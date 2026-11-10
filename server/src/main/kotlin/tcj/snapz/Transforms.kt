package tcj.snapz

import com.google.common.util.concurrent.AbstractIdleService
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import java.awt.image.BufferedImage
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import misk.exceptions.BadRequestException
import misk.exceptions.NotFoundException
import misk.logging.getLogger

// ---------------------------------------------------------------------------------------------
// The transform model, as events:
//
//   transform_intents  "run transform T on photo P as it stands at version V"   (append-only)
//   transform_results  "T ran for intent I: status, extracted data, maybe a new version"
//
// Nothing is updated in place, so a photo's whole processing history is the two streams joined,
// and both are feeds: an external worker is a consumer of `intents` that posts results.
//
// Two kinds of transform:
//   extract  reads pixels/metadata, returns data (optionally a meta_patch merged into photo.meta)
//   rewrite  returns a new image, which becomes a child version of the one it read
//
// A collection carries an ordered chain of transforms. Entering the collection queues link 0;
// each result queues the next link against the photo as the previous one left it. A link ends
// the chain by answering `halt` (or failing) — the interceptor that doesn't call proceed().
// ---------------------------------------------------------------------------------------------

data class TransformDto(val name: String, val kind: String, val description: String?, val builtin: Boolean, val updated_at: String)

data class ChainLinkDto(val position: Int, val transform: String, val params: Map<String, Any?>?)

data class VersionDto(
  val token: String,
  val parent: String?,
  val transform: String?,
  val sha256: String,
  val content_type: String,
  val bytes: Long,
  val width: Int?,
  val height: Int?,
  val current: Boolean,
  val url: String,
  val thumb_url: String,
  val created_at: String,
)

data class ResultDto(
  val intent: String,
  val photo: String?,
  val transform: String,
  val status: String,
  val data: Any?,
  val output_version: String?,
  val error: String?,
  val worker: String?,
  val created_at: String,
  val feed_sync_id: Long?,
)

data class IntentDto(
  val token: String,
  /** Photo token; null once the photo has been purged. */
  val photo: String?,
  val collection: String?,
  /** The version this intent reads. */
  val version: String?,
  val input_url: String?,
  val transform: String,
  val params: Map<String, Any?>?,
  val origin: String,
  val chain_position: Int?,
  /** pending until a result exists, then the latest result's status. */
  val state: String,
  val results: List<ResultDto>,
  val created_at: String,
  val feed_sync_id: Long?,
)

data class PhotoHistory(val photo: String, val current_version: String?, val versions: List<VersionDto>, val intents: List<IntentDto>)

val RESULT_STATUSES = setOf("ok", "skipped", "halt", "error")
private val TRANSFORM_NAME = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")

