package tcj.snapz

import java.sql.Connection
import javax.inject.Inject
import javax.inject.Singleton
import misk.exceptions.BadRequestException
import misk.exceptions.NotFoundException
import misk.exceptions.UnauthorizedException
import misk.security.authz.Authenticated
import misk.web.Get
import misk.web.PathParam
import misk.web.Post
import misk.web.Put
import misk.web.RequestBody
import misk.web.Response
import misk.web.ResponseBody
import misk.web.ResponseContentType
import misk.web.actions.WebAction
import misk.web.mediatype.MediaTypes
import okhttp3.Headers
import okio.BufferedSink
import okio.ByteString
import okio.source

private val SHA256 = Regex("^[0-9a-f]{64}$")

data class MissingBlob(val token: String, val sha256: String)

/**
 * What became of a batch. `applied` rows were written, `unchanged` already looked like the entry,
 * `stale` lost to a newer local row. Photos whose bytes are not here yet are listed in
 * `missing_blobs` and were NOT applied: push the bytes (PUT /_feeds/blobs/{sha256}) and send the
 * batch again.
 */
data class ReceiveResp(val feed: String, val received: Int, val applied: Int, val unchanged: Int, val stale: Int, val missing_blobs: List<MissingBlob>)

/**
 * The receiving half of a feed: takes entries in exactly the shape /_feeds/fetch/{feed} hands
 * out and makes the local rows look like them. Fetch on one server + Receive on another is
 * replication.
 *
 * - Idempotent: an entry that is already reflected (same content, same `updated_at`) writes
 *   nothing, so nothing is republished; that is what makes at-least-once delivery safe.
 * - Last writer wins on `updated_at`: an entry older than the local row is dropped.
 * - Rows keep the source's identity (photo token, vault/collection slug, record ns/k) and the
 *   source's timestamps.
 * - Feeds are independent: a photo may arrive before its collection, a collection before its
 *   vault. The missing parent is created as a stub (timestamp 0) that the real entry overwrites.
 * - Nothing here starts transform chains: a replica gets a transform's outcome (the photo's
 *   current bytes, meta, records) from the feeds, not by running it again.
 *
 * Deleted vaults and collections arrive under their tombstone slug (`<slug>.deleted.<source id>`);
 * the live local row of that base slug is what gets deleted.
 */
