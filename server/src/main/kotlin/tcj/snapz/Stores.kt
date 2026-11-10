package tcj.snapz

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import misk.exceptions.BadRequestException
import misk.exceptions.ConflictException
import misk.exceptions.NotFoundException

// ---------------------------------------------------------------------------------------------
// Wire DTOs (JSON, snake_case). Nulls are omitted on the wire.
// ---------------------------------------------------------------------------------------------

data class CollectionDto(
  val slug: String,
  val name: String,
  val description: String?,
  val icon: String?,
  val photo_count: Long,
  val bytes: Long,
  val last_photo_at: String?,
  val created_at: String,
  val updated_at: String,
  val deleted_at: String?,
  val feed_sync_id: Long?,
)

data class PhotoDto(
  val token: String,
  val collection: String,
  val sha256: String,
  val content_type: String,
  val bytes: Long,
  val width: Int?,
  val height: Int?,
  val taken_at: String,
  val note: String?,
  val meta: Map<String, Any?>,
  /** Denormalized from meta.barcodes[].value so feed consumers don't have to dig. */
  val barcodes: List<String>,
  val client_id: String?,
  val url: String,
  val thumb_url: String,
  val created_at: String,
  val updated_at: String,
  val deleted_at: String?,
  val feed_sync_id: Long?,
)

data class RecordDto(
  val ns: String,
  val k: String,
  val v: Any?,
  val created_at: String,
  val updated_at: String,
  val deleted_at: String?,
  val feed_sync_id: Long?,
)

data class TokenDto(
  val id: Long,
  val kind: String,
  val name: String,
  val principal: String,
  val created_at: String,
  val last_used_at: String?,
  val expires_at: String?,
)

private val SLUG = Regex("^[a-z0-9][a-z0-9_-]{0,62}$")

fun slugify(name: String): String =
  name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(63).ifEmpty { throw BadRequestException("can't derive a slug from '$name'") }

// ---------------------------------------------------------------------------------------------

@Singleton
class CollectionStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: SnapzConfig,
) {
  private val select = """
    SELECT c.*, COALESCE(s.n, 0) AS photo_count, COALESCE(s.bytes, 0) AS photo_bytes, s.last_taken
    FROM collections c
    LEFT JOIN (SELECT collection_id, COUNT(*) n, SUM(bytes) bytes, MAX(taken_at_ms) last_taken
               FROM photos WHERE deleted_at_ms IS NULL GROUP BY collection_id) s ON s.collection_id = c.id
  """.trimIndent()

  private fun dto(rs: ResultSet) = CollectionDto(
    slug = rs.getString("slug"),
    name = rs.getString("name"),
    description = rs.getString("description"),
    icon = rs.getString("icon"),
    photo_count = rs.getLong("photo_count"),
    bytes = rs.getLong("photo_bytes"),
    last_photo_at = isoOrNull(rs.longOrNull("last_taken")),
    created_at = iso(rs.getLong("created_at_ms")),
    updated_at = iso(rs.getLong("updated_at_ms")),
    deleted_at = isoOrNull(rs.longOrNull("deleted_at_ms")),
    feed_sync_id = rs.longOrNull("feed_sync_id"),
  )

  fun list(): List<CollectionDto> = db.tx { c ->
    c.select("$select WHERE c.deleted_at_ms IS NULL ORDER BY c.name", map = ::dto)
  }

  fun get(slug: String): CollectionDto = db.tx { c -> get(c, slug) }

  private fun get(c: Connection, slug: String): CollectionDto =
    c.select("$select WHERE c.slug = ? AND c.deleted_at_ms IS NULL", listOf(slug), ::dto).firstOrNull()
      ?: throw NotFoundException("no collection '$slug'")

  /** Live collection id for a slug, or 404. */
  fun idOf(c: Connection, slug: String): Long =
    c.select("SELECT id FROM collections WHERE slug = ? AND deleted_at_ms IS NULL", listOf(slug)) { it.getLong(1) }.firstOrNull()
      ?: throw NotFoundException("no collection '$slug'")

  fun create(slug: String?, name: String, description: String?, icon: String?): CollectionDto {
    val s = slug?.takeIf { it.isNotBlank() } ?: slugify(name)
    if (!SLUG.matches(s)) throw BadRequestException("bad slug '$s': want ${SLUG.pattern}")
    val now = clock.millis()
    return db.tx { c ->
      try {
        c.insert(
          "INSERT INTO collections (slug, name, description, icon, created_at_ms, updated_at_ms, shard) VALUES (?,?,?,?,?,?,?)",
          listOf(s, name, description, icon, now, now, shardOf(s, config.snapz.feed_shards)),
        )
      } catch (e: SQLIntegrityConstraintViolationException) {
        throw ConflictException("collection '$s' already exists")
      }
      get(c, s)
    }
  }

  fun update(slug: String, name: String?, description: String?, icon: String?): CollectionDto = db.tx { c ->
    val id = idOf(c, slug)
    val sets = mutableListOf("updated_at_ms = ?", "feed_sync_id = NULL")
    val args = mutableListOf<Any?>(clock.millis())
    name?.let { sets += "name = ?"; args += it }
    description?.let { sets += "description = ?"; args += it }
    icon?.let { sets += "icon = ?"; args += it }
    c.exec("UPDATE collections SET ${sets.joinToString()} WHERE id = ?", args + id)
    get(c, slug)
  }

  /**
   * Soft delete. Refuses a non-empty collection unless [force], which also trashes its photos.
   * The slug is mangled so the name can be reused; the tombstone still flows through the feed.
   */
  fun delete(slug: String, force: Boolean) {
    val now = clock.millis()
    db.tx { c ->
      val id = idOf(c, slug)
      val n = c.select("SELECT COUNT(*) FROM photos WHERE collection_id = ? AND deleted_at_ms IS NULL", listOf(id)) { it.getLong(1) }.first()
      if (n > 0 && !force) throw ConflictException("collection '$slug' has $n photos; pass force=true to trash them too")
      c.exec("UPDATE photos SET deleted_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE collection_id = ? AND deleted_at_ms IS NULL", listOf(now, now, id))
      c.exec("UPDATE collections SET slug = ?, deleted_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?", listOf("$slug.deleted.$id", now, now, id))
    }
  }

  fun feed(after: Long, limit: Int, shard: Int?, shardCount: Int?): List<CollectionDto> = db.tx { c ->
    val (cond, args) = shardCond("c", shard, shardCount)
    c.select("$select WHERE c.feed_sync_id > ? AND c.shard >= 0 $cond ORDER BY c.feed_sync_id LIMIT ?", listOf<Any?>(after) + args + limit, ::dto)
  }
}