@Singleton
class TransformStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: SnapzConfig,
  private val json: Json,
  private val lineage: Lineage,
  private val photos: PhotoStore,
  private val collections: CollectionStore,
) {
  // ---- library

  fun list(): List<TransformDto> = db.tx { c ->
    c.select("SELECT * FROM transforms ORDER BY builtin DESC, name") {
      TransformDto(it.getString("name"), it.getString("kind"), it.getString("description"), it.getBoolean("builtin"), iso(it.getLong("updated_at_ms")))
    }
  }

  /** Workers call this on startup, so the library lists whatever is actually out there. */
  fun register(name: String, kind: String, description: String?, builtin: Boolean = false) {
    if (!TRANSFORM_NAME.matches(name)) throw BadRequestException("bad transform name '$name': want ${TRANSFORM_NAME.pattern}")
    if (kind !in setOf("extract", "rewrite")) throw BadRequestException("kind must be extract or rewrite")
    val now = clock.millis()
    db.tx { c ->
      val existing = c.select("SELECT builtin FROM transforms WHERE name = ?", listOf(name)) { it.getBoolean(1) }.firstOrNull()
      if (existing == true && !builtin) throw BadRequestException("'$name' is a built-in transform")
      c.exec(
        """
        INSERT INTO transforms (name, kind, description, builtin, created_at_ms, updated_at_ms) VALUES (?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE kind = VALUES(kind), description = VALUES(description), builtin = VALUES(builtin), updated_at_ms = VALUES(updated_at_ms)
        """.trimIndent(),
        listOf(name, kind, description, builtin, now, now),
      )
    }
  }

  private fun requireTransform(c: Connection, name: String) {
    if (c.select("SELECT 1 FROM transforms WHERE name = ?", listOf(name)) { 1 }.isEmpty()) throw NotFoundException("no transform '$name' (see GET /api/transforms)")
  }

  // ---- chains

  fun chain(collection: String): List<ChainLinkDto> = db.tx { c -> chain(c, collections.idOf(c, collection)) }

  private fun chain(c: Connection, collectionId: Long) =
    c.select("SELECT position, transform, params FROM collection_transforms WHERE collection_id = ? ORDER BY position", listOf(collectionId)) {
      ChainLinkDto(it.getInt(1), it.getString(2), it.getString(3)?.let(json::parseObject))
    }

  /** Replaces the whole chain. Photos already in the collection are not re-run; new arrivals are. */
  fun setChain(collection: String, links: List<Pair<String, Map<String, Any?>?>>): List<ChainLinkDto> = db.tx { c ->
    val cid = collections.idOf(c, collection)
    links.forEach { requireTransform(c, it.first) }
    c.exec("DELETE FROM collection_transforms WHERE collection_id = ?", listOf(cid))
    links.forEachIndexed { i, (name, params) ->
      c.exec("INSERT INTO collection_transforms (collection_id, position, transform, params) VALUES (?,?,?,?)", listOf(cid, i, name, params?.let(json::write)))
    }
    chain(c, cid)
  }

  // ---- intents + results

  private val intentSelect = """
    SELECT i.*, p.token AS photo_token, p.sha256 AS photo_sha, c.slug AS collection_slug, v.token AS version_token
    FROM transform_intents i
    LEFT JOIN photos p ON p.id = i.photo_id
    LEFT JOIN collections c ON c.id = p.collection_id
    LEFT JOIN photo_versions v ON v.id = i.version_id
  """.trimIndent()

  private fun results(c: Connection, where: String, args: List<Any?>, tail: String = "ORDER BY r.id"): List<Pair<Long, ResultDto>> = c.select(
    """
    SELECT r.*, i.token AS intent_token, p.token AS photo_token, v.token AS out_token
    FROM transform_results r JOIN transform_intents i ON i.id = r.intent_id
    LEFT JOIN photos p ON p.id = r.photo_id LEFT JOIN photo_versions v ON v.id = r.output_version_id
    WHERE $where $tail
    """.trimIndent(),
    args,
  ) { rs ->
    rs.getLong("intent_id") to ResultDto(
      intent = rs.getString("intent_token"),
      photo = rs.getString("photo_token"),
      transform = rs.getString("transform"),
      status = rs.getString("status"),
      data = rs.getString("data")?.let(json::parse),
      output_version = rs.getString("out_token"),
      error = rs.getString("error"),
      worker = rs.getString("worker"),
      created_at = iso(rs.getLong("created_at_ms")),
      feed_sync_id = rs.longOrNull("feed_sync_id"),
    )
  }

  private fun intents(c: Connection, where: String, args: List<Any?>): List<IntentDto> {
    val rows = c.select("$intentSelect WHERE $where", args) { rs ->
      val photo = rs.getString("photo_token")
      val version = rs.getString("version_token")
      rs.getLong("id") to IntentDto(
        token = rs.getString("token"),
        photo = photo,
        collection = rs.getString("collection_slug"),
        version = version,
        input_url = if (photo != null && version != null) "/blobs/$photo?version=$version" else null,
        transform = rs.getString("transform"),
        params = rs.getString("params")?.let(json::parseObject),
        origin = rs.getString("origin"),
        chain_position = rs.intOrNull("chain_position"),
        state = "pending",
        results = listOf(),
        created_at = iso(rs.getLong("created_at_ms")),
        feed_sync_id = rs.longOrNull("feed_sync_id"),
      )
    }
    if (rows.isEmpty()) return listOf()
    val byIntent = results(c, "r.intent_id IN (${rows.joinToString(",") { it.first.toString() }})", listOf()).groupBy({ it.first }, { it.second })
    return rows.map { (id, dto) ->
      val rs = byIntent[id].orEmpty()
      dto.copy(results = rs, state = rs.lastOrNull()?.status ?: "pending")
    }
  }

  fun intent(token: String): IntentDto = db.tx { c -> intents(c, "i.token = ?", listOf(token)).firstOrNull() } ?: throw NotFoundException("no intent '$token'")

  /** Ad-hoc request, outside any chain. */
  fun request(photoToken: String, transform: String, params: Map<String, Any?>?, by: String?): IntentDto = db.tx { c ->
    requireTransform(c, transform)
    val (pid, _) = photos.idOf(c, photoToken)
    if (c.select("SELECT 1 FROM photos WHERE id = ? AND deleted_at_ms IS NULL", listOf(pid)) { 1 }.isEmpty()) throw NotFoundException("photo '$photoToken' is in the trash")
    val token = lineage.addIntent(c, pid, transform, params?.let(json::write), "manual", null, null, by)
    intents(c, "i.token = ?", listOf(token)).first()
  }

  /** Intents with no result yet, oldest first. [builtinOnly] is what the in-process worker asks for. */
  fun pending(transform: String?, builtinOnly: Boolean, limit: Int): List<IntentDto> = db.tx { c ->
    val conds = mutableListOf("NOT EXISTS (SELECT 1 FROM transform_results r WHERE r.intent_id = i.id)")
    val args = mutableListOf<Any?>()
    if (transform != null) { conds += "i.transform = ?"; args += transform }
    if (builtinOnly) conds += "i.transform IN (SELECT name FROM transforms WHERE builtin = 1)"
    intents(c, conds.joinToString(" AND ") + " ORDER BY i.id LIMIT ${limit.coerceIn(1, 500)}", args)
  }

  /**
   * Record what a transform did. Everything it implies happens in the same transaction: the new
   * version (for rewrites), the meta patch (for extractors), and queueing the chain's next link.
   * Results are append-only; a retry adds another row and the latest one is the intent's state.
   */
  fun addResult(
    intentToken: String,
    status: String,
    data: Any?,
    metaPatch: Map<String, Any?>?,
    error: String?,
    worker: String?,
    output: BlobInfo? = null,
    makeCurrent: Boolean = true,
  ): IntentDto {
    if (status !in RESULT_STATUSES) throw BadRequestException("status must be one of $RESULT_STATUSES")
    return db.tx { c ->
      val i = c.select("SELECT id, photo_id, version_id, transform, origin, collection_id, chain_position FROM transform_intents WHERE token = ? FOR UPDATE", listOf(intentToken)) { rs ->
        object {
          val id = rs.getLong("id"); val photoId = rs.getLong("photo_id"); val versionId = rs.getLong("version_id"); val transform = rs.getString("transform")
          val origin = rs.getString("origin"); val collectionId = rs.longOrNull("collection_id"); val position = rs.intOrNull("chain_position")
        }
      }.firstOrNull() ?: throw NotFoundException("no intent '$intentToken'")
      val photoCollection = c.select("SELECT collection_id FROM photos WHERE id = ? AND deleted_at_ms IS NULL", listOf(i.photoId)) { it.getLong(1) }.firstOrNull()
      val first = c.select("SELECT 1 FROM transform_results WHERE intent_id = ? AND status IN ('ok','skipped') LIMIT 1", listOf(i.id)) { 1 }.isEmpty()

      var outputId: Long? = null
      if (photoCollection != null) {
        if (output != null) outputId = lineage.addVersion(c, i.photoId, i.versionId, i.transform, output, makeCurrent)
        if (metaPatch != null && status == "ok") photos.patchMeta(c, i.photoId, metaPatch)
      }
      val token = intentToken
      c.insert(
        "INSERT INTO transform_results (intent_id, photo_id, transform, status, data, output_version_id, error, worker, created_at_ms, shard) VALUES (?,?,?,?,?,?,?,?,?,?)",
        listOf(i.id, i.photoId, i.transform, status, data?.let(json::write), outputId, error?.take(4000), worker?.take(128), clock.millis(), shardOf(token, config.snapz.feed_shards)),
      )
      // Advance once, and only while the photo is still where this chain started: a move begins
      // the destination's chain from the top, and the old one must not leak into it.
      if (first && status in setOf("ok", "skipped") && i.origin == "chain" && i.collectionId != null && photoCollection == i.collectionId) {
        lineage.advanceChain(c, i.photoId, i.collectionId, i.position)
      }
      intents(c, "i.id = ?", listOf(i.id)).first()
    }
  }

  fun history(photoToken: String): PhotoHistory = db.tx { c ->
    val (pid, _) = photos.idOf(c, photoToken)
    val current = lineage.currentVersion(c, pid)
    val tokens = c.select("SELECT id, token FROM photo_versions WHERE photo_id = ?", listOf(pid)) { it.getLong(1) to it.getString(2) }.toMap()
    val versions = c.select("SELECT * FROM photo_versions WHERE photo_id = ? ORDER BY id", listOf(pid)) { rs ->
      val t = rs.getString("token")
      VersionDto(
        token = t,
        parent = rs.longOrNull("parent_version_id")?.let(tokens::get),
        transform = rs.getString("transform"),
        sha256 = rs.getString("sha256"),
        content_type = rs.getString("content_type"),
        bytes = rs.getLong("bytes"),
        width = rs.intOrNull("width"),
        height = rs.intOrNull("height"),
        current = rs.getLong("id") == current,
        url = "/blobs/$photoToken?version=$t",
        thumb_url = "/blobs/$photoToken?version=$t&size=thumb",
        created_at = iso(rs.getLong("created_at_ms")),
      )
    }
    PhotoHistory(photoToken, tokens[current], versions, intents(c, "i.photo_id = ? ORDER BY i.id", listOf(pid)))
  }

  /** Step back (or sideways) in the version tree. Nothing is deleted; "current" just moves. */
  fun setCurrent(photoToken: String, versionToken: String): PhotoDto {
    db.tx { c ->
      val (pid, _) = photos.idOf(c, photoToken)
      val vid = c.select("SELECT id FROM photo_versions WHERE photo_id = ? AND token = ?", listOf(pid, versionToken)) { it.getLong(1) }.firstOrNull()
        ?: throw NotFoundException("no version '$versionToken' of photo '$photoToken'")
      lineage.setCurrent(c, pid, vid)
    }
    return photos.get(photoToken)
  }

  fun intentFeed(after: Long, limit: Int, shard: Int?, shardCount: Int?, transform: String?): List<IntentDto> = db.tx { c ->
    val (cond, shardArgs) = shardCond("i", shard, shardCount)
    val args = mutableListOf<Any?>(after)
    args.addAll(shardArgs)
    var t = ""
    if (transform != null) { t = "AND i.transform = ?"; args += transform }
    intents(c, "i.feed_sync_id > ? AND i.shard >= 0 $cond $t ORDER BY i.feed_sync_id LIMIT ?", args + limit)
  }

  fun resultFeed(after: Long, limit: Int, shard: Int?, shardCount: Int?, transform: String?): List<ResultDto> = db.tx { c ->
    val (cond, shardArgs) = shardCond("r", shard, shardCount)
    val args = mutableListOf<Any?>(after)
    args.addAll(shardArgs)
    var t = ""
    if (transform != null) { t = "AND r.transform = ?"; args += transform }
    results(c, "r.feed_sync_id > ? AND r.shard >= 0 $cond $t", args + limit, "ORDER BY r.feed_sync_id LIMIT ?").map { it.second }
  }
}

