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

/**
 * misk hands path parameters over still percent-encoded. Free-form ones (record keys, consumer
 * names) must be decoded or "a b" and "a%20b" become different keys depending on the client.
 */
private fun String.unescaped(): String = try {
  java.net.URLDecoder.decode(replace("+", "%2B"), Charsets.UTF_8)
} catch (e: IllegalArgumentException) {
  throw BadRequestException("bad percent-encoding in path")
}

// ------------------------------------------------------------------------------- collections

data class CollectionReq(val slug: String?, val name: String?, val description: String?, val icon: String?)
data class CollectionList(val collections: List<CollectionDto>)
data class ActionsReq(val actions: List<ActionDto>)

class CollectionActions @Inject constructor(private val store: CollectionStore, private val ctx: VaultContext) : WebAction {
  @Get("/api/collections")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun list() = CollectionList(store.list(ctx.current().id))

  @Post("/api/collections")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun create(@RequestBody req: CollectionReq): CollectionDto {
    val name = req.name?.takeIf { it.isNotBlank() } ?: req.slug ?: throw BadRequestException("name is required")
    return store.create(ctx.current().id, req.slug, name, req.description, req.icon)
  }

  @Get("/api/collections/{slug}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun get(@PathParam slug: String) = store.get(ctx.current().id, slug)

  @Patch("/api/collections/{slug}")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun update(@PathParam slug: String, @RequestBody req: CollectionReq) = store.update(ctx.current().id, slug, req.name, req.description, req.icon)

  /**
   * Replace the collection's action set: the verbs a photo filed here can carry. Whatever reads
   * the feed and gives them meaning (e.g. pantry_orders.py) is the natural caller.
   */
  @Put("/api/collections/{slug}/actions")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun setActions(@PathParam slug: String, @RequestBody req: ActionsReq) = store.setActions(ctx.current().id, slug, req.actions)

  @Delete("/api/collections/{slug}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun delete(@PathParam slug: String, @QueryParam force: Boolean?): Ok {
    store.delete(ctx.current().id, slug, force == true)
    return Ok()
  }
}

// ------------------------------------------------------------------------------------ photos

data class PhotoPatch(val note: String?, val meta: Map<String, Any?>?, val taken_at: String?, val collection: String?, val action: String?, val qty: Int?)
data class CopyReq(val collection: String)
data class BatchReq(val op: String, val tokens: List<String>, val collection: String?, val action: String?, val qty: Int?)
data class BatchResp(val photos: List<PhotoDto>)
data class PurgeResp(val photos_purged: Int, val blobs_deleted: Int)

class PhotoActions @Inject constructor(
  private val store: PhotoStore,
  private val blobs: BlobStore,
  private val json: Json,
  private val config: SnapzConfig,
  private val ctx: VaultContext,
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
  ) = store.list(ctx.current().id, collection, before, limit ?: 200, barcode, deleted == true)

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
    val (photo, _) = store.create(ctx.current().id, collection, blob, takenMs, note, metaObj, client_id)
    return photo
  }

  @Get("/api/photos/{token}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun get(@PathParam token: String) = store.get(ctx.photo(token))

  @Patch("/api/photos/{token}")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun update(@PathParam token: String, @RequestBody req: PhotoPatch) =
    store.update(ctx.photo(token), req.note, req.meta?.let { Json.normalize(it) as Map<String, Any?> }, req.taken_at?.let(::parseTime), ctx.current().id, req.collection, req.action, req.qty)

  @Post("/api/photos/{token}/copy")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun copy(@PathParam token: String, @RequestBody req: CopyReq) = store.copy(ctx.photo(token), ctx.current().id, req.collection)

  @Post("/api/photos/{token}/restore")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun restore(@PathParam token: String) = store.restore(ctx.photo(token))

  @Delete("/api/photos/{token}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun delete(@PathParam token: String) = store.delete(ctx.photo(token))

  @Post("/api/photos/batch")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun batch(@RequestBody req: BatchReq) = BatchResp(store.batch(req.op, ctx.photos(req.tokens), ctx.current().id, req.collection, req.action, req.qty))

  /** Empties trash older than N days (default 30), in every vault, and deletes blobs nothing references any more. */
  @Post("/api/admin/purge")
  @Authenticated(capabilities = [ADMIN])
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
  private val ctx: VaultContext,
) : WebAction {
  /**
   * Image bytes. `size=thumb` serves the derived JPEG (falls back to the original when none can
   * be made). `version=<token>` serves one specific version; `v=<anything>` is a cache-buster the
   * API puts on URLs it hands out (it changes whenever the photo's current version does).
   */
  @Get("/blobs/{token}")
  @Authenticated(capabilities = [USER])
  fun get(@PathParam token: String, @QueryParam size: String?, @QueryParam version: String?, @QueryParam v: String?): Response<ResponseBody> {
    val (sha, contentType) = store.blobRef(ctx.photo(token), version)
    val thumb = if (size == "thumb") blobs.thumbOrNull(sha) else null
    val file = thumb ?: blobs.original(sha)
    if (!file.exists()) throw NotFoundException("blob for '$token' is missing on disk")
    val etag = "\"${if (thumb != null) "t-" else ""}$sha\""
    val headers = Headers.Builder()
      .add("Content-Type", if (thumb != null) "image/jpeg" else contentType)
      .add("ETag", etag)
      // A pinned URL (version= or v=) names fixed bytes: cache forever. A bare /blobs/{token}
      // follows the current version, so it must revalidate (cheap: the ETag is the sha256).
      .add("Cache-Control", if (version != null || v != null) "private, max-age=31536000, immutable" else "private, no-cache")
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

class RecordActions @Inject constructor(private val store: RecordStore, private val json: Json, private val ctx: VaultContext) : WebAction {
  @Get("/api/records")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun namespaces() = NamespaceList(store.namespaces(ctx.current().id))

  @Get("/api/records/{ns}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun scan(@PathParam ns: String, @QueryParam prefix: String?, @QueryParam limit: Int?) = RecordList(store.scan(ctx.current().id, ns.unescaped(), prefix, limit ?: 500))

  @Get("/api/records/{ns}/{k}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun get(@PathParam ns: String, @PathParam k: String) = store.get(ctx.current().id, ns.unescaped(), k.unescaped())

  /** Body is the JSON value itself (object, array, string, number...). */
  @Put("/api/records/{ns}/{k}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun put(@PathParam ns: String, @PathParam k: String, @RequestBody body: String) = store.put(ctx.current().id, ns.unescaped(), k.unescaped(), json.parse(body))

  @Delete("/api/records/{ns}/{k}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun delete(@PathParam ns: String, @PathParam k: String): Ok {
    store.delete(ctx.current().id, ns.unescaped(), k.unescaped())
    return Ok()
  }
}

// ------------------------------------------------------------------------------------- feeds

data class PublishingReq(val enabled: Boolean)
data class CursorReq(val feed: String, val cursor: Long, val shard: Int?, val shard_count: Int?)
data class CursorResp(val consumer: String, val feed: String, val shard: Int, val cursor: Long)

class FeedActions @Inject constructor(private val feeds: FeedService, private val publisher: FeedPublisher, private val ctx: VaultContext) : WebAction {
  /**
   * The feed endpoint (feedz-compatible reply shape): {"entries": [...], "cursor": N}.
   * Poll with after=<cursor>. Optional filters: collection= (photos), ns= (records),
   * transform= (intents, results). With a vault named (header, ?vault=, or a pinned token) the
   * feed carries that vault only; otherwise every vault, each entry saying which.
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
    @QueryParam transform: String?,
  ) = feeds.fetch(feed, after ?: 0, limit ?: 100, shard, shard_count, ctx.explicit(), ctx.current(), collection, ns, transform)

  @Get("/_feeds/status")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun status() = feeds.status()

  /** Server-side cursor storage so consumer scripts can be stateless. Unknown consumer = position 0. */
  @Get("/_feeds/cursors/{consumer}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun cursor(@PathParam consumer: String, @QueryParam feed: String, @QueryParam shard: Int?) =
    consumer.unescaped().let { CursorResp(it, feed, shard ?: 0, feeds.cursor(it, feed, shard ?: 0)) }

  @Put("/_feeds/cursors/{consumer}")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun setCursor(@PathParam consumer: String, @RequestBody req: CursorReq): CursorResp {
    if (req.cursor < 0) throw BadRequestException("cursor must be >= 0")
    feeds.setCursor(consumer.unescaped(), req.feed, req.shard ?: 0, req.shard_count ?: 1, req.cursor)
    return CursorResp(consumer.unescaped(), req.feed, req.shard ?: 0, req.cursor)
  }

  @Delete("/_feeds/cursors/{consumer}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun deleteCursor(@PathParam consumer: String, @QueryParam feed: String): Ok {
    feeds.deleteCursor(consumer.unescaped(), feed)
    return Ok()
  }

  /**
   * Publish pending rows now instead of waiting for the next tick. `feed=` limits it to one feed;
   * `force=true` publishes even where publishing is switched off (stepping a paused feed by hand).
   */
  @Post("/_feeds/publish")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun publish(@QueryParam feed: String?, @QueryParam force: Boolean?) = mapOf("published" to publisher.publishAll(feed, force == true))

  /** The per-feed publish switch. Off = mutations accumulate as pending; consumers see nothing new. */
  @Put("/_feeds/publishing/{feed}")
  @Authenticated(capabilities = [ADMIN])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun setPublishing(@PathParam feed: String, @RequestBody req: PublishingReq): Ok {
    publisher.setEnabled(feed, req.enabled)
    return Ok()
  }
}

// ------------------------------------------------------------------------------------ tokens

data class TokenReq(val name: String, val vault: String?, val read_only: Boolean?, val ttl_days: Int?)
data class TokenCreated(val id: Long, val name: String, val token: String)
data class TokenList(val tokens: List<TokenDto>)

class TokenActions @Inject constructor(
  private val tokens: TokenStore,
  private val caller: @JvmSuppressWildcards ActionScoped<misk.MiskCaller?>,
) : WebAction {
  @Get("/api/tokens")
  @Authenticated(capabilities = [ADMIN])
  @ResponseContentType(JSON)
  fun list() = TokenList(tokens.list())

  /**
   * Mint a script token. The plaintext is in this response and nowhere else, ever. `vault` pins
   * it to one vault, `read_only` to GETs (plus saving its own feed cursors), `ttl_days` expires it.
   */
  @Post("/api/tokens")
  @Authenticated(capabilities = [ADMIN])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun create(@RequestBody req: TokenReq): TokenCreated {
    if (req.name.isBlank()) throw BadRequestException("name is required")
    if (req.ttl_days != null && req.ttl_days <= 0) throw BadRequestException("ttl_days must be positive")
    val (id, plain) = tokens.create(
      "script", req.name.trim(), caller.get()?.principal ?: "?",
      ttlMs = req.ttl_days?.let { Duration.ofDays(it.toLong()).toMillis() }, vault = req.vault?.takeIf { it.isNotBlank() }, readOnly = req.read_only == true,
    )
    return TokenCreated(id, req.name.trim(), plain)
  }

  @Delete("/api/tokens/{id}")
  @Authenticated(capabilities = [ADMIN])
  @ResponseContentType(JSON)
  fun revoke(@PathParam id: Long): Ok {
    if (!tokens.revoke(id)) throw NotFoundException("no live token $id")
    return Ok()
  }
}

// -------------------------------------------------------------------------------- transforms

data class TransformReq(val kind: String, val description: String?)
data class TransformList(val transforms: List<TransformDto>)
data class ChainLinkReq(val transform: String, val params: Map<String, Any?>?)
data class ChainReq(val transforms: List<ChainLinkReq>)
data class ChainResp(val collection: String, val transforms: List<ChainLinkDto>)
data class IntentReq(val transform: String, val params: Map<String, Any?>?)
data class ResultReq(val status: String?, val data: Any?, val meta_patch: Map<String, Any?>?, val error: String?, val worker: String?)
data class CurrentReq(val version: String)
data class IntentList(val intents: List<IntentDto>)
data class InboxResp(val inbox: String, val photos: List<InboxItem>)

class TransformActions @Inject constructor(
  private val store: TransformStore,
  private val blobs: BlobStore,
  private val json: Json,
  private val runner: TransformRunner,
  private val caller: @JvmSuppressWildcards ActionScoped<misk.MiskCaller?>,
  private val ctx: VaultContext,
) : WebAction {
  /** Intents are named by token; a pinned caller only reaches the ones on its vault's photos. */
  private fun own(intent: String): String {
    val pin = ctx.pinned() ?: return intent
    if (store.vaultOfIntent(intent) != pin) throw NotFoundException("no intent '$intent'")
    return intent
  }

  @Get("/api/transforms")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun list() = TransformList(store.list())

  /** Register (or refresh) an external transform. Workers do this on startup. */
  @Put("/api/transforms/{name}")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun register(@PathParam name: String, @RequestBody req: TransformReq): Ok {
    store.register(name, req.kind, req.description)
    return Ok()
  }

  @Get("/api/collections/{slug}/transforms")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun chain(@PathParam slug: String) = ChainResp(slug, store.chain(ctx.current().id, slug))

  /** Replace a collection's chain. Applies to photos that enter the collection from now on. */
  @Put("/api/collections/{slug}/transforms")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun setChain(@PathParam slug: String, @RequestBody req: ChainReq) =
    ChainResp(slug, store.setChain(ctx.current().id, slug, req.transforms.map { it.transform to it.params?.let { p -> Json.normalize(p) as Map<String, Any?> } }))

  /**
   * Start the chain for photos already in the collection: those it has never run on, or with
   * all=true every live photo (a re-run after editing the chain).
   */
  @Post("/api/collections/{slug}/transforms/run")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun backfill(@PathParam slug: String, @QueryParam all: Boolean?) = mapOf("started" to store.backfill(ctx.current().id, slug, all == true))

  /** Versions (the tree), plus every intent and its results: the photo's processing history. */
  @Get("/api/photos/{token}/history")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun history(@PathParam token: String) = store.history(ctx.photo(token))

  /** Ask for one transform on one photo, outside any chain. */
  @Post("/api/photos/{token}/intents")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun request(@PathParam token: String, @RequestBody req: IntentReq): IntentDto =
    store.request(ctx.photo(token), req.transform, req.params?.let { Json.normalize(it) as Map<String, Any?> }, caller.get()?.principal)

  /** Make a version current (e.g. step back to the original). */
  @Put("/api/photos/{token}/current")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun setCurrent(@PathParam token: String, @RequestBody req: CurrentReq) = store.setCurrent(ctx.photo(token), req.version)

  @Get("/api/intents")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun pending(@QueryParam transform: String?, @QueryParam limit: Int?) = IntentList(store.pending(transform, builtinOnly = false, limit = limit ?: 100, vaultId = ctx.explicit()?.id))

  @Get("/api/intents/{token}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun intent(@PathParam token: String) = store.intent(own(token))

  /** A worker's answer for an extract transform (or a failure / skip / halt of any kind). */
  @Post("/api/intents/{token}/results")
  @Authenticated(capabilities = [USER])
  @RequestContentType(JSON)
  @ResponseContentType(JSON)
  fun result(@PathParam token: String, @RequestBody req: ResultReq): IntentDto =
    store.addResult(own(token), req.status ?: "ok", Json.normalize(req.data), req.meta_patch?.let { Json.normalize(it) as Map<String, Any?> }, req.error, req.worker)

  /**
   * A worker's answer for a rewrite transform: the body is the new image. It becomes a child of
   * the version the intent read, and the photo's current version unless current=false.
   *   curl --data-binary @out.jpg "$U/api/intents/$I/output?worker=me&data=%7B%7D"
   */
  @Post("/api/intents/{token}/output")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun output(
    @PathParam token: String,
    @QueryParam worker: String?,
    @QueryParam data: String?,
    @QueryParam current: Boolean?,
    @RequestBody body: ByteString,
  ): IntentDto {
    if (body.size == 0) throw BadRequestException("empty body: send the transformed image bytes")
    return store.addResult(own(token), "ok", data?.let(json::parse), null, null, worker, blobs.put(body.toByteArray()), current != false)
  }

  /** Run pending built-in intents now instead of waiting for the next tick. */
  @Post("/api/transforms/run")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun run() = mapOf("ran" to runner.runPending())
}

class InboxAction @Inject constructor(
  private val db: Db,
  private val photos: PhotoStore,
  private val suggester: Suggester,
  private val config: SnapzConfig,
  private val ctx: VaultContext,
) : WebAction {
  /**
   * Everything waiting to be filed, newest first, each with collections ranked by probability.
   * Ranks are computed at read time, so filing one photo immediately re-ranks the rest.
   */
  @Get("/api/inbox")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun inbox(@QueryParam limit: Int?): InboxResp {
    val slug = config.snapz.inbox_slug
    val vault = ctx.current()
    val page = try {
      photos.list(vault.id, slug, null, limit ?: 200, null, false).photos
    } catch (e: NotFoundException) {
      return InboxResp(slug, listOf())
    }
    val ranked = db.tx { c -> suggester.suggest(c, vault.id, null).mapKeys { (id, _) -> c.select("SELECT token FROM photos WHERE id = ?", listOf(id)) { it.getString(1) }.first() } }
    return InboxResp(slug, page.map { p -> InboxItem(p, ranked[p.token]?.first ?: listOf(), ranked[p.token]?.second ?: mapOf()) })
  }
}

// -------------------------------------------------------------------------------------- info

/** One named value of a product-level enum, with what it means. */
data class EnumValue(val name: String, val description: String)
data class EnumDto(val name: String, val description: String, val values: List<EnumValue>)
data class InfoDto(val settings: Map<String, Any?>, val enums: List<EnumDto>)

/**
 * The product's vocabulary in one place: every closed set of names a client can send or will see.
 * Kept next to the code that defines them so the web UI's info tab can't drift into fiction.
 */
class InfoAction @Inject constructor(private val config: SnapzConfig, private val builtins: Builtins) : WebAction {
  @Get("/api/info")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(JSON)
  fun info(): InfoDto {
    val s = config.snapz
    fun e(name: String, description: String, vararg values: Pair<String, String>) = EnumDto(name, description, values.map { EnumValue(it.first, it.second) })
    return InfoDto(
      settings = linkedMapOf(
        "public_url" to s.public_url,
        "oidc_issuer" to s.oidc?.issuer,
        "oidc_allowed_emails" to s.oidc?.allowed_emails?.size,
        "oidc_allowed_groups" to s.oidc?.allowed_groups,
        "bootstrap_tokens" to s.bootstrap_tokens.size,
        "session_ttl_days" to s.session_ttl_days,
        "max_upload_bytes" to s.max_upload_bytes,
        "thumb_px" to s.thumb_px,
        "feed_publish_interval_ms" to s.feed_publish_interval_ms,
        "feed_shards" to s.feed_shards,
        "transform_interval_ms" to s.transform_interval_ms,
        "inbox_slug" to s.inbox_slug,
        "default_vault" to s.default_vault,
        "timezone" to s.timezone,
        "ios_redirect_scheme" to s.ios_redirect_scheme,
      ),
      enums = listOf(
        e("login mechanism", "How a request proves who it is.",
          "oidc (web)" to "GET /auth/login → provider → /auth/callback → session cookie. ${if (s.oidc != null) "ENABLED" else "not configured"}",
          "oidc (ios)" to "Same flow in the system web sheet, ends at ${s.ios_redirect_scheme}://auth?code=…; POST /auth/device-token trades code + PKCE verifier for a device token.",
          "bearer" to "Authorization: Bearer <token>. Any token kind works.",
          "token-login" to "POST /auth/token-login {token} → session cookie. Web UI fallback when OIDC is off.",
        ),
        e("token kind", "api_tokens.kind — only SHA-256 hashes are stored. Any kind can be pinned to a vault and/or read-only.",
          "session" to "Web cookie ($SESSION_COOKIE), expires after session_ttl_days.",
          "device" to "One per signed-in phone; no expiry; revoked on sign-out.",
          "script" to "Minted by hand (tokens tab / POST /api/tokens); optional vault pin, read-only flag and ttl_days.",
          "bootstrap" to "Defined in config, not in the DB. Dev / break-glass.",
        ),
        e("capability", "misk capability checked on every /api, /blobs and /_feeds route.",
          USER to "Every valid credential. A read-only one only has it on GETs (and on PUT /_feeds/cursors, so a consumer can save its place).",
          ADMIN to "Credentials that are neither pinned to a vault nor read-only: vault and token management, purge, feed publish switches.",
          "vault:<slug>" to "Carried by a pinned credential: it sees that vault only.",
        ),
        e("vault selection", "Which vault a request is about.",
          "$VAULT_HEADER header" to "What the apps and snapz.py send (SNAPZ_VAULT).",
          "?vault=" to "Same thing for curl.",
          "(neither)" to "The default vault (${s.default_vault}) for anything addressed by name; feeds carry every vault.",
          "by token" to "Photos, blobs and intents are addressed by token and found in whichever vault they live. Moving one to a collection moves it into the request's vault.",
        ),
        e("feed", "GET /_feeds/fetch/{feed}. Each has its own sequence and publish switch.",
          "photos" to "One entry per photo row. Filter: collection=. Deletes arrive with deleted_at set.",
          "collections" to "One entry per collection (with live counts).",
          "vaults" to "One entry per vault (with live counts).",
          "records" to "Derived (ns, k) JSON documents. Filter: ns=.",
          "intents" to "Requests to run a transform on a photo version. Append-only. Filter: transform=. What workers consume.",
          "results" to "What transforms answered. Append-only. Filter: transform=.",
        ),
        e("batch op", "POST /api/photos/batch {op, tokens, collection?} — all-or-nothing.",
          "move" to "Change collection_id (works on trashed photos too). Optional action / qty: the verb it is filed with.",
          "copy" to "New photo row pointing at the same sha256 blob; no bytes are duplicated.",
          "delete" to "Soft delete: sets deleted_at, photo goes to the trash.",
          "restore" to "Clear deleted_at.",
        ),
        e("image format", "Sniffed from the bytes on upload; Content-Type is ignored.",
          "image/jpeg" to "Thumbnail + dimensions, EXIF orientation honored.",
          "image/png" to "Thumbnail + dimensions.",
          "image/gif" to "Thumbnail of the first frame.",
          "image/heic" to "Stored as-is; no server thumbnail (ImageIO can't decode it).",
          "image/webp" to "Stored as-is; no server thumbnail.",
          "image/avif" to "Stored as-is; no server thumbnail.",
        ),
        e("barcode symbology", "meta.barcodes[].symbology as written by the iOS client. The server stores them verbatim.",
          "ean13" to "Retail. UPC-A is reported as EAN-13 with a leading 0.",
          "ean8" to "Small retail packages.",
          "upce" to "Zero-suppressed UPC.",
          "code128" to "Shipping / logistics.",
          "code39" to "Industrial, asset tags.",
          "itf14" to "Cartons.",
          "qr" to "2D, URLs and payloads.",
          "datamatrix" to "2D, small parts / pharma lot codes.",
          "pdf417" to "2D stacked, IDs and boarding passes.",
          "aztec" to "2D, tickets.",
        ),
        e("barcode source", "meta.barcodes[].source — where a detection came from.",
          "live" to "Decoded from the camera's video stream before the shutter (AVCaptureMetadataOutput).",
          "still" to "Decoded from the saved JPEG (Vision).",
          "server" to "Decoded by the `barcodes` transform (ZXing).",
        ),
        e("transform kind", "transforms.kind — what a transform hands back.",
          "extract" to "Data about the photo (result.data), optionally a meta_patch merged into photo.meta.",
          "rewrite" to "A new image: a child version of the one it read.",
        ),
        e("built-in transform", "Run in-process; anything else is an external worker consuming the intents feed.",
          *builtins.all.map { "${it.name} (${it.kind})" to it.description }.toTypedArray(),
        ),
        e("intent origin", "transform_intents.origin",
          "chain" to "Queued by a collection's chain: on entering the collection, then link by link as results arrive.",
          "manual" to "Requested for one photo (POST /api/photos/{token}/intents). Does not continue any chain.",
        ),
        e("result status", "transform_results.status — an intent's state is its latest result, or pending.",
          "ok" to "Done. The chain continues.",
          "skipped" to "Not applicable to this photo. The chain continues.",
          "halt" to "Done, and stop the chain here (the interceptor that doesn't call proceed).",
          "error" to "Failed. The chain stops; post another result to retry.",
        ),
        e("suggestion feature", "Inputs to P(collection | photo) for inbox triage (naive Bayes, add-one smoothing).",
          "barcode" to "yes / no", "hour" to "local hour (${s.timezone}) in 3h buckets", "weekend" to "yes / no", "shape" to "portrait / landscape / square",
          "source" to "meta.source", "prev" to "collection of the nearest earlier filed photo within 10 minutes, else none",
        ),
        e("photo meta convention", "meta is free-form JSON; these keys are understood by the UIs.",
          "source" to "ios | web | cli | <your script>",
          "barcodes" to "[{symbology, value, box?, source?}] — values are denormalized to photo.barcodes and searchable with ?barcode=.",
          "barcodes[].box" to "[x, y, w, h] as fractions of the upright image, origin top-left. Drawn as an overlay.",
          "filename" to "Original file name for web/cli uploads.",
          "action" to "The verb the photo was filed with — one of its collection's actions. Absent = just a picture.",
          "qty" to "Units for the action, when the action takes one (default 1).",
        ),
        e("collection action", "A collection's action set (PUT /api/collections/{slug}/actions): the verbs a photo filed there can carry. Names are free-form; the server only checks membership. Set when filing (move / PATCH with action, qty); leaving the collection clears it.",
          "name" to "What lands in meta.action.",
          "label" to "Optional button text.",
          "qty" to "true = the action takes a quantity (meta.qty).",
          "e.g. pantry" to "consume (stock -= qty) / out (stock = 0) / purchase (stock += qty) / count (stock = qty), as registered and interpreted by scripts/pantry_orders.py.",
        ),
        e("blob size", "GET /blobs/{token}?size=", "(none)" to "Original bytes.", "thumb" to "JPEG, longest side thumb_px; falls back to the original when none can be made."),
      ),
    )
  }
}

// ------------------------------------------------------------------------------------ web ui

class WebUiAction @Inject constructor(private val resources: ResourceLoader) : WebAction {
  /** The whole web client is one static page; it talks to the JSON API with the session cookie. */
  @Get("/")
  @Unauthenticated
  @ResponseContentType(MediaTypes.TEXT_HTML)
  fun index(): String = resources.utf8("classpath:/web/index.html") ?: throw NotFoundException("web ui not bundled")

  @Get("/icon.png")
  @Unauthenticated
  fun icon(): Response<ResponseBody> {
    val bytes = javaClass.getResourceAsStream("/web/icon.png")?.use { it.readBytes() } ?: throw NotFoundException("icon not bundled")
    val body = object : ResponseBody {
      override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
      }
    }
    return Response(body, Headers.Builder().add("Content-Type", "image/png").add("Cache-Control", "public, max-age=86400").build(), 200)
  }
}