@Singleton
class Replica @Inject constructor(
  private val db: Db,
  private val json: Json,
  private val config: SnapzConfig,
  private val blobs: BlobStore,
  private val lineage: Lineage,
  private val photos: PhotoStore,
) {
  private enum class Outcome { APPLIED, UNCHANGED, STALE, MISSING_BLOB }

  fun receive(feed: String, entries: List<Map<String, Any?>>): ReceiveResp {
    val apply: (Connection, Map<String, Any?>) -> Outcome = when (feed) {
      "vaults" -> ::vault
      "collections" -> ::collection
      "photos" -> ::photo
      "records" -> ::record
      else -> throw BadRequestException("feed '$feed' can't be received (vaults, collections, photos, records can)")
    }
    val missing = mutableListOf<MissingBlob>()
    val counts = IntArray(Outcome.entries.size)
    // One transaction per batch: a batch lands whole or not at all, and is simply sent again.
    db.tx { c ->
      for (e in entries) {
        val o = apply(c, e)
        counts[o.ordinal]++
        if (o == Outcome.MISSING_BLOB) missing += MissingBlob(e.str("token"), e.str("sha256"))
      }
    }
    return ReceiveResp(feed, entries.size, counts[Outcome.APPLIED.ordinal], counts[Outcome.UNCHANGED.ordinal], counts[Outcome.STALE.ordinal], missing)
  }

  // ------------------------------------------------------------------------------ entry fields

  private fun Map<String, Any?>.str(k: String): String = this[k] as? String ?: throw BadRequestException("entry has no '$k'")
  private fun Map<String, Any?>.strOrNull(k: String): String? = this[k] as? String
  private fun Map<String, Any?>.ms(k: String): Long = parseTime(str(k))
  private fun Map<String, Any?>.msOrNull(k: String): Long? = strOrNull(k)?.let(::parseTime)
  private fun Map<String, Any?>.intOrNull(k: String): Int? = (this[k] as? Number)?.toInt()

  private val tombstone = Regex("^(.+)\\.deleted\\.\\d+$")
  private fun baseSlug(slug: String) = tombstone.matchEntire(slug)?.groupValues?.get(1)
  private val shards get() = config.snapz.feed_shards

  // ------------------------------------------------------------------------------ parents

  /**
   * Id of the vault an entry is in. Its token says which vault it is, on any server; entries from
   * a source that predates tokens fall back to the slug (or, for a tombstone slug, the live vault
   * it was). A vault not seen before is created as a stub under the source's token.
   */
  private fun vaultId(c: Connection, e: Map<String, Any?>): Long {
    val slug = e.str("vault")
    val token = e.strOrNull("vault_token")
    fun find(col: String, v: String) = c.select("SELECT id FROM vaults WHERE $col = ?", listOf(v)) { it.getLong(1) }.firstOrNull()
    token?.let { find("token", it) }?.let { return it }
    find("slug", slug)?.let { return it }
    baseSlug(slug)?.let { find("slug", it) }?.let { return it }
    return c.insert(
      "INSERT INTO vaults (slug, name, created_at_ms, updated_at_ms, deleted_at_ms, shard, token) VALUES (?,?,0,0,?,?,?)",
      listOf(slug, baseSlug(slug) ?: slug, if (baseSlug(slug) != null) 0L else null, shardOf("vault:$slug", shards), token ?: newVaultToken()),
    )
  }

  private fun collectionId(c: Connection, vaultId: Long, slug: String): Long {
    fun find(s: String) = c.select("SELECT id FROM collections WHERE vault_id = ? AND slug = ?", listOf(vaultId, s)) { it.getLong(1) }.firstOrNull()
    find(slug)?.let { return it }
    baseSlug(slug)?.let(::find)?.let { return it }
    return c.insert(
      "INSERT INTO collections (vault_id, slug, name, created_at_ms, updated_at_ms, deleted_at_ms, shard) VALUES (?,?,?,0,0,?,?)",
      listOf(vaultId, slug, baseSlug(slug) ?: slug, if (baseSlug(slug) != null) 0L else null, shardOf("collection:$slug", shards)),
    )
  }

  // ------------------------------------------------------------------------------ vaults

  private fun vault(c: Connection, e: Map<String, Any?>): Outcome {
    val slug = e.str("slug")
    val updated = e.ms("updated_at")
    val deleted = e.msOrNull("deleted_at")
    if (deleted != null) {
      val base = baseSlug(slug) ?: slug
      fun live(where: String, v: String) = c.select("SELECT id, updated_at_ms FROM vaults WHERE $where = ? AND deleted_at_ms IS NULL FOR UPDATE", listOf(v)) { it.getLong(1) to it.getLong(2) }.firstOrNull()
      val live = e.strOrNull("token")?.let { live("token", it) } ?: live("slug", base) ?: return Outcome.UNCHANGED
      // The default vault is what requests fall back to; it is never deleted, here or at the source.
      if (live.second > updated || base == config.snapz.default_vault) return Outcome.STALE
      c.exec("UPDATE vaults SET slug = ?, deleted_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?", listOf("$base.deleted.${live.first}", deleted, updated, live.first))
      return Outcome.APPLIED
    }
    val want = listOf(e.str("name"), e.strOrNull("description"), e.strOrNull("icon"))
    val token = e.strOrNull("token")
    fun row(where: String, v: String) = c.select("SELECT id, name, description, icon, updated_at_ms FROM vaults WHERE $where = ? FOR UPDATE", listOf(v)) {
      Triple(it.getLong(1), listOf(it.getString(2), it.getString(3), it.getString(4)), it.getLong(5))
    }.firstOrNull()
    val byToken = token?.let { row("token", it) }
    val cur = byToken ?: row("slug", slug)
    // Same slug, different token: this server made its own vault of that name before it started
    // following the source. A replica takes the source's identity for it.
    if (cur != null && byToken == null && token != null) c.exec("UPDATE vaults SET token = ?, feed_sync_id = NULL WHERE id = ?", listOf(token, cur.first))
    if (cur == null) {
      c.insert(
        "INSERT INTO vaults (slug, name, description, icon, created_at_ms, updated_at_ms, shard, token) VALUES (?,?,?,?,?,?,?,?)",
        listOf(slug) + want + listOf(e.ms("created_at"), updated, shardOf("vault:$slug", shards), token ?: newVaultToken()),
      )
      return Outcome.APPLIED
    }
    if (cur.second == want && cur.third == updated) return Outcome.UNCHANGED
    if (cur.third > updated) return Outcome.STALE
    c.exec("UPDATE vaults SET name = ?, description = ?, icon = ?, created_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?", want + listOf(e.ms("created_at"), updated, cur.first))
    return Outcome.APPLIED
  }

  // ------------------------------------------------------------------------------ collections

  private fun actionsText(v: Any?): String? {
    val list = (v as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.mapNotNull { a ->
      val name = a["name"]?.toString() ?: return@mapNotNull null
      listOfNotNull("name" to name, (a["label"] as? String)?.takeIf { it.isNotBlank() }?.let { "label" to it }, "qty" to (a["qty"] == true)).toMap()
    }
    return if (list.isEmpty()) null else json.write(list)
  }

  private fun collection(c: Connection, e: Map<String, Any?>): Outcome {
    val slug = e.str("slug")
    val updated = e.ms("updated_at")
    val deleted = e.msOrNull("deleted_at")
    val vault = vaultId(c, e)
    if (deleted != null) {
      val base = baseSlug(slug) ?: slug
      val live = c.select("SELECT id, updated_at_ms FROM collections WHERE vault_id = ? AND slug = ? AND deleted_at_ms IS NULL FOR UPDATE", listOf(vault, base)) { it.getLong(1) to it.getLong(2) }.firstOrNull()
        ?: return Outcome.UNCHANGED
      if (live.second > updated) return Outcome.STALE
      c.exec("UPDATE collections SET slug = ?, deleted_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?", listOf("$base.deleted.${live.first}", deleted, updated, live.first))
      return Outcome.APPLIED
    }
    val want = listOf(e.str("name"), e.strOrNull("description"), e.strOrNull("icon"), actionsText(e["actions"]))
    val cur = c.select("SELECT id, name, description, icon, actions, updated_at_ms FROM collections WHERE vault_id = ? AND slug = ? FOR UPDATE", listOf(vault, slug)) {
      Triple(it.getLong(1), listOf(it.getString(2), it.getString(3), it.getString(4), actionsText(it.getString(5)?.let(json::parse))), it.getLong(6))
    }.firstOrNull()
    if (cur == null) {
      c.insert(
        "INSERT INTO collections (vault_id, slug, name, description, icon, actions, created_at_ms, updated_at_ms, shard) VALUES (?,?,?,?,?,?,?,?,?)",
        listOf(vault, slug) + want + listOf(e.ms("created_at"), updated, shardOf("collection:$slug", shards)),
      )
      return Outcome.APPLIED
    }
    if (cur.second == want && cur.third == updated) return Outcome.UNCHANGED
    if (cur.third > updated) return Outcome.STALE
    c.exec(
      "UPDATE collections SET name = ?, description = ?, icon = ?, actions = ?, created_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?",
      want + listOf(e.ms("created_at"), updated, cur.first),
    )
    return Outcome.APPLIED
  }

  // ------------------------------------------------------------------------------ photos

  private fun photo(c: Connection, e: Map<String, Any?>): Outcome {
    val token = e.str("token")
    val sha = e.str("sha256")
    if (!SHA256.matches(sha)) throw BadRequestException("'$sha' is not a sha256")
    if (!blobs.original(sha).exists()) return Outcome.MISSING_BLOB
    val updated = e.ms("updated_at")
    val meta = (e["meta"] as? Map<*, *>).orEmpty().mapKeys { it.key.toString() }
    val blob = BlobInfo(sha, e.str("content_type"), (e["bytes"] as Number).toLong(), e.intOrNull("width"), e.intOrNull("height"))
    val collection = collectionId(c, vaultId(c, e), e.str("collection"))
    val want = listOf(collection, sha, e.ms("taken_at"), e.strOrNull("note"), e.msOrNull("deleted_at"))

    val cur = c.select("SELECT id, collection_id, sha256, taken_at_ms, note, deleted_at_ms, meta, updated_at_ms, current_version_id FROM photos WHERE token = ? FOR UPDATE", listOf(token)) { rs ->
      object {
        val id = rs.getLong(1)
        val fields = listOf(rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.longOrNull("deleted_at_ms"))
        val meta = json.parseObject(rs.getString(7))
        val updated = rs.getLong(8)
        val version = rs.longOrNull("current_version_id")
      }
    }.firstOrNull()

    if (cur == null) {
      // The same shot can have reached both servers on its own (a phone that talks to both):
      // client_id is unique here, so the replicated row goes without it.
      val clientId = e.strOrNull("client_id")?.takeIf { id -> c.select("SELECT 1 FROM photos WHERE client_id = ?", listOf(id)) { 1 }.isEmpty() }
      val id = c.insert(
        """
        INSERT INTO photos (token, collection_id, sha256, content_type, bytes, width, height, taken_at_ms, note, meta, barcodes,
                            client_id, created_at_ms, updated_at_ms, deleted_at_ms, shard)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent(),
        listOf(
          token, collection, sha, blob.contentType, blob.bytes, blob.width, blob.height, e.ms("taken_at"), e.strOrNull("note"), json.write(meta),
          photos.barcodeColumn(meta), clientId, e.ms("created_at"), updated, e.msOrNull("deleted_at"), shardOf(token, shards),
        ),
      )
      val v = lineage.addVersion(c, id, null, null, blob, makeCurrent = false)
      c.exec("UPDATE photos SET current_version_id = ? WHERE id = ?", listOf(v, id))
      return Outcome.APPLIED
    }
    if (cur.fields == want && cur.meta == meta && cur.updated == updated) return Outcome.UNCHANGED
    if (cur.updated > updated) return Outcome.STALE
    // New bytes at the source (a transform rewrote the photo): a new version here, the old one kept.
    val version = if (cur.fields[1] != sha) lineage.addVersion(c, cur.id, cur.version, "replica", blob, makeCurrent = false) else cur.version
    c.exec(
      """
      UPDATE photos SET collection_id = ?, sha256 = ?, content_type = ?, bytes = ?, width = ?, height = ?, taken_at_ms = ?, note = ?, meta = ?,
                        barcodes = ?, updated_at_ms = ?, deleted_at_ms = ?, current_version_id = ?, feed_sync_id = NULL
      WHERE id = ?
      """.trimIndent(),
      listOf(
        collection, sha, blob.contentType, blob.bytes, blob.width, blob.height, e.ms("taken_at"), e.strOrNull("note"), json.write(meta),
        photos.barcodeColumn(meta), updated, e.msOrNull("deleted_at"), version, cur.id,
      ),
    )
    return Outcome.APPLIED
  }

  // ------------------------------------------------------------------------------ records

  private fun record(c: Connection, e: Map<String, Any?>): Outcome {
    val ns = e.str("ns")
    val k = e.str("k")
    val vault = vaultId(c, e)
    val updated = e.ms("updated_at")
    val text = json.write(e["v"])
    val deleted = e.msOrNull("deleted_at")
    val cur = c.select("SELECT id, v, deleted_at_ms, updated_at_ms FROM records WHERE vault_id = ? AND ns = ? AND k = ? FOR UPDATE", listOf(vault, ns, k)) {
      object {
        val id = it.getLong(1)
        val v = it.getString(2)
        val deleted = it.longOrNull("deleted_at_ms")
        val updated = it.getLong(4)
      }
    }.firstOrNull()
    if (cur == null) {
      c.insert(
        "INSERT INTO records (vault_id, ns, k, v, created_at_ms, updated_at_ms, deleted_at_ms, shard) VALUES (?,?,?,?,?,?,?,?)",
        listOf(vault, ns, k, text, e.ms("created_at"), updated, deleted, shardOf("$ns:$k", shards)),
      )
      return Outcome.APPLIED
    }
    if (json.parse(cur.v) == json.parse(text) && cur.deleted == deleted && cur.updated == updated) return Outcome.UNCHANGED
    if (cur.updated > updated) return Outcome.STALE
    c.exec("UPDATE records SET v = ?, updated_at_ms = ?, deleted_at_ms = ?, feed_sync_id = NULL WHERE id = ?", listOf(text, updated, deleted, cur.id))
    return Outcome.APPLIED
  }
}

/**
 * Sink endpoints. Writing is admin only: it touches any vault, with any timestamp, under any token.
 *
 *   POST /_feeds/receive/{feed}    body: {"entries": [...]} as fetched from another server
 *   PUT  /_feeds/blobs/{sha256}    body: the bytes (checked against the hash)
 *   GET  /_feeds/blobs/{sha256}    the bytes, by content hash (what a replicator copies from)
 */
class ReplicaActions @Inject constructor(
  private val replica: Replica,
  private val blobs: BlobStore,
  private val json: Json,
  private val config: SnapzConfig,
  private val ctx: VaultContext,
) : WebAction {
  @Post("/_feeds/receive/{feed}")
  @Authenticated(capabilities = [ADMIN])
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun receive(@PathParam feed: String, @RequestBody body: ByteString): ReceiveResp {
    // Parsed with the same parser that reads stored JSON, so numbers compare equal to what is stored.
    val entries = (json.parseObject(body.utf8())["entries"] as? List<*> ?: throw BadRequestException("body must be {\"entries\": [...]}"))
      .map { e -> (e as? Map<*, *> ?: throw BadRequestException("entries must be objects")).mapKeys { it.key.toString() } }
    if (entries.size > 1000) throw BadRequestException("at most 1000 entries per call")
    return replica.receive(feed, entries)
  }

  @Put("/_feeds/blobs/{sha256}")
  @Authenticated(capabilities = [ADMIN])
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun put(@PathParam sha256: String, @RequestBody body: ByteString): Ok {
    if (body.size > config.snapz.max_upload_bytes) throw BadRequestException("max upload is ${config.snapz.max_upload_bytes} bytes")
    val bytes = body.toByteArray()
    val actual = sha256Hex(bytes)
    if (actual != sha256) throw BadRequestException("body hashes to $actual, not $sha256")
    blobs.put(bytes)
    return Ok()
  }

  /** Readable with a read-only token, so a replicator need not hold write access to its source; not with a vault-pinned one (blobs are shared across vaults). */
  @Get("/_feeds/blobs/{sha256}")
  @Authenticated(capabilities = [USER])
  fun get(@PathParam sha256: String): Response<ResponseBody> {
    ctx.requireOperator()
    if (ctx.pinned() != null) throw UnauthorizedException("a vault-pinned token can't read blobs by hash")
    if (!SHA256.matches(sha256)) throw BadRequestException("not a sha256")
    val file = blobs.original(sha256)
    if (!file.exists()) throw NotFoundException("no blob $sha256")
    val body = object : ResponseBody {
      override fun writeTo(sink: BufferedSink) {
        file.source().use { sink.writeAll(it) }
      }
    }
    return Response(body, Headers.Builder().add("Content-Type", "application/octet-stream").add("Content-Length", file.length().toString()).build(), 200)
  }

}
