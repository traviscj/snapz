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

/**
 * One verb a photo filed in a collection can carry (photo meta `action`, plus `qty` when [qty]).
 * The server stores and validates the names; what they mean is up to whoever consumes the feed.
 */
data class ActionDto(val name: String, val label: String?, val qty: Boolean = false)

data class CollectionDto(
  val vault: String,
  val slug: String,
  val name: String,
  val description: String?,
  val icon: String?,
  /** The collection's action set, in display order. Empty = photos here carry no verb. */
  val actions: List<ActionDto>,
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
  val vault: String,
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
  /** Token of the version the blob columns above describe (null for photos never versioned). */
  val current_version: String?,
  val url: String,
  val thumb_url: String,
  val created_at: String,
  val updated_at: String,
  val deleted_at: String?,
  val feed_sync_id: Long?,
)

data class RecordDto(
  val vault: String,
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
  /** Slug of the vault this credential is pinned to; null = all vaults + admin routes. */
  val vault: String?,
  val read_only: Boolean,
)

private val ACTION_NAME = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")
internal val SLUG = Regex("^[a-z0-9][a-z0-9_-]{0,62}$")

fun slugify(name: String): String =
  name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(63).ifEmpty { throw BadRequestException("can't derive a slug from '$name'") }

// ---------------------------------------------------------------------------------------------

