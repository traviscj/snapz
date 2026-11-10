package tcj.snapz

import java.io.File
import java.time.Duration
import javax.inject.Inject
import misk.exceptions.BadRequestException
import misk.exceptions.NotFoundException
import misk.exceptions.PayloadTooLargeException
import misk.resources.ResourceLoader
import misk.scope.ActionScoped
import misk.security.authz.Authenticated
import misk.security.authz.Unauthenticated
import misk.web.Delete
import misk.web.Get
import misk.web.HttpCall
import misk.web.Patch
import misk.web.PathParam
import misk.web.Post
import misk.web.Put
import misk.web.QueryParam
import misk.web.RequestBody
import misk.web.RequestContentType
import misk.web.Response
import misk.web.ResponseBody
import misk.web.ResponseContentType
import misk.web.actions.WebAction
import misk.web.mediatype.MediaTypes
import okhttp3.Headers
import okio.BufferedSink
import okio.ByteString
import okio.source

private const val JSON = MediaTypes.APPLICATION_JSON

data class Ok(val ok: Boolean = true)

// ------------------------------------------------------------------------------- collections

data class CollectionReq(val slug: String?, val name: String?, val description: String?, val icon: String?)
data class CollectionList(val collections: List<CollectionDto>)

class CollectionActions @Inject constructor(private val store: CollectionStore) : WebAction {
  @Get("/api/collections")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun list() = CollectionList(store.list())

  @Post("/api/collections")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun create(@RequestBody req: CollectionReq): CollectionDto {
    val name = req.name?.takeIf { it.isNotBlank() } ?: req.slug ?: throw BadRequestException("name is required")
    return store.create(req.slug, name, req.description, req.icon)
  }