// ---------------------------------------------------------------------------------------------
// Where does this photo belong?
// ---------------------------------------------------------------------------------------------

data class Suggestion(val collection: String, val p: Double)
data class InboxItem(val photo: PhotoDto, val suggestions: List<Suggestion>, val features: Map<String, String>)

/**
 * P(collection | photo) by naive Bayes over how photos have actually been filed so far. Each
 * feature is categorical and cheap:
 *
 *   barcode   yes / no
 *   hour      UTC hour in 3h buckets (a stand-in for "breakfast vs. office hours")
 *   weekend   yes / no
 *   shape     portrait / landscape / square
 *   source    meta.source (ios / web / cli / …)
 *   prev      the collection of the nearest earlier photo within 10 minutes, or "none" — bursts
 *             go to the same place, so filing one photo of a burst sharpens the rest
 *
 * Counts get add-one smoothing; the prior is the collection's share of all filed photos. The
 * inbox itself and trashed photos are never training data. With nothing filed yet, every
 * collection is equally likely.
 */
@Singleton
class Suggester @Inject constructor(
  private val db: Db,
  private val config: SnapzConfig,
  private val json: Json,
) {
  private class Row(val id: Long, val collection: String, val takenMs: Long, val features: MutableMap<String, String>)

  private fun shape(w: Int?, h: Int?) = when {
    w == null || h == null -> "unknown"
    w > h * 1.1 -> "landscape"
    h > w * 1.1 -> "portrait"
    else -> "square"
  }

  private fun load(c: Connection): List<Row> = c.select(
    """
    SELECT p.id, c.slug, p.taken_at_ms, p.barcodes, p.width, p.height, p.meta
    FROM photos p JOIN collections c ON c.id = p.collection_id
    WHERE p.deleted_at_ms IS NULL AND c.deleted_at_ms IS NULL ORDER BY p.taken_at_ms, p.id
    """.trimIndent(),
  ) { rs ->
    val t = rs.getLong("taken_at_ms")
    val at = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC)
    Row(
      rs.getLong("id"), rs.getString("slug"), t,
      mutableMapOf(
        "barcode" to if (rs.getString("barcodes") != null) "yes" else "no",
        "hour" to "h%02d".format(at.hour / 3 * 3),
        "weekend" to if (at.dayOfWeek.value >= 6) "yes" else "no",
        "shape" to shape(rs.intOrNull("width"), rs.intOrNull("height")),
        "source" to ((try { json.parseObject(rs.getString("meta"))["source"] } catch (e: Exception) { null }) as? String ?: "unknown"),
      ),
    )
  }

  /** Ranked destinations for each requested photo id (all inbox photos when null). */
  fun suggest(c: Connection, photoIds: Set<Long>?): Map<Long, Pair<List<Suggestion>, Map<String, String>>> {
    val inbox = config.snapz.inbox_slug
    val rows = load(c)
    // prev: nearest earlier *filed* photo within the window.
    var lastFiled: Row? = null
    for (r in rows) {
      r.features["prev"] = lastFiled?.takeIf { r.takenMs - it.takenMs <= BURST_MS }?.collection ?: "none"
      if (r.collection != inbox) lastFiled = r
    }
    val train = rows.filter { it.collection != inbox }
    val classes = c.select("SELECT slug FROM collections WHERE deleted_at_ms IS NULL AND slug <> ? ORDER BY slug", listOf(inbox)) { it.getString(1) }
    if (classes.isEmpty()) return mapOf()
    val perClass = train.groupingBy { it.collection }.eachCount()
    val counts = HashMap<Triple<String, String, String>, Int>() // (class, feature, value) -> n
    val values = HashMap<String, MutableSet<String>>()          // feature -> distinct values seen
    for (r in train) for ((f, v) in r.features) {
      counts.merge(Triple(r.collection, f, v), 1, Int::plus)
      values.getOrPut(f) { mutableSetOf() }.add(v)
    }
    val targets = rows.filter { if (photoIds != null) it.id in photoIds else it.collection == inbox }
    return targets.associate { r ->
      val logp = classes.associateWith { k ->
        val nk = perClass[k] ?: 0
        var lp = ln((nk + 1.0) / (train.size + classes.size))
        for ((f, v) in r.features) {
          val distinct = (values[f]?.size ?: 0) + if (values[f]?.contains(v) == true) 0 else 1
          lp += ln(((counts[Triple(k, f, v)] ?: 0) + 1.0) / (nk + distinct))
        }
        lp
      }
      val max = logp.values.max()
      val z = logp.values.sumOf { Math.exp(it - max) }
      r.id to (logp.map { (k, lp) -> Suggestion(k, Math.round(Math.exp(lp - max) / z * 1000) / 1000.0) }.sortedWith(compareByDescending<Suggestion> { it.p }.thenBy { it.collection }) to r.features.toMap())
    }
  }

  companion object {
    const val BURST_MS = 10 * 60 * 1000L
  }
}