@Singleton
class CollectionStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: SnapzConfig,
  private val json: Json,
) {
  private fun parseActions(text: String?): List<ActionDto> = if (text.isNullOrBlank()) listOf() else (json.parse(text) as? List<*>).orEmpty().mapNotNull {
    val m = it as? Map<*, *> ?: return@mapNotNull null
    ActionDto(m["name"]?.toString() ?: return@mapNotNull null, m["label"]?.toString(), m["qty"] == true)
  }

  fun actionsOf(c: Connection, collectionId: Long): List<ActionDto> =
    parseActions(c.select("SELECT actions FROM collections WHERE id = ?", listOf(collectionId)) { it.getString(1) }.firstOrNull())

  /** Replace the collection's action set. Photos already carrying a verb that was dropped keep it. */
  fun setActions(vaultId: Long, slug: String, actions: List<ActionDto>): CollectionDto = db.tx { c ->
    val id = idOf(c, vaultId, slug)
    if (actions.size > 12) throw BadRequestException("at most 12 actions per collection")
    actions.forEach { if (!ACTION_NAME.matches(it.name)) throw BadRequestException("bad action name '${it.name}': want ${ACTION_NAME.pattern}") }
    if (actions.map { it.name }.distinct().size != actions.size) throw BadRequestException("duplicate action name")
    val text = if (actions.isEmpty()) null else json.write(actions.map { a -> listOfNotNull("name" to a.name, a.label?.takeIf { it.isNotBlank() }?.let { "label" to it }, "qty" to a.qty).toMap() })
    c.exec("UPDATE collections SET actions = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?", listOf(text, clock.millis(), id))
    get(c, vaultId, slug)
  }

  private val select = """
    SELECT c.*, vt.slug AS vault, COALESCE(s.n, 0) AS photo_count, COALESCE(s.bytes, 0) AS photo_bytes, s.last_taken
    FROM collections c JOIN vaults vt ON vt.id = c.vault_id
    LEFT JOIN (SELECT collection_id, COUNT(*) n, SUM(bytes) bytes, MAX(taken_at_ms) last_taken
               FROM photos WHERE deleted_at_ms IS NULL GROUP BY collection_id) s ON s.collection_id = c.id
  """.trimIndent()

  private fun dto(rs: ResultSet) = CollectionDto(
    vault = rs.getString("vault"),
    slug = rs.getString("slug"),
    name = rs.getString("name"),
    description = rs.getString("description"),
    icon = rs.getString("icon"),
    actions = parseActions(rs.getString("actions")),
    photo_count = rs.getLong("photo_count"),
    bytes = rs.getLong("photo_bytes"),
    last_photo_at = isoOrNull(rs.longOrNull("last_taken")),
    created_at = iso(rs.getLong("created_at_ms")),
    updated_at = iso(rs.getLong("updated_at_ms")),
    deleted_at = isoOrNull(rs.longOrNull("deleted_at_ms")),
    feed_sync_id = rs.longOrNull("feed_sync_id"),
  )

  fun list(vaultId: Long): List<CollectionDto> = db.tx { c ->
    c.select("$select WHERE c.vault_id = ? AND c.deleted_at_ms IS NULL ORDER BY c.name", listOf(vaultId), ::dto)
  }

  fun get(vaultId: Long, slug: String): CollectionDto = db.tx { c -> get(c, vaultId, slug) }

  private fun get(c: Connection, vaultId: Long, slug: String): CollectionDto =
    c.select("$select WHERE c.vault_id = ? AND c.slug = ? AND c.deleted_at_ms IS NULL", listOf(vaultId, slug), ::dto).firstOrNull()
      ?: throw NotFoundException("no collection '$slug'")

  /** Live collection id for a slug in a vault, or 404. */
  fun idOf(c: Connection, vaultId: Long, slug: String): Long =
    c.select("SELECT id FROM collections WHERE vault_id = ? AND slug = ? AND deleted_at_ms IS NULL", listOf(vaultId, slug)) { it.getLong(1) }.firstOrNull()
      ?: throw NotFoundException("no collection '$slug'")

  fun create(vaultId: Long, slug: String?, name: String, description: String?, icon: String?): CollectionDto {
    val s = slug?.takeIf { it.isNotBlank() } ?: slugify(name)
    if (!SLUG.matches(s)) throw BadRequestException("bad slug '$s': want ${SLUG.pattern}")
    val now = clock.millis()
    return db.tx { c ->
      try {
        c.insert(
          "INSERT INTO collections (vault_id, slug, name, description, icon, created_at_ms, updated_at_ms, shard) VALUES (?,?,?,?,?,?,?,?)",
          listOf(vaultId, s, name, description, icon, now, now, shardOf(s, config.snapz.feed_shards)),
        )
      } catch (e: SQLIntegrityConstraintViolationException) {
        throw ConflictException("collection '$s' already exists")
      }
      get(c, vaultId, s)
    }
  }

  fun update(vaultId: Long, slug: String, name: String?, description: String?, icon: String?): CollectionDto = db.tx { c ->
    val id = idOf(c, vaultId, slug)
    val sets = mutableListOf("updated_at_ms = ?", "feed_sync_id = NULL")
    val args = mutableListOf<Any?>(clock.millis())
    name?.let { sets += "name = ?"; args += it }
    description?.let { sets += "description = ?"; args += it }
    icon?.let { sets += "icon = ?"; args += it }
    c.exec("UPDATE collections SET ${sets.joinToString()} WHERE id = ?", args + id)
    get(c, vaultId, slug)
  }

  /**
   * Soft delete. Refuses a non-empty collection unless [force], which also trashes its photos.
   * The slug is mangled so the name can be reused; the tombstone still flows through the feed.
   */
  fun delete(vaultId: Long, slug: String, force: Boolean) {
    val now = clock.millis()
    db.tx { c ->
      val id = idOf(c, vaultId, slug)
      val n = c.select("SELECT COUNT(*) FROM photos WHERE collection_id = ? AND deleted_at_ms IS NULL", listOf(id)) { it.getLong(1) }.first()
      if (n > 0 && !force) throw ConflictException("collection '$slug' has $n photos; pass force=true to trash them too")
      c.exec("UPDATE photos SET deleted_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE collection_id = ? AND deleted_at_ms IS NULL", listOf(now, now, id))
      c.exec("UPDATE collections SET slug = ?, deleted_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?", listOf("$slug.deleted.$id", now, now, id))
    }
  }

  fun feed(after: Long, limit: Int, shard: Int?, shardCount: Int?, vaultId: Long?): List<CollectionDto> = db.tx { c ->
    val (cond, args) = shardCond("c", shard, shardCount)
    val only = if (vaultId != null) "AND c.vault_id = ?" else ""
    c.select("$select WHERE c.feed_sync_id > ? AND c.shard >= 0 $cond $only ORDER BY c.feed_sync_id LIMIT ?", listOf<Any?>(after) + args + listOfNotNull(vaultId) + limit, ::dto)
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

/**
 * Low-level writes for a photo's history: its version tree and the transform intents queued
 * against it. Lives below PhotoStore and TransformStore so both can use it inside their own
 * transactions.
 *
 * A version is one stored image. The upload is the root; every image-rewriting transform adds a
 * child of the version it read. The photo row mirrors whichever version is "current".
 */
@Singleton
class Lineage @Inject constructor(private val clock: Clock, private val config: SnapzConfig) {
  fun addVersion(c: Connection, photoId: Long, parentId: Long?, transform: String?, blob: BlobInfo, makeCurrent: Boolean): Long {
    val id = c.insert(
      "INSERT INTO photo_versions (token, photo_id, parent_version_id, transform, sha256, content_type, bytes, width, height, created_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?)",
      listOf(randomToken(), photoId, parentId, transform, blob.sha256, blob.contentType, blob.bytes, blob.width, blob.height, clock.millis()),
    )
    if (makeCurrent) setCurrent(c, photoId, id)
    return id
  }

  /** Points the photo at a version: copies its blob columns onto the row and requeues it for the feed. */
  fun setCurrent(c: Connection, photoId: Long, versionId: Long) {
    val n = c.exec(
      """
      UPDATE photos p JOIN photo_versions v ON v.id = ? AND v.photo_id = p.id
      SET p.current_version_id = v.id, p.sha256 = v.sha256, p.content_type = v.content_type, p.bytes = v.bytes,
          p.width = v.width, p.height = v.height, p.updated_at_ms = ?, p.feed_sync_id = NULL
      WHERE p.id = ?
      """.trimIndent(),
      listOf(versionId, clock.millis(), photoId),
    )
    if (n == 0) throw NotFoundException("version does not belong to this photo")
  }

  /** Current version id, creating the root for photos that predate versioning. */
  fun currentVersion(c: Connection, photoId: Long): Long {
    val row = c.select("SELECT current_version_id, sha256, content_type, bytes, width, height FROM photos WHERE id = ? FOR UPDATE", listOf(photoId)) { rs ->
      rs.longOrNull("current_version_id") to BlobInfo(rs.getString("sha256"), rs.getString("content_type"), rs.getLong("bytes"), rs.intOrNull("width"), rs.intOrNull("height"))
    }.firstOrNull() ?: throw NotFoundException("no such photo")
    row.first?.let { return it }
    val id = c.insert(
      "INSERT INTO photo_versions (token, photo_id, parent_version_id, transform, sha256, content_type, bytes, width, height, created_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?)",
      listOf(randomToken(), photoId, null, null, row.second.sha256, row.second.contentType, row.second.bytes, row.second.width, row.second.height, clock.millis()),
    )
    // Not a content change: no feed requeue.
    c.exec("UPDATE photos SET current_version_id = ? WHERE id = ?", listOf(id, photoId))
    return id
  }

  fun addIntent(c: Connection, photoId: Long, transform: String, params: String?, origin: String, collectionId: Long?, position: Int?, by: String?): String {
    val token = randomToken()
    c.insert(
      """
      INSERT INTO transform_intents (token, photo_id, version_id, transform, params, origin, collection_id, chain_position, requested_by, created_at_ms, shard)
      VALUES (?,?,?,?,?,?,?,?,?,?,?)
      """.trimIndent(),
      listOf(token, photoId, currentVersion(c, photoId), transform, params, origin, collectionId, position, by, clock.millis(), shardOf(token, config.snapz.feed_shards)),
    )
    return token
  }

  /**
   * Queue the next link of a collection's transform chain: the first one at a position greater
   * than [after] (null = start of the chain). Chains run one link at a time, each on the photo as
   * the previous link left it — the interceptor model, with the hand-off made explicit as events.
   */
  fun advanceChain(c: Connection, photoId: Long, collectionId: Long, after: Int?): String? {
    val next = c.select(
      "SELECT position, transform, params FROM collection_transforms WHERE collection_id = ? AND position > ? ORDER BY position LIMIT 1",
      listOf(collectionId, after ?: -1),
    ) { Triple(it.getInt(1), it.getString(2), it.getString(3)) }.firstOrNull() ?: return null
    return addIntent(c, photoId, next.second, next.third, "chain", collectionId, next.first, null)
  }
}

data class PhotoPage(val photos: List<PhotoDto>, val next_before: String?)

@Singleton
class PhotoStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: SnapzConfig,
  private val json: Json,
  private val collections: CollectionStore,
  private val lineage: Lineage,
) {
  private val select = """
    SELECT p.id, p.token, vt.slug AS vault, c.slug AS collection, p.sha256, p.content_type, p.bytes, p.width, p.height, p.taken_at_ms,
           p.note, p.meta, p.barcodes, p.client_id, p.created_at_ms, p.updated_at_ms, p.deleted_at_ms, p.feed_sync_id,
           v.token AS version_token
    FROM photos p JOIN collections c ON c.id = p.collection_id JOIN vaults vt ON vt.id = c.vault_id
    LEFT JOIN photo_versions v ON v.id = p.current_version_id
  """.trimIndent()

  private fun dto(rs: ResultSet): PhotoDto {
    val token = rs.getString("token")
    return PhotoDto(
      token = token,
      vault = rs.getString("vault"),
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
      current_version = rs.getString("version_token"),
      // `v` pins the URL to the current bytes: cacheable forever, and it changes when a
      // transform swaps the current version.
      url = "/blobs/$token?v=${rs.getString("sha256").take(10)}",
      thumb_url = "/blobs/$token?size=thumb&v=${rs.getString("sha256").take(10)}",
      created_at = iso(rs.getLong("created_at_ms")),
      updated_at = iso(rs.getLong("updated_at_ms")),
      deleted_at = isoOrNull(rs.longOrNull("deleted_at_ms")),
      feed_sync_id = rs.longOrNull("feed_sync_id"),
    )
  }

  /** ",v1,v2," so `LIKE '%,v1,%'` matches whole codes; null when there are none. */
  fun barcodeColumn(meta: Map<String, Any?>): String? {
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
  fun list(vaultId: Long, collection: String?, before: String?, limit: Int, barcode: String?, deleted: Boolean): PhotoPage = db.tx { c ->
    val conds = mutableListOf(if (deleted) "p.deleted_at_ms IS NOT NULL" else "p.deleted_at_ms IS NULL", "c.vault_id = ?")
    val args = mutableListOf<Any?>(vaultId)
    if (collection != null) {
      conds += "p.collection_id = ?"
      args += collections.idOf(c, vaultId, collection)
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
  fun create(vaultId: Long, collection: String, blob: BlobInfo, takenAtMs: Long?, note: String?, meta: Map<String, Any?>, clientId: String?): Pair<PhotoDto, Boolean> {
    val now = clock.millis()
    fun existing(c: Connection) = clientId?.let { c.select("$select WHERE p.client_id = ?", listOf(it), ::dto).firstOrNull() }
    return try {
      db.tx { c ->
        existing(c)?.let { return@tx it to false }
        val cid = collections.idOf(c, vaultId, collection)
        val token = randomToken()
        val id = c.insert(
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
        lineage.addVersion(c, id, null, null, blob, makeCurrent = false)
          .also { v -> c.exec("UPDATE photos SET current_version_id = ? WHERE id = ?", listOf(v, id)) }
        lineage.advanceChain(c, id, cid, null)
        get(c, token) to true
      }
    } catch (e: Exception) {
      // Lost a race with a concurrent retry carrying the same client_id (unique-key violation,
      // possibly wrapped by the transacter); anything else rethrows.
      db.tx { c -> existing(c) }?.let { it to false } ?: throw e
    }
  }

  /**
   * A photo's verb belongs to the collection it is filed in, so it is set as part of filing:
   * leaving a collection ([moved]) drops `meta.action`/`meta.qty`, and a given [action] must be one
   * of the destination's. "" clears it without moving.
   */
  private fun applyAction(c: Connection, photoId: Long, collectionId: Long, moved: Boolean, action: String?, qty: Int?) {
    if (!moved && action == null) return
    val patch = LinkedHashMap<String, Any?>()
    val cur = c.select("SELECT meta FROM photos WHERE id = ? FOR UPDATE", listOf(photoId)) { it.getString(1) }.firstOrNull() ?: return
    val meta = LinkedHashMap(json.parseObject(cur)).apply { remove("action"); remove("qty") }
    if (!action.isNullOrEmpty()) {
      val allowed = collections.actionsOf(c, collectionId)
      val a = allowed.firstOrNull { it.name == action }
        ?: throw BadRequestException("this collection has no action '$action' (have: ${allowed.joinToString { it.name }.ifEmpty { "none" }})")
      patch["action"] = a.name
      if (a.qty) patch["qty"] = (qty ?: 1).also { if (it < 0) throw BadRequestException("qty must be >= 0") }
    }
    meta.putAll(patch)
    val text = json.write(meta)
    if (text != json.write(json.parseObject(cur))) c.exec("UPDATE photos SET meta = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?", listOf(text, clock.millis(), photoId))
  }

  /** Null arguments are left unchanged; [collection] (a slug in [vaultId]) moves the photo, [action] sets its verb there. */
  fun update(
    token: String, note: String?, meta: Map<String, Any?>?, takenAtMs: Long?, vaultId: Long, collection: String?,
    action: String? = null, qty: Int? = null,
  ): PhotoDto = db.tx { c ->
    get(c, token, includeDeleted = false)
    val sets = mutableListOf("updated_at_ms = ?", "feed_sync_id = NULL")
    val args = mutableListOf<Any?>(clock.millis())
    note?.let { sets += "note = ?"; args += it.takeIf { n -> n.isNotBlank() } }
    meta?.let { sets += "meta = ?"; args += json.write(it); sets += "barcodes = ?"; args += barcodeColumn(it) }
    takenAtMs?.let { sets += "taken_at_ms = ?"; args += it }
    val before = idOf(c, token)
    val dest = collection?.let { collections.idOf(c, vaultId, it) }
    dest?.let { sets += "collection_id = ?"; args += it }
    c.exec("UPDATE photos SET ${sets.joinToString()} WHERE token = ?", args + token)
    val moved = dest != null && dest != before.second
    applyAction(c, before.first, dest ?: before.second, moved, action, qty)
    if (moved) lineage.advanceChain(c, before.first, dest!!, null)
    get(c, token)
  }

  /** Slug of the vault a photo lives in (trashed photos included). */
  fun vaultOf(token: String): String = db.tx { c ->
    c.select("SELECT vt.slug FROM photos p JOIN collections c ON c.id = p.collection_id JOIN vaults vt ON vt.id = c.vault_id WHERE p.token = ?", listOf(token)) { it.getString(1) }.firstOrNull()
  } ?: throw NotFoundException("no photo '$token'")

  fun vaultIdOf(c: Connection, photoId: Long): Long? =
    c.select("SELECT c.vault_id FROM photos p JOIN collections c ON c.id = p.collection_id WHERE p.id = ?", listOf(photoId)) { it.getLong(1) }.firstOrNull()

  /** (photo id, collection id) for a token. */
  fun idOf(c: Connection, token: String): Pair<Long, Long> =
    c.select("SELECT id, collection_id FROM photos WHERE token = ?", listOf(token)) { it.getLong(1) to it.getLong(2) }.firstOrNull()
      ?: throw NotFoundException("no photo '$token'")

  /** Shallow-merges [patch] into the photo's meta (how data-extracting transforms tag a photo). */
  fun patchMeta(c: Connection, photoId: Long, patch: Map<String, Any?>) {
    val cur = c.select("SELECT meta FROM photos WHERE id = ? FOR UPDATE", listOf(photoId)) { it.getString(1) }.firstOrNull() ?: return
    val merged = LinkedHashMap(json.parseObject(cur)).apply { putAll(patch) }
    val text = json.write(merged)
    if (text == cur) return
    c.exec("UPDATE photos SET meta = ?, barcodes = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?", listOf(text, barcodeColumn(merged), clock.millis(), photoId))
  }

  /** New row, same blob. Copies are independent from then on (notes, meta, deletion). */
  fun copy(token: String, vaultId: Long, collection: String): PhotoDto = db.tx { c -> copy(c, token, vaultId, collection) }

  private fun copy(c: Connection, token: String, vaultId: Long, collection: String): PhotoDto {
    get(c, token, includeDeleted = false)
    val cid = collections.idOf(c, vaultId, collection)
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
    // The copy starts its own history at whatever the source currently looks like.
    val newId = idOf(c, newToken).first
    applyAction(c, newId, cid, moved = idOf(c, token).second != cid, action = null, qty = null)
    lineage.currentVersion(c, newId)
    lineage.advanceChain(c, newId, cid, null)
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
  fun batch(op: String, tokens: List<String>, vaultId: Long, collection: String?, action: String? = null, qty: Int? = null): List<PhotoDto> {
    if (tokens.size > 2000) throw BadRequestException("at most 2000 tokens per batch")
    return db.tx { c ->
      tokens.distinct().map { t ->
        when (op) {
          "delete" -> setDeleted(c, t, true)
          "restore" -> setDeleted(c, t, false)
          "copy" -> copy(c, t, vaultId, collection ?: throw BadRequestException("copy needs 'collection'"))
          "move" -> {
            val cid = collections.idOf(c, vaultId, collection ?: throw BadRequestException("move needs 'collection'"))
            val (pid, from) = idOf(c, t)
            // Moving out of the trash is allowed: it's how photos of a deleted collection are rescued.
            c.exec("UPDATE photos SET collection_id = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE token = ?", listOf(cid, clock.millis(), t))
            applyAction(c, pid, cid, moved = from != cid, action, qty)
            if (from != cid) lineage.advanceChain(c, pid, cid, null)
            get(c, t)
          }
          else -> throw BadRequestException("unknown op '$op': want move|copy|delete|restore")
        }
      }
    }
  }

  /** sha256 + content type for serving; soft-deleted photos stay viewable (the trash needs them). */
  fun blobRef(token: String, version: String? = null): Pair<String, String> = db.tx { c ->
    if (version == null) {
      c.select("SELECT sha256, content_type FROM photos WHERE token = ?", listOf(token)) { it.getString(1) to it.getString(2) }.firstOrNull()
        ?: throw NotFoundException("no photo '$token'")
    } else {
      c.select(
        "SELECT v.sha256, v.content_type FROM photo_versions v JOIN photos p ON p.id = v.photo_id WHERE p.token = ? AND v.token = ?", listOf(token, version),
      ) { it.getString(1) to it.getString(2) }.firstOrNull() ?: throw NotFoundException("no version '$version' of photo '$token'")
    }
  }

  /**
   * Hard-deletes trashed photos older than [olderThanMs]; returns the sha256s no row references
   * any more (the caller removes those blobs). Purged rows simply vanish from the feed — consumers
   * already saw the soft-delete tombstone.
   */
  fun purge(olderThanMs: Long): Pair<Int, List<String>> = db.tx { c ->
    val cutoff = clock.millis() - olderThanMs
    val doomed = "SELECT id FROM photos WHERE deleted_at_ms IS NOT NULL AND deleted_at_ms <= ?"
    val shas = (
      c.select("SELECT DISTINCT sha256 FROM photos WHERE deleted_at_ms IS NOT NULL AND deleted_at_ms <= ?", listOf(cutoff)) { it.getString(1) } +
        c.select("SELECT DISTINCT sha256 FROM photo_versions WHERE photo_id IN ($doomed)", listOf(cutoff)) { it.getString(1) }
      ).distinct()
    // Intents and results are an event log and outlive the photo; its images do not.
    c.exec("DELETE FROM photo_versions WHERE photo_id IN (SELECT id FROM ($doomed) d)", listOf(cutoff))
    val n = c.exec("DELETE FROM photos WHERE deleted_at_ms IS NOT NULL AND deleted_at_ms <= ?", listOf(cutoff))
    val orphans = shas.filter { sha ->
      c.select("SELECT 1 FROM photos WHERE sha256 = ? LIMIT 1", listOf(sha)) { 1 }.isEmpty() &&
        c.select("SELECT 1 FROM photo_versions WHERE sha256 = ? LIMIT 1", listOf(sha)) { 1 }.isEmpty()
    }
    n to orphans
  }

  /** [vaultId] null = every vault. [collection] is a slug in [collectionVaultId]. */
  fun feed(after: Long, limit: Int, shard: Int?, shardCount: Int?, vaultId: Long?, collectionVaultId: Long, collection: String?): List<PhotoDto> = db.tx { c ->
    val (cond, shardArgs) = shardCond("p", shard, shardCount)
    val args = mutableListOf<Any?>(after)
    args.addAll(shardArgs)
    var collCond = ""
    if (vaultId != null) {
      collCond += " AND c.vault_id = ?"
      args += vaultId
    }
    if (collection != null) {
      collCond += " AND p.collection_id = ?"
      args += collections.idOf(c, collectionVaultId, collection)
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
  private val select = "SELECT r.*, vt.slug AS vault FROM records r JOIN vaults vt ON vt.id = r.vault_id"

  private fun dto(rs: ResultSet) = RecordDto(
    vault = rs.getString("vault"),
    ns = rs.getString("ns"),
    k = rs.getString("k"),
    v = json.parse(rs.getString("v")),
    created_at = iso(rs.getLong("created_at_ms")),
    updated_at = iso(rs.getLong("updated_at_ms")),
    deleted_at = isoOrNull(rs.longOrNull("deleted_at_ms")),
    feed_sync_id = rs.longOrNull("feed_sync_id"),
  )

  fun namespaces(vaultId: Long): List<NamespaceDto> = db.tx { c ->
    c.select("SELECT ns, COUNT(*) n, MAX(updated_at_ms) u FROM records WHERE vault_id = ? AND deleted_at_ms IS NULL GROUP BY ns ORDER BY ns", listOf(vaultId)) {
      NamespaceDto(it.getString("ns"), it.getLong("n"), isoOrNull(it.longOrNull("u")))
    }
  }

  fun scan(vaultId: Long, ns: String, prefix: String?, limit: Int): List<RecordDto> = db.tx { c ->
    val like = (prefix ?: "").replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    c.select("$select WHERE r.vault_id = ? AND r.ns = ? AND r.k LIKE ? AND r.deleted_at_ms IS NULL ORDER BY r.k LIMIT ?", listOf(vaultId, ns, like, limit.coerceIn(1, 5000)), ::dto)
  }

  fun get(vaultId: Long, ns: String, k: String): RecordDto = db.tx { c -> find(c, vaultId, ns, k) }?.takeIf { it.deleted_at == null } ?: throw NotFoundException("no record $ns/$k")

  private fun find(c: Connection, vaultId: Long, ns: String, k: String) =
    c.select("$select WHERE r.vault_id = ? AND r.ns = ? AND r.k = ?", listOf(vaultId, ns, k), ::dto).firstOrNull()

  /**
   * Upsert. A value-identical put is a no-op (no feed republish), so a transform that re-derives
   * the same output after a replay doesn't ripple downstream.
   */
  fun put(vaultId: Long, ns: String, k: String, v: Any?): RecordDto {
    if (ns.isBlank() || ns.length > 128 || k.isBlank() || k.length > 255) throw BadRequestException("bad ns/k")
    val text = json.write(v)
    val now = clock.millis()
    return db.tx { c ->
      val cur = c.select("SELECT v, deleted_at_ms FROM records WHERE vault_id = ? AND ns = ? AND k = ? FOR UPDATE", listOf(vaultId, ns, k)) { it.getString(1) to it.longOrNull("deleted_at_ms") }.firstOrNull()
      when {
        cur == null -> c.exec(
          "INSERT INTO records (vault_id, ns, k, v, created_at_ms, updated_at_ms, shard) VALUES (?,?,?,?,?,?,?)",
          listOf(vaultId, ns, k, text, now, now, shardOf("$ns:$k", config.snapz.feed_shards)),
        )
        cur.first != text || cur.second != null ->
          c.exec("UPDATE records SET v = ?, updated_at_ms = ?, deleted_at_ms = NULL, feed_sync_id = NULL WHERE vault_id = ? AND ns = ? AND k = ?", listOf(text, now, vaultId, ns, k))
      }
      find(c, vaultId, ns, k)!!
    }
  }

  fun delete(vaultId: Long, ns: String, k: String) {
    val now = clock.millis()
    val n = db.tx { c ->
      c.exec("UPDATE records SET deleted_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE vault_id = ? AND ns = ? AND k = ? AND deleted_at_ms IS NULL", listOf(now, now, vaultId, ns, k))
    }
    if (n == 0) throw NotFoundException("no record $ns/$k")
  }

  fun feed(after: Long, limit: Int, shard: Int?, shardCount: Int?, vaultId: Long?, ns: String?): List<RecordDto> = db.tx { c ->
    val (cond, shardArgs) = shardCond("r", shard, shardCount)
    val args = mutableListOf<Any?>(after)
    args.addAll(shardArgs)
    var nsCond = ""
    if (vaultId != null) {
      nsCond += " AND r.vault_id = ?"
      args += vaultId
    }
    if (ns != null) {
      nsCond += " AND r.ns = ?"
      args += ns
    }
    c.select("$select WHERE r.feed_sync_id > ? AND r.shard >= 0 $cond $nsCond ORDER BY r.feed_sync_id LIMIT ?", args + limit, ::dto)
  }
}

// ---------------------------------------------------------------------------------------------

data class ResolvedToken(val id: Long?, val kind: String, val principal: String, val vault: String? = null, val readOnly: Boolean = false)

/** Bearer credentials: web sessions, per-device tokens (iOS), and named script tokens. Only SHA-256 hashes are stored. */
@Singleton
class TokenStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: SnapzConfig,
) {
  /** [vault] pins the credential to one vault (and off the admin routes); [readOnly] limits it to GETs. */
  fun create(kind: String, name: String, principal: String, ttlMs: Long? = null, vault: String? = null, readOnly: Boolean = false): Pair<Long, String> {
    val plain = "snapz_" + randomSecret()
    val now = clock.millis()
    val id = db.tx { c ->
      val vaultId = vault?.let { v -> c.select("SELECT id FROM vaults WHERE slug = ? AND deleted_at_ms IS NULL", listOf(v)) { it.getLong(1) }.firstOrNull() ?: throw NotFoundException("no vault '$v'") }
      c.insert(
        "INSERT INTO api_tokens (kind, name, token_hash, principal, created_at_ms, expires_at_ms, vault_id, read_only) VALUES (?,?,?,?,?,?,?,?)",
        listOf(kind, name.take(255), sha256Hex(plain.toByteArray()), principal, now, ttlMs?.let { now + it }, vaultId, readOnly),
      )
    }
    return id to plain
  }

  fun resolve(plain: String): ResolvedToken? {
    for (b in config.snapz.bootstrap_tokens) {
      if (java.security.MessageDigest.isEqual(b.token.toByteArray(), plain.toByteArray())) return ResolvedToken(null, "bootstrap", b.principal, b.vault, b.read_only)
    }
    if (!plain.startsWith("snapz_")) return null
    val now = clock.millis()
    return db.tx { c ->
      val row = c.select(
        """
        SELECT t.id, t.kind, t.principal, t.last_used_at_ms, t.read_only, t.vault_id, v.slug AS vault
        FROM api_tokens t LEFT JOIN vaults v ON v.id = t.vault_id AND v.deleted_at_ms IS NULL
        WHERE t.token_hash = ? AND t.revoked_at_ms IS NULL AND (t.expires_at_ms IS NULL OR t.expires_at_ms > ?)
        """.trimIndent(),
        listOf(sha256Hex(plain.toByteArray()), now),
      ) { rs ->
        Triple(
          ResolvedToken(rs.getLong("id"), rs.getString("kind"), rs.getString("principal"), rs.getString("vault"), rs.getBoolean("read_only")),
          rs.longOrNull("last_used_at_ms"),
          // Pinned to a vault that has since been deleted: the credential dies with it.
          rs.longOrNull("vault_id") != null && rs.getString("vault") == null,
        )
      }.firstOrNull()?.takeUnless { it.third } ?: return@tx null
      // Coarse last-used tracking: at most one write per token per minute.
      if (row.second == null || now - row.second!! > 60_000) c.exec("UPDATE api_tokens SET last_used_at_ms = ? WHERE id = ?", listOf(now, row.first.id))
      row.first
    }
  }

  fun list(): List<TokenDto> = db.tx { c ->
    c.select(
      "SELECT t.*, v.slug AS vault FROM api_tokens t LEFT JOIN vaults v ON v.id = t.vault_id WHERE t.revoked_at_ms IS NULL AND (t.expires_at_ms IS NULL OR t.expires_at_ms > ?) ORDER BY t.kind, t.id",
      listOf(clock.millis()),
    ) { rs ->
      TokenDto(
        id = rs.getLong("id"),
        kind = rs.getString("kind"),
        name = rs.getString("name"),
        principal = rs.getString("principal"),
        created_at = iso(rs.getLong("created_at_ms")),
        last_used_at = isoOrNull(rs.longOrNull("last_used_at_ms")),
        expires_at = isoOrNull(rs.longOrNull("expires_at_ms")),
        vault = rs.getString("vault"),
        read_only = rs.getBoolean("read_only"),
      )
    }
  }

  fun revoke(id: Long): Boolean = db.tx { c ->
    c.exec("UPDATE api_tokens SET revoked_at_ms = ? WHERE id = ? AND revoked_at_ms IS NULL", listOf(clock.millis(), id)) > 0
  }
}