internal fun shardCond(alias: String, shard: Int?, shardCount: Int?): Pair<String, List<Any?>> =
  if (shard != null && shardCount != null && shardCount > 1) {
    if (shard !in 0 until shardCount) throw BadRequestException("shard must be in [0, shard_count)")
    "AND $alias.shard % ? = ?" to listOf(shardCount, shard)
  } else {
    "" to listOf()
  }

// ---------------------------------------------------------------------------------------------

data class PhotoPage(val photos: List<PhotoDto>, val next_before: String?)

@Singleton
class PhotoStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: SnapzConfig,
  private val json: Json,
  private val collections: CollectionStore,
) {
  private val select = """
    SELECT p.id, p.token, c.slug AS collection, p.sha256, p.content_type, p.bytes, p.width, p.height, p.taken_at_ms,
           p.note, p.meta, p.barcodes, p.client_id, p.created_at_ms, p.updated_at_ms, p.deleted_at_ms, p.feed_sync_id
    FROM photos p JOIN collections c ON c.id = p.collection_id
  """.trimIndent()

  private fun dto(rs: ResultSet): PhotoDto {
    val token = rs.getString("token")
    return PhotoDto(
      token = token,
      collection = rs.getString("collection"),
      sha256 = rs.getString("sha256"),
      content_type = rs.getString("content_type"),
      bytes = rs.getLong("bytes"),
      width = rs.intOrNull("width"),
      height = rs.intOrNull("height"),
      taken_at = iso(rs.getLong("taken_at_ms")),
      note = rs.getString("note"),
      meta = json.parseObject(rs.getString("meta")),
      barcodes = rs.getString("barcodes")?.split(',')?.filter { it.isNotEmpty() } ?: listOf(),
      client_id = rs.getString("client_id"),
      url = "/blobs/$token",
      thumb_url = "/blobs/$token?size=thumb",
      created_at = iso(rs.getLong("created_at_ms")),
      updated_at = iso(rs.getLong("updated_at_ms")),
      deleted_at = isoOrNull(rs.longOrNull("deleted_at_ms")),
      feed_sync_id = rs.longOrNull("feed_sync_id"),
    )
  }

  /** ",v1,v2," so `LIKE '%,v1,%'` matches whole codes; null when there are none. */
  private fun barcodeColumn(meta: Map<String, Any?>): String? {
    val values = (meta["barcodes"] as? List<*>).orEmpty().mapNotNull {
      when (it) {
        is Map<*, *> -> it["value"]?.toString()
        is String -> it
        else -> null
      }
    }.map { it.replace(",", "") }.filter { it.isNotBlank() }.distinct()
    return if (values.isEmpty()) null else values.joinToString(",", ",", ",").take(1024)
  }

  fun get(token: String, includeDeleted: Boolean = true): PhotoDto = db.tx { c -> get(c, token, includeDeleted) }

  private fun get(c: Connection, token: String, includeDeleted: Boolean = true): PhotoDto =
    c.select("$select WHERE p.token = ?", listOf(token), ::dto).firstOrNull()?.takeIf { includeDeleted || it.deleted_at == null }
      ?: throw NotFoundException("no photo '$token'")

  /**
   * Newest first by (taken_at, id). [before] is the opaque `next_before` of the previous page.
   * [deleted]: false = live only, true = trash only.
   */
  fun list(collection: String?, before: String?, limit: Int, barcode: String?, deleted: Boolean): PhotoPage = db.tx { c ->
    val conds = mutableListOf(if (deleted) "p.deleted_at_ms IS NOT NULL" else "p.deleted_at_ms IS NULL")
    val args = mutableListOf<Any?>()
    if (collection != null) {
      conds += "p.collection_id = ?"
      args += collections.idOf(c, collection)
    }
    if (!barcode.isNullOrBlank()) {
      conds += "p.barcodes LIKE ?"
      args += "%,${barcode.replace("%", "").replace("_", "")},%"
    }
    if (before != null) {
      val parts = before.split('_').mapNotNull { it.toLongOrNull() }
      if (parts.size != 2) throw BadRequestException("bad 'before' cursor")
      conds += "(p.taken_at_ms < ? OR (p.taken_at_ms = ? AND p.id < ?))"
      args.addAll(listOf(parts[0], parts[0], parts[1]))
    }
    val n = limit.coerceIn(1, 500)
    var last: String? = null
    val rows = c.select("$select WHERE ${conds.joinToString(" AND ")} ORDER BY p.taken_at_ms DESC, p.id DESC LIMIT ?", args + (n + 1)) { rs ->
      Triple(dto(rs), rs.getLong("taken_at_ms"), rs.getLong("id"))
    }
    val page = rows.take(n)
    if (rows.size > n) last = page.last().let { "${it.second}_${it.third}" }
    PhotoPage(page.map { it.first }, last)
  }

  /**
   * Insert a photo row for an already-stored blob. [clientId] is the uploader's idempotency key:
   * a retry of an upload that actually landed returns the existing row instead of a duplicate.
   */
  fun create(collection: String, blob: BlobInfo, takenAtMs: Long?, note: String?, meta: Map<String, Any?>, clientId: String?): Pair<PhotoDto, Boolean> {
    val now = clock.millis()
    fun existing(c: Connection) = clientId?.let { c.select("$select WHERE p.client_id = ?", listOf(it), ::dto).firstOrNull() }
    return try {
      db.tx { c ->
        existing(c)?.let { return@tx it to false }
        val cid = collections.idOf(c, collection)
        val token = randomToken()
        c.insert(
          """
          INSERT INTO photos (token, collection_id, sha256, content_type, bytes, width, height, taken_at_ms, note, meta, barcodes,
                              client_id, created_at_ms, updated_at_ms, shard)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """.trimIndent(),
          listOf(
            token, cid, blob.sha256, blob.contentType, blob.bytes, blob.width, blob.height, takenAtMs ?: now, note?.takeIf { it.isNotBlank() },
            json.write(meta), barcodeColumn(meta), clientId, now, now, shardOf(token, config.snapz.feed_shards),
          ),
        )
        get(c, token) to true
      }
    } catch (e: Exception) {
      // Lost a race with a concurrent retry carrying the same client_id (unique-key violation,
      // possibly wrapped by the transacter); anything else rethrows.
      db.tx { c -> existing(c) }?.let { it to false } ?: throw e
    }
  }

  /** Null arguments are left unchanged; [collection] moves the photo. */
  fun update(token: String, note: String?, meta: Map<String, Any?>?, takenAtMs: Long?, collection: String?): PhotoDto = db.tx { c ->
    get(c, token, includeDeleted = false)
    val sets = mutableListOf("updated_at_ms = ?", "feed_sync_id = NULL")
    val args = mutableListOf<Any?>(clock.millis())
    note?.let { sets += "note = ?"; args += it.takeIf { n -> n.isNotBlank() } }
    meta?.let { sets += "meta = ?"; args += json.write(it); sets += "barcodes = ?"; args += barcodeColumn(it) }
    takenAtMs?.let { sets += "taken_at_ms = ?"; args += it }
    collection?.let { sets += "collection_id = ?"; args += collections.idOf(c, it) }
    c.exec("UPDATE photos SET ${sets.joinToString()} WHERE token = ?", args + token)
    get(c, token)
  }

  /** New row, same blob. Copies are independent from then on (notes, meta, deletion). */
  fun copy(token: String, collection: String): PhotoDto = db.tx { c -> copy(c, token, collection) }

  private fun copy(c: Connection, token: String, collection: String): PhotoDto {
    get(c, token, includeDeleted = false)
    val cid = collections.idOf(c, collection)
    val now = clock.millis()
    val newToken = randomToken()
    c.exec(
      """
      INSERT INTO photos (token, collection_id, sha256, content_type, bytes, width, height, taken_at_ms, note, meta, barcodes,
                          created_at_ms, updated_at_ms, shard)
      SELECT ?, ?, sha256, content_type, bytes, width, height, taken_at_ms, note, meta, barcodes, ?, ?, ?
      FROM photos WHERE token = ?
      """.trimIndent(),
      listOf(newToken, cid, now, now, shardOf(newToken, config.snapz.feed_shards), token),
    )
    return get(c, newToken)
  }

  fun delete(token: String): PhotoDto = db.tx { c -> setDeleted(c, token, true) }

  fun restore(token: String): PhotoDto = db.tx { c -> setDeleted(c, token, false) }

  private fun setDeleted(c: Connection, token: String, deleted: Boolean): PhotoDto {
    val p = get(c, token)
    if (!deleted) {
      // Restoring into a deleted collection would resurrect an invisible photo.
      val live = c.select("SELECT c.deleted_at_ms FROM photos p JOIN collections c ON c.id = p.collection_id WHERE p.token = ?", listOf(token)) { it.longOrNull("deleted_at_ms") }.first() == null
      if (!live) throw ConflictException("photo '$token' belongs to a deleted collection; move it first")
    }
    if ((p.deleted_at != null) == deleted) return p
    val now = clock.millis()
    c.exec("UPDATE photos SET deleted_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE token = ?", listOf(if (deleted) now else null, now, token))
    return get(c, token)
  }

  /** All-or-nothing: one transaction for the whole selection. */
  fun batch(op: String, tokens: List<String>, collection: String?): List<PhotoDto> {
    if (tokens.size > 2000) throw BadRequestException("at most 2000 tokens per batch")
    return db.tx { c ->
      tokens.distinct().map { t ->
        when (op) {
          "delete" -> setDeleted(c, t, true)
          "restore" -> setDeleted(c, t, false)
          "copy" -> copy(c, t, collection ?: throw BadRequestException("copy needs 'collection'"))
          "move" -> {
            val cid = collections.idOf(c, collection ?: throw BadRequestException("move needs 'collection'"))
            get(c, t)
            // Moving out of the trash is allowed: it's how photos of a deleted collection are rescued.
            c.exec("UPDATE photos SET collection_id = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE token = ?", listOf(cid, clock.millis(), t))
            get(c, t)
          }
          else -> throw BadRequestException("unknown op '$op': want move|copy|delete|restore")
        }
      }
    }
  }

  /** sha256 + content type for serving; soft-deleted photos stay viewable (the trash needs them). */
  fun blobRef(token: String): Pair<String, String> = db.tx { c ->
    c.select("SELECT sha256, content_type FROM photos WHERE token = ?", listOf(token)) { it.getString(1) to it.getString(2) }.firstOrNull()
      ?: throw NotFoundException("no photo '$token'")
  }

  /**
   * Hard-deletes trashed photos older than [olderThanMs]; returns the sha256s no row references
   * any more (the caller removes those blobs). Purged rows simply vanish from the feed — consumers
   * already saw the soft-delete tombstone.
   */
  fun purge(olderThanMs: Long): Pair<Int, List<String>> = db.tx { c ->
    val cutoff = clock.millis() - olderThanMs
    val shas = c.select("SELECT DISTINCT sha256 FROM photos WHERE deleted_at_ms IS NOT NULL AND deleted_at_ms <= ?", listOf(cutoff)) { it.getString(1) }
    val n = c.exec("DELETE FROM photos WHERE deleted_at_ms IS NOT NULL AND deleted_at_ms <= ?", listOf(cutoff))
    val orphans = shas.filter { sha -> c.select("SELECT 1 FROM photos WHERE sha256 = ? LIMIT 1", listOf(sha)) { 1 }.isEmpty() }
    n to orphans
  }

  fun feed(after: Long, limit: Int, shard: Int?, shardCount: Int?, collection: String?): List<PhotoDto> = db.tx { c ->
    val (cond, shardArgs) = shardCond("p", shard, shardCount)
    val args = mutableListOf<Any?>(after)
    args.addAll(shardArgs)
    var collCond = ""
    if (collection != null) {
      collCond = "AND p.collection_id = ?"
      args += collections.idOf(c, collection)
    }
    c.select("$select WHERE p.feed_sync_id > ? AND p.shard >= 0 $cond $collCond ORDER BY p.feed_sync_id LIMIT ?", args + limit, ::dto)
  }
}