// ---------------------------------------------------------------------------------------------
// Built-in transforms, run in-process.
// ---------------------------------------------------------------------------------------------

class TransformOutcome(
  val status: String = "ok",
  val data: Any? = null,
  val metaPatch: Map<String, Any?>? = null,
  val image: ByteArray? = null,
  val makeCurrent: Boolean = true,
)

class Builtin(val name: String, val kind: String, val description: String, val run: (IntentDto, PhotoDto) -> TransformOutcome)

@Singleton
class Builtins @Inject constructor(
  private val db: Db,
  private val blobs: BlobStore,
  private val photos: PhotoStore,
  private val suggester: Suggester,
) {
  val all: List<Builtin> = listOf(
    Builtin("suggest-collection", "extract", "Ranks collections for a photo by naive Bayes over how past photos were filed (barcode, hour, weekend, shape, source, burst). Feeds inbox triage.") { _, photo ->
      val (suggestions, features) = db.tx { c -> suggester.suggest(c, setOf(photos.idOf(c, photo.token).first)) }.values.firstOrNull() ?: (listOf<Suggestion>() to mapOf())
      TransformOutcome(data = mapOf("suggestions" to suggestions.map { mapOf("collection" to it.collection, "p" to it.p) }, "features" to features))
    },
    Builtin("barcodes", "extract", "Decodes barcodes server-side (ZXing) and merges them into meta.barcodes with boxes. For uploads that didn't come through the phone's decoder.") { intent, photo ->
      val img = blobs.readUpright(inputSha(intent, photo), 2400) ?: return@Builtin TransformOutcome("skipped", mapOf("reason" to "format not decodable server-side"))
      val found = decodeBarcodes(img)
      val known = (photo.meta["barcodes"] as? List<*>).orEmpty()
      val knownValues = known.mapNotNull { (it as? Map<*, *>)?.get("value")?.toString() ?: it as? String }.toSet()
      val fresh = found.filter { it["value"] !in knownValues }
      TransformOutcome(data = mapOf("barcodes" to found), metaPatch = if (fresh.isEmpty()) null else mapOf("barcodes" to known + fresh))
    },
    Builtin("upright-2k", "rewrite", "Bakes the EXIF rotation into the pixels and caps the long side at 2048px (JPEG). Geometry-preserving, so it becomes the current version.") { intent, photo ->
      val img = blobs.readUpright(inputSha(intent, photo), 2048) ?: return@Builtin TransformOutcome("skipped", mapOf("reason" to "format not decodable server-side"))
      val scale = minOf(1.0, 2048.0 / maxOf(img.width, img.height))
      TransformOutcome(image = blobs.encodeJpeg(scaled(img, scale)), data = mapOf("width" to (img.width * scale).toInt(), "height" to (img.height * scale).toInt()))
    },
    Builtin("crop-detections", "rewrite", "Crops to the union of the detected boxes (meta.barcodes[].box) plus a margin. Changes geometry, so it is added as a side version and does not become current.") { intent, photo ->
      val boxes = (photo.meta["barcodes"] as? List<*>).orEmpty().mapNotNull { ((it as? Map<*, *>)?.get("box") as? List<*>)?.mapNotNull { n -> (n as? Number)?.toDouble() }?.takeIf { b -> b.size == 4 } }
      if (boxes.isEmpty()) return@Builtin TransformOutcome("skipped", mapOf("reason" to "no detection boxes"))
      val margin = ((intent.params?.get("margin") as? Number)?.toDouble() ?: 0.35)
      val img = blobs.readUpright(inputSha(intent, photo), 4096) ?: return@Builtin TransformOutcome("skipped", mapOf("reason" to "format not decodable server-side"))
      val x0 = boxes.minOf { it[0] }; val y0 = boxes.minOf { it[1] }
      val x1 = boxes.maxOf { it[0] + it[2] }; val y1 = boxes.maxOf { it[1] + it[3] }
      val pad = maxOf(x1 - x0, y1 - y0) * margin
      val cx0 = ((x0 - pad).coerceIn(0.0, 1.0) * img.width).toInt(); val cy0 = ((y0 - pad).coerceIn(0.0, 1.0) * img.height).toInt()
      val cx1 = ((x1 + pad).coerceIn(0.0, 1.0) * img.width).toInt(); val cy1 = ((y1 + pad).coerceIn(0.0, 1.0) * img.height).toInt()
      if (cx1 - cx0 < 8 || cy1 - cy0 < 8) return@Builtin TransformOutcome("skipped", mapOf("reason" to "crop too small"))
      val crop = BufferedImage(cx1 - cx0, cy1 - cy0, BufferedImage.TYPE_INT_RGB)
      crop.createGraphics().apply { drawImage(img, -cx0, -cy0, null); dispose() }
      TransformOutcome(
        image = blobs.encodeJpeg(crop), makeCurrent = false,
        data = mapOf("crop" to listOf(cx0.toDouble() / img.width, cy0.toDouble() / img.height, (cx1 - cx0).toDouble() / img.width, (cy1 - cy0).toDouble() / img.height)),
      )
    },
  )

  val byName = all.associateBy { it.name }

  /** The blob the intent was issued against, not whatever is current now. */
  private fun inputSha(intent: IntentDto, photo: PhotoDto): String =
    intent.version?.let { v -> runCatching { photos.blobRef(photo.token, v).first }.getOrNull() } ?: photo.sha256

  private fun scaled(img: BufferedImage, scale: Double): BufferedImage {
    if (scale >= 1.0) return img
    val out = BufferedImage(maxOf(1, (img.width * scale).toInt()), maxOf(1, (img.height * scale).toInt()), BufferedImage.TYPE_INT_RGB)
    out.createGraphics().apply {
      setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      drawImage(img, 0, 0, out.width, out.height, null)
      dispose()
    }
    return out
  }

  companion object {
    private val FORMATS = mapOf(
      BarcodeFormat.EAN_13 to "ean13", BarcodeFormat.UPC_A to "ean13", BarcodeFormat.EAN_8 to "ean8", BarcodeFormat.UPC_E to "upce",
      BarcodeFormat.CODE_128 to "code128", BarcodeFormat.CODE_39 to "code39", BarcodeFormat.ITF to "itf14", BarcodeFormat.QR_CODE to "qr",
      BarcodeFormat.DATA_MATRIX to "datamatrix", BarcodeFormat.PDF_417 to "pdf417", BarcodeFormat.AZTEC to "aztec",
    )

    /** [{symbology, value, box, source: "server"}], boxes as fractions of [img], origin top-left. */
    fun decodeBarcodes(img: BufferedImage): List<Map<String, Any?>> {
      val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to FORMATS.keys.toList())
      val results = try {
        GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(img))), hints)
      } catch (e: com.google.zxing.NotFoundException) {
        arrayOf()
      }
      return results.mapNotNull { r ->
        val name = FORMATS[r.barcodeFormat] ?: return@mapNotNull null
        // Same canonical form the phone uses: UPC-A as 13-digit EAN with a leading zero.
        val value = if (r.barcodeFormat == BarcodeFormat.UPC_A) "0" + r.text else r.text
        val pts = r.resultPoints.orEmpty().filterNotNull()
        val box = if (pts.isEmpty()) null else {
          val x0 = pts.minOf { it.x } / img.width; val x1 = pts.maxOf { it.x } / img.width
          val y0 = pts.minOf { it.y } / img.height; val y1 = pts.maxOf { it.y } / img.height
          // 1D readers report the two ends of one scan line: give the box some height.
          val h = maxOf(y1 - y0, (x1 - x0) * 0.35f); val w = maxOf(x1 - x0, (y1 - y0) * 0.35f)
          val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
          listOf(cx - w / 2, cy - h / 2, w, h).map { Math.round(it.toDouble().coerceIn(0.0, 1.0) * 10000) / 10000.0 }
        }
        mapOf("symbology" to name, "value" to value, "box" to box, "source" to "server")
      }.distinctBy { it["value"] }
    }
  }
}