  @Get("/api/collections/{slug}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun get(@PathParam slug: String) = store.get(slug)

  @Patch("/api/collections/{slug}")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun update(@PathParam slug: String, @RequestBody req: CollectionReq) = store.update(slug, req.name, req.description, req.icon)

  @Delete("/api/collections/{slug}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun delete(@PathParam slug: String, @QueryParam force: Boolean?): Ok {
    store.delete(slug, force == true)
    return Ok()
  }
}

// ------------------------------------------------------------------------------------ photos

data class PhotoPatch(val note: String?, val meta: Map<String, Any?>?, val taken_at: String?, val collection: String?)
data class CopyReq(val collection: String)
data class BatchReq(val op: String, val tokens: List<String>, val collection: String?)
data class BatchResp(val photos: List<PhotoDto>)
data class PurgeResp(val photos_purged: Int, val blobs_deleted: Int)

class PhotoActions @Inject constructor(
  private val store: PhotoStore,
  private val blobs: BlobStore,
  private val json: Json,
  private val config: SnapzConfig,
  private val publisher: FeedPublisher,
) : WebAction {
  @Get("/api/photos")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun list(
    @QueryParam collection: String?,
    @QueryParam before: String?,
    @QueryParam limit: Int?,
    @QueryParam barcode: String?,
    @QueryParam deleted: Boolean?,
  ) = store.list(collection, before, limit ?: 200, barcode, deleted == true)

  /**
   * Upload: the request body IS the image bytes (any Content-Type; the format is sniffed).
   *   curl -H "Authorization: Bearer $T" --data-binary @x.jpg "$URL/api/photos?collection=pantry"
   * `meta` is a URL-encoded JSON object; `client_id` makes retries idempotent.
   */
  @Post("/api/photos")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun upload(
    @QueryParam collection: String,
    @QueryParam taken_at: String?,
    @QueryParam client_id: String?,
    @QueryParam note: String?,
    @QueryParam meta: String?,
    @RequestBody body: ByteString,
  ): PhotoDto {
    if (body.size == 0) throw BadRequestException("empty body: send the image bytes as the request body")
    if (body.size > config.snapz.max_upload_bytes) throw PayloadTooLargeException("max upload is ${config.snapz.max_upload_bytes} bytes")
    if (client_id != null && client_id.length > 64) throw BadRequestException("client_id is at most 64 chars")
    val metaObj = json.parseObject(meta)
    val takenMs = taken_at?.let(::parseTime)
    // Existence check before touching disk; create() re-resolves inside its transaction.
    val blob = blobs.put(body.toByteArray())
    val (photo, _) = store.create(collection, blob, takenMs, note, metaObj, client_id)
    return photo
  }

  @Get("/api/photos/{token}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun get(@PathParam token: String) = store.get(token)

  @Patch("/api/photos/{token}")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun update(@PathParam token: String, @RequestBody req: PhotoPatch) =
    store.update(token, req.note, req.meta?.let { Json.normalize(it) as Map<String, Any?> }, req.taken_at?.let(::parseTime), req.collection)

  @Post("/api/photos/{token}/copy")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun copy(@PathParam token: String, @RequestBody req: CopyReq) = store.copy(token, req.collection)

  @Post("/api/photos/{token}/restore")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun restore(@PathParam token: String) = store.restore(token)

  @Delete("/api/photos/{token}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun delete(@PathParam token: String) = store.delete(token)

  @Post("/api/photos/batch")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun batch(@RequestBody req: BatchReq) = BatchResp(store.batch(req.op, req.tokens, req.collection))

  /** Empties trash older than N days (default 30) and deletes blobs nothing references any more. */
  @Post("/api/admin/purge")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun purge(@QueryParam older_than_days: Int?): PurgeResp {
    val days = older_than_days ?: 30
    if (days < 0) throw BadRequestException("older_than_days must be >= 0")
    val (n, orphans) = store.purge(Duration.ofDays(days.toLong()).toMillis())
    orphans.forEach(blobs::delete)
    return PurgeResp(n, orphans.size)
  }
}

class BlobAction @Inject constructor(
  private val store: PhotoStore,
  private val blobs: BlobStore,
  private val httpCall: @JvmSuppressWildcards ActionScoped<HttpCall>,
) : WebAction {
  /** Image bytes. `size=thumb` serves the derived JPEG (falls back to the original when none can be made). */
  @Get("/blobs/{token}")
  @Authenticated(capabilities = [USER])
  fun get(@PathParam token: String, @QueryParam size: String?): Response<ResponseBody> {
    val (sha, contentType) = store.blobRef(token)
    val thumb = if (size == "thumb") blobs.thumbOrNull(sha) else null
    val file = thumb ?: blobs.original(sha)
    if (!file.exists()) throw NotFoundException("blob for '$token' is missing on disk")
    val etag = "\"${if (thumb != null) "t-" else ""}$sha\""
    val headers = Headers.Builder()
      .add("Content-Type", if (thumb != null) "image/jpeg" else contentType)
      .add("ETag", etag)
      // A token's bytes never change, so clients may cache forever; `private` keeps proxies out.
      .add("Cache-Control", "private, max-age=31536000, immutable")
    if (httpCall.get().requestHeaders["If-None-Match"] == etag) return Response(EMPTY, headers.build(), 304)
    headers.add("Content-Length", file.length().toString())
    return Response(FileBody(file), headers.build(), 200)
  }

  private class FileBody(private val file: File) : ResponseBody {
    override fun writeTo(sink: BufferedSink) {
      file.source().use { sink.writeAll(it) }
    }
  }

  companion object {
    private val EMPTY = object : ResponseBody {
      override fun writeTo(sink: BufferedSink) {}
    }
  }
}

// ----------------------------------------------------------------------------------- records

data class NamespaceList(val namespaces: List<NamespaceDto>)
data class RecordList(val records: List<RecordDto>)

class RecordActions @Inject constructor(private val store: RecordStore, private val json: Json) : WebAction {
  @Get("/api/records")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun namespaces() = NamespaceList(store.namespaces())

  @Get("/api/records/{ns}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun scan(@PathParam ns: String, @QueryParam prefix: String?, @QueryParam limit: Int?) = RecordList(store.scan(ns, prefix, limit ?: 500))

  @Get("/api/records/{ns}/{k}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun get(@PathParam ns: String, @PathParam k: String) = store.get(ns, k)

  /** Body is the JSON value itself (object, array, string, number...). */
  @Put("/api/records/{ns}/{k}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun put(@PathParam ns: String, @PathParam k: String, @RequestBody body: String) = store.put(ns, k, json.parse(body))

  @Delete("/api/records/{ns}/{k}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun delete(@PathParam ns: String, @PathParam k: String): Ok {
    store.delete(ns, k)
    return Ok()
  }
}

// ------------------------------------------------------------------------------------- feeds

data class CursorReq(val feed: String, val cursor: Long, val shard: Int?, val shard_count: Int?)
data class CursorResp(val consumer: String, val feed: String, val shard: Int, val cursor: Long)

class FeedActions @Inject constructor(private val feeds: FeedService, private val publisher: FeedPublisher) : WebAction {
  /**
   * The feed endpoint (feedz-compatible reply shape): {"entries": [...], "cursor": N}.
   * Poll with after=<cursor>. Optional filters: collection= (photos), ns= (records).
   */
  @Get("/_feeds/fetch/{feed}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun fetch(
    @PathParam feed: String,
    @QueryParam after: Long?,
    @QueryParam limit: Int?,
    @QueryParam shard: Int?,
    @QueryParam shard_count: Int?,
    @QueryParam collection: String?,
    @QueryParam ns: String?,
  ) = feeds.fetch(feed, after ?: 0, limit ?: 100, shard, shard_count, collection, ns)

  @Get("/_feeds/status")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun status() = feeds.status()

  /** Server-side cursor storage so consumer scripts can be stateless. Unknown consumer = position 0. */
  @Get("/_feeds/cursors/{consumer}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun cursor(@PathParam consumer: String, @QueryParam feed: String, @QueryParam shard: Int?) =
    CursorResp(consumer, feed, shard ?: 0, feeds.cursor(consumer, feed, shard ?: 0))

  @Put("/_feeds/cursors/{consumer}")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun setCursor(@PathParam consumer: String, @RequestBody req: CursorReq): CursorResp {
    if (req.cursor < 0) throw BadRequestException("cursor must be >= 0")
    feeds.setCursor(consumer, req.feed, req.shard ?: 0, req.shard_count ?: 1, req.cursor)
    return CursorResp(consumer, req.feed, req.shard ?: 0, req.cursor)
  }

  @Delete("/_feeds/cursors/{consumer}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun deleteCursor(@PathParam consumer: String, @QueryParam feed: String): Ok {
    feeds.deleteCursor(consumer, feed)
    return Ok()
  }

  /** Publish pending rows now instead of waiting for the next tick. */
  @Post("/_feeds/publish")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun publish() = mapOf("published" to publisher.publishAll())
}

// ------------------------------------------------------------------------------------ tokens

data class TokenReq(val name: String)
data class TokenCreated(val id: Long, val name: String, val token: String)
data class TokenList(val tokens: List<TokenDto>)

class TokenActions @Inject constructor(
  private val tokens: TokenStore,
  private val caller: @JvmSuppressWildcards ActionScoped<misk.MiskCaller?>,
) : WebAction {
  @Get("/api/tokens")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun list() = TokenList(tokens.list())

  /** Mint a script token. The plaintext is in this response and nowhere else, ever. */
  @Post("/api/tokens")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun create(@RequestBody req: TokenReq): TokenCreated {
    if (req.name.isBlank()) throw BadRequestException("name is required")
    val (id, plain) = tokens.create("script", req.name.trim(), caller.get()?.principal ?: "?")
    return TokenCreated(id, req.name.trim(), plain)
  }

  @Delete("/api/tokens/{id}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun revoke(@PathParam id: Long): Ok {
    if (!tokens.revoke(id)) throw NotFoundException("no live token $id")
    return Ok()
  }
}

// ------------------------------------------------------------------------------------ web ui

class WebUiAction @Inject constructor(private val resources: ResourceLoader) : WebAction {
  /** The whole web client is one static page; it talks to the JSON API with the session cookie. */
  @Get("/")
  @Unauthenticated
  @ResponseContentType(MediaTypes.TEXT_HTML)
  fun index(): String = resources.utf8("classpath:/web/index.html") ?: throw NotFoundException("web ui not bundled")
}