// ---------------------------------------------------------------------------------------------

data class NamespaceDto(val ns: String, val count: Long, val updated_at: String?)

/**
 * Generic JSON documents keyed by (ns, k), feed-published like everything else. This is where
 * stream processors write their output (e.g. ns=order_items), which makes transforms chainable:
 * a derived namespace is itself a feed.
 */
@Singleton
class RecordStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: SnapzConfig,
  private val json: Json,
) {
  private fun dto(rs: ResultSet) = RecordDto(
    ns = rs.getString("ns"),
    k = rs.getString("k"),
    v = json.parse(rs.getString("v")),
    created_at = iso(rs.getLong("created_at_ms")),
    updated_at = iso(rs.getLong("updated_at_ms")),
    deleted_at = isoOrNull(rs.longOrNull("deleted_at_ms")),
    feed_sync_id = rs.longOrNull("feed_sync_id"),
  )

  fun namespaces(): List<NamespaceDto> = db.tx { c ->
    c.select("SELECT ns, COUNT(*) n, MAX(updated_at_ms) u FROM records WHERE deleted_at_ms IS NULL GROUP BY ns ORDER BY ns") {
      NamespaceDto(it.getString("ns"), it.getLong("n"), isoOrNull(it.longOrNull("u")))
    }
  }

  fun scan(ns: String, prefix: String?, limit: Int): List<RecordDto> = db.tx { c ->
    val like = (prefix ?: "").replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    c.select("SELECT * FROM records WHERE ns = ? AND k LIKE ? AND deleted_at_ms IS NULL ORDER BY k LIMIT ?", listOf(ns, like, limit.coerceIn(1, 5000)), ::dto)
  }

  fun get(ns: String, k: String): RecordDto = db.tx { c -> find(c, ns, k) }?.takeIf { it.deleted_at == null } ?: throw NotFoundException("no record $ns/$k")

  private fun find(c: Connection, ns: String, k: String) = c.select("SELECT * FROM records WHERE ns = ? AND k = ?", listOf(ns, k), ::dto).firstOrNull()

  /**
   * Upsert. A value-identical put is a no-op (no feed republish), so a transform that re-derives
   * the same output after a replay doesn't ripple downstream.
   */
  fun put(ns: String, k: String, v: Any?): RecordDto {
    if (ns.isBlank() || ns.length > 128 || k.isBlank() || k.length > 255) throw BadRequestException("bad ns/k")
    val text = json.write(v)
    val now = clock.millis()
    return db.tx { c ->
      val cur = c.select("SELECT v, deleted_at_ms FROM records WHERE ns = ? AND k = ? FOR UPDATE", listOf(ns, k)) { it.getString(1) to it.longOrNull("deleted_at_ms") }.firstOrNull()
      when {
        cur == null -> c.exec(
          "INSERT INTO records (ns, k, v, created_at_ms, updated_at_ms, shard) VALUES (?,?,?,?,?,?)",
          listOf(ns, k, text, now, now, shardOf("$ns:$k", config.snapz.feed_shards)),
        )
        cur.first != text || cur.second != null ->
          c.exec("UPDATE records SET v = ?, updated_at_ms = ?, deleted_at_ms = NULL, feed_sync_id = NULL WHERE ns = ? AND k = ?", listOf(text, now, ns, k))
      }
      find(c, ns, k)!!
    }
  }

  fun delete(ns: String, k: String) {
    val now = clock.millis()
    val n = db.tx { c ->
      c.exec("UPDATE records SET deleted_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE ns = ? AND k = ? AND deleted_at_ms IS NULL", listOf(now, now, ns, k))
    }
    if (n == 0) throw NotFoundException("no record $ns/$k")
  }

  fun feed(after: Long, limit: Int, shard: Int?, shardCount: Int?, ns: String?): List<RecordDto> = db.tx { c ->
    val (cond, shardArgs) = shardCond("r", shard, shardCount)
    val args = mutableListOf<Any?>(after)
    args.addAll(shardArgs)
    var nsCond = ""
    if (ns != null) {
      nsCond = "AND r.ns = ?"
      args += ns
    }
    c.select("SELECT r.* FROM records r WHERE r.feed_sync_id > ? AND r.shard >= 0 $cond $nsCond ORDER BY r.feed_sync_id LIMIT ?", args + limit, ::dto)
  }
}