/** Seeds the library (and the inbox), then works off pending intents for built-in transforms. */
@Singleton
class TransformRunner @Inject constructor(
  private val store: TransformStore,
  private val builtins: Builtins,
  private val photos: PhotoStore,
  private val collections: CollectionStore,
  private val blobs: BlobStore,
  private val config: SnapzConfig,
) : AbstractIdleService() {
  private lateinit var executor: ScheduledExecutorService

  override fun startUp() {
    seed()
    val period = config.snapz.transform_interval_ms
    executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "transform-runner").apply { isDaemon = true } }
    executor.scheduleWithFixedDelay({
      try {
        runPending()
      } catch (e: Exception) {
        logger.error(e) { "transform run failed; will retry" }
      }
    }, period, period, TimeUnit.MILLISECONDS)
  }

  override fun shutDown() {
    executor.shutdownNow()
  }

  fun seed() {
    builtins.all.forEach { store.register(it.name, it.kind, it.description, builtin = true) }
    val inbox = config.snapz.inbox_slug
    if (config.snapz.auto_create_inbox && collections.list().none { it.slug == inbox }) {
      collections.create(inbox, "Inbox", "Snap first, file later: photos here are ranked for triage.", "📥")
      store.setChain(inbox, listOf("suggest-collection" to null))
    }
  }

  /** Runs every pending built-in intent, including ones queued by the results it writes. */
  @Synchronized
  fun runPending(): Int {
    var total = 0
    while (true) {
      val batch = store.pending(null, builtinOnly = true, limit = 50)
      if (batch.isEmpty()) return total
      for (intent in batch) {
        runOne(intent)
        total++
      }
    }
  }

  private fun runOne(intent: IntentDto) {
    val builtin = builtins.byName[intent.transform]
    val photo = intent.photo?.let { runCatching { photos.get(it, includeDeleted = false) }.getOrNull() }
    if (builtin == null || photo == null) {
      store.addResult(intent.token, "skipped", mapOf("reason" to if (photo == null) "photo is gone" else "no implementation"), null, null, WORKER)
      return
    }
    try {
      val out = builtin.run(intent, photo)
      store.addResult(intent.token, out.status, out.data, out.metaPatch, null, WORKER, out.image?.let(blobs::put), out.makeCurrent)
    } catch (e: Exception) {
      logger.warn(e) { "transform ${intent.transform} failed on ${photo.token}" }
      store.addResult(intent.token, "error", null, null, e.toString(), WORKER)
    }
  }

  companion object {
    const val WORKER = "builtin"
    private val logger = getLogger<TransformRunner>()
  }
}