// ---------------------------------------------------------------------------------------------

data class ResolvedToken(val id: Long?, val kind: String, val principal: String)

/** Bearer credentials: web sessions, per-device tokens (iOS), and named script tokens. Only SHA-256 hashes are stored. */
@Singleton
class TokenStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: SnapzConfig,
) {
  fun create(kind: String, name: String, principal: String, ttlMs: Long? = null): Pair<Long, String> {
    val plain = "snapz_" + randomSecret()
    val now = clock.millis()
    val id = db.tx { c ->
      c.insert(
        "INSERT INTO api_tokens (kind, name, token_hash, principal, created_at_ms, expires_at_ms) VALUES (?,?,?,?,?,?)",
        listOf(kind, name.take(255), sha256Hex(plain.toByteArray()), principal, now, ttlMs?.let { now + it }),
      )
    }
    return id to plain
  }

  fun resolve(plain: String): ResolvedToken? {
    for (b in config.snapz.bootstrap_tokens) {
      if (java.security.MessageDigest.isEqual(b.token.toByteArray(), plain.toByteArray())) return ResolvedToken(null, "bootstrap", b.principal)
    }
    if (!plain.startsWith("snapz_")) return null
    val now = clock.millis()
    return db.tx { c ->
      val row = c.select(
        "SELECT id, kind, principal, last_used_at_ms FROM api_tokens WHERE token_hash = ? AND revoked_at_ms IS NULL AND (expires_at_ms IS NULL OR expires_at_ms > ?)",
        listOf(sha256Hex(plain.toByteArray()), now),
      ) { rs -> Triple(ResolvedToken(rs.getLong("id"), rs.getString("kind"), rs.getString("principal")), rs.longOrNull("last_used_at_ms"), 0) }.firstOrNull()
        ?: return@tx null
      // Coarse last-used tracking: at most one write per token per minute.
      if (row.second == null || now - row.second!! > 60_000) c.exec("UPDATE api_tokens SET last_used_at_ms = ? WHERE id = ?", listOf(now, row.first.id))
      row.first
    }
  }

  fun list(): List<TokenDto> = db.tx { c ->
    c.select("SELECT * FROM api_tokens WHERE revoked_at_ms IS NULL AND (expires_at_ms IS NULL OR expires_at_ms > ?) ORDER BY kind, id", listOf(clock.millis())) { rs ->
      TokenDto(
        id = rs.getLong("id"),
        kind = rs.getString("kind"),
        name = rs.getString("name"),
        principal = rs.getString("principal"),
        created_at = iso(rs.getLong("created_at_ms")),
        last_used_at = isoOrNull(rs.longOrNull("last_used_at_ms")),
        expires_at = isoOrNull(rs.longOrNull("expires_at_ms")),
      )
    }
  }

  fun revoke(id: Long): Boolean = db.tx { c ->
    c.exec("UPDATE api_tokens SET revoked_at_ms = ? WHERE id = ? AND revoked_at_ms IS NULL", listOf(clock.millis(), id)) > 0
  }
}
