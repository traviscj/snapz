package tcj.snapz

import com.google.inject.Module
import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import javax.imageio.ImageIO
import javax.inject.Inject
import misk.MiskTestingServiceModule
import misk.config.ConfigModule
import misk.config.MiskConfig
import misk.inject.KAbstractModule
import misk.testing.MiskTest
import misk.testing.MiskTestModule
import misk.web.WebServerTestingModule
import misk.web.jetty.JettyService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import wisp.deployment.TESTING

/** A just-enough OIDC provider: discovery, token, userinfo. Who "logs in" is whatever [user] says. */
object FakeIdp {
  @Volatile var user: Map<String, Any?> = mapOf()
  @Volatile var lastTokenForm: Map<String, String> = mapOf()
  val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  val issuer get() = "http://127.0.0.1:${server.address.port}"

  init {
    fun reply(ex: com.sun.net.httpserver.HttpExchange, body: String) {
      val b = body.toByteArray()
      ex.responseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(200, b.size.toLong())
      ex.responseBody.use { it.write(b) }
    }
    server.createContext("/.well-known/openid-configuration") { ex ->
      reply(ex, """{"authorization_endpoint":"$issuer/authorize","token_endpoint":"$issuer/token","userinfo_endpoint":"$issuer/userinfo"}""")
    }
    server.createContext("/token") { ex ->
      lastTokenForm = String(ex.requestBody.readAllBytes()).split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, Charsets.UTF_8)
      }
      reply(ex, """{"access_token":"at-123","token_type":"Bearer"}""")
    }
    server.createContext("/userinfo") { ex ->
      check(ex.requestHeaders.getFirst("Authorization") == "Bearer at-123")
      val u = user
      val groups = (u["groups"] as? List<*>).orEmpty().joinToString(",") { "\"$it\"" }
      reply(ex, """{"sub":"${u["sub"]}","email":"${u["email"]}","groups":[$groups]}""")
    }
    server.start()
  }
}

class SnapzTestModule : KAbstractModule() {
  override fun configure() {
    val base = MiskConfig.load<SnapzConfig>("snapz", TESTING, listOf<String>())
    val config = base.copy(
      snapz = base.snapz.copy(
        oidc = OidcConfig(
          issuer = FakeIdp.issuer, client_id = "snapz", client_secret = "s3cret",
          allowed_emails = listOf("travis@example.com"), allowed_groups = listOf("snapz-users"),
        ),
      ),
    )
    install(MiskTestingServiceModule())
    install(WebServerTestingModule())
    install(ConfigModule.create("snapz", config))
    install(SnapzModule(config))
  }
}

/** Drives the service over real HTTP, the same path the iOS app, the web UI and scripts use. */
@MiskTest(startService = true)
class SnapzServiceTest {
  @MiskTestModule val module: Module = SnapzTestModule()

  @Inject lateinit var jetty: JettyService
  @Inject lateinit var db: Db
  @Inject lateinit var json: Json
  @Inject lateinit var publisher: FeedPublisher
  @Inject lateinit var blobs: BlobStore

  private val http = OkHttpClient.Builder().followRedirects(false).build()
  private val jsonType = "application/json".toMediaType()

  @BeforeEach
  fun setUp() {
    db.tx { c ->
      for (t in listOf("photos", "collections", "records", "api_tokens", "sequences", "feed_cursors")) c.exec("DELETE FROM $t")
    }
  }

  // ------------------------------------------------------------------------------ helpers

  private fun url(path: String) = jetty.httpServerUrl.resolve(path)!!

  private fun call(method: String, path: String, body: Any? = null, token: String? = "test-token", cookie: String? = null, raw: ByteArray? = null): Response {
    val rb = when {
      raw != null -> raw.toRequestBody("application/octet-stream".toMediaType())
      body is String -> body.toRequestBody(jsonType)
      body != null -> json.write(body).toRequestBody(jsonType)
      method in setOf("POST", "PUT", "PATCH") -> "".toRequestBody(null)
      else -> null
    }
    val req = Request.Builder().url(url(path)).method(method, rb)
    token?.let { req.header("Authorization", "Bearer $it") }
    cookie?.let { req.header("Cookie", it) }
    return http.newCall(req.build()).execute()
  }

  @Suppress("UNCHECKED_CAST")
  private fun obj(r: Response): Map<String, Any?> {
    val text = r.body!!.string()
    assertThat(r.code).withFailMessage { "HTTP ${r.code}: $text" }.isEqualTo(200)
    return json.parseObject(text)
  }

  private fun api(method: String, path: String, body: Any? = null) = obj(call(method, path, body))

  @Suppress("UNCHECKED_CAST")
  private fun Map<String, Any?>.list(key: String) = this[key] as List<Map<String, Any?>>

  private fun jpeg(w: Int = 64, h: Int = 48, color: Color = Color.RED): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    img.createGraphics().apply { paint = color; fillRect(0, 0, w, h); dispose() }
    return ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
  }

  private fun upload(collection: String, bytes: ByteArray, query: String = "") = obj(call("POST", "/api/photos?collection=$collection$query", raw = bytes))

  private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)

  // -------------------------------------------------------------------------------- tests

  @Test
  fun `api requires a credential and enforces it`() {
    assertThat(call("GET", "/api/collections", token = null).code).isEqualTo(401)
    assertThat(call("GET", "/api/collections", token = "nope").code).isEqualTo(401)
    assertThat(call("GET", "/_feeds/fetch/photos", token = null).code).isEqualTo(401)
    assertThat(call("GET", "/blobs/whatever", token = null).code).isEqualTo(401)
    assertThat(call("GET", "/api/me").code).isEqualTo(200)
    // the page itself is public; it has no data in it
    assertThat(call("GET", "/", token = null).code).isEqualTo(200)
  }

  @Test
  fun `collection crud`() {
    val c = api("POST", "/api/collections", mapOf("name" to "Pantry Stuff", "icon" to "🥫"))
    assertThat(c["slug"]).isEqualTo("pantry-stuff")
    assertThat(call("POST", "/api/collections", mapOf("name" to "Pantry Stuff")).code).isEqualTo(409)
    assertThat(call("POST", "/api/collections", mapOf("name" to "x", "slug" to "Bad Slug")).code).isEqualTo(400)

    api("PATCH", "/api/collections/pantry-stuff", mapOf("description" to "upc scans"))
    val got = api("GET", "/api/collections/pantry-stuff")
    assertThat(got["description"]).isEqualTo("upc scans")
    assertThat(got["name"]).isEqualTo("Pantry Stuff")
    assertThat(got["photo_count"]).isEqualTo(0L)

    upload("pantry-stuff", jpeg())
    assertThat(call("DELETE", "/api/collections/pantry-stuff").code).isEqualTo(409)
    api("DELETE", "/api/collections/pantry-stuff?force=true")
    assertThat(api("GET", "/api/collections").list("collections")).isEmpty()
    assertThat(api("GET", "/api/photos").list("photos")).isEmpty()
    assertThat(api("GET", "/api/photos?deleted=true").list("photos")).hasSize(1)
    // the slug is free again
    api("POST", "/api/collections", mapOf("slug" to "pantry-stuff", "name" to "Pantry 2"))
  }

  @Test
  fun `upload, metadata, blobs and thumbnails`() {
    api("POST", "/api/collections", mapOf("name" to "pantry"))
    val meta = """{"barcodes":[{"symbology":"ean13","value":"0012345678905"}],"qty":2}"""
    val bytes = jpeg(1600, 1200)
    val p = upload("pantry", bytes, "&taken_at=2026-11-01T12:00:00Z&note=${enc("top shelf")}&meta=${enc(meta)}&client_id=abc-1")
    assertThat(p["collection"]).isEqualTo("pantry")
    assertThat(p["content_type"]).isEqualTo("image/jpeg")
    assertThat(p["width"]).isEqualTo(1600L)
    assertThat(p["height"]).isEqualTo(1200L)
    assertThat(p["taken_at"]).isEqualTo("2026-11-01T12:00:00Z")
    assertThat(p["note"]).isEqualTo("top shelf")
    assertThat(p["barcodes"]).isEqualTo(listOf("0012345678905"))
    assertThat((p["meta"] as Map<*, *>)["qty"]).isEqualTo(2L)

    // a retry with the same client_id is the same photo, not a duplicate
    val again = upload("pantry", bytes, "&client_id=abc-1")
    assertThat(again["token"]).isEqualTo(p["token"])
    assertThat(api("GET", "/api/photos?collection=pantry").list("photos")).hasSize(1)

    val full = call("GET", "/blobs/${p["token"]}")
    assertThat(full.header("Content-Type")).isEqualTo("image/jpeg")
    assertThat(full.header("Cache-Control")).contains("immutable")
    assertThat(full.body!!.bytes()).isEqualTo(bytes)
    assertThat(call("GET", "/blobs/${p["token"]}").newBuilder().build().header("ETag")).isEqualTo(full.header("ETag"))

    val thumb = call("GET", "/blobs/${p["token"]}?size=thumb")
    val timg = ImageIO.read(ByteArrayInputStream(thumb.body!!.bytes()))
    assertThat(timg.width).isEqualTo(400)
    assertThat(timg.height).isEqualTo(300)

    val notModified = http.newCall(
      Request.Builder().url(url("/blobs/${p["token"]}")).header("Authorization", "Bearer test-token").header("If-None-Match", full.header("ETag")!!).build()
    ).execute()
    assertThat(notModified.code).isEqualTo(304)

    assertThat(api("GET", "/api/photos?barcode=0012345678905").list("photos")).hasSize(1)
    assertThat(api("GET", "/api/photos?barcode=001234").list("photos")).isEmpty()

    assertThat(call("POST", "/api/photos?collection=pantry", raw = "not an image".toByteArray()).code).isEqualTo(400)
    assertThat(call("POST", "/api/photos?collection=nope", raw = bytes).code).isEqualTo(404)
  }

  @Test
  fun `list paginates newest first by taken_at`() {
    api("POST", "/api/collections", mapOf("name" to "food"))
    val tokens = (1..5).map { i -> upload("food", jpeg(color = Color(i * 40, 0, 0)), "&taken_at=${1_700_000_000_000 + i * 1000}")["token"] }
    val p1 = api("GET", "/api/photos?collection=food&limit=2")
    assertThat(p1.list("photos").map { it["token"] }).isEqualTo(listOf(tokens[4], tokens[3]))
    val p2 = api("GET", "/api/photos?collection=food&limit=2&before=${p1["next_before"]}")
    assertThat(p2.list("photos").map { it["token"] }).isEqualTo(listOf(tokens[2], tokens[1]))
    val p3 = api("GET", "/api/photos?collection=food&limit=2&before=${p2["next_before"]}")
    assertThat(p3.list("photos").map { it["token"] }).isEqualTo(listOf(tokens[0]))
    assertThat(p3["next_before"]).isNull()
  }

  @Test
  fun `move, copy, delete, restore, batch and purge`() {
    api("POST", "/api/collections", mapOf("name" to "a"))
    api("POST", "/api/collections", mapOf("name" to "b"))
    val t1 = upload("a", jpeg(color = Color.BLUE))["token"] as String
    val t2 = upload("a", jpeg(color = Color.GREEN))["token"] as String

    assertThat(api("PATCH", "/api/photos/$t1", mapOf("collection" to "b", "note" to "moved"))["collection"]).isEqualTo("b")
    val copy = api("POST", "/api/photos/$t1/copy", mapOf("collection" to "a"))
    assertThat(copy["token"]).isNotEqualTo(t1)
    assertThat(copy["sha256"]).isEqualTo(api("GET", "/api/photos/$t1")["sha256"])
    assertThat(copy["note"]).isEqualTo("moved")

    assertThat(api("DELETE", "/api/photos/$t1")["deleted_at"]).isNotNull()
    assertThat(api("GET", "/api/photos?collection=b").list("photos")).isEmpty()
    // trashed photos still serve their bytes (the trash view shows them)
    assertThat(call("GET", "/blobs/$t1?size=thumb").code).isEqualTo(200)
    assertThat(api("POST", "/api/photos/$t1/restore")["deleted_at"]).isNull()

    val moved = api("POST", "/api/photos/batch", mapOf("op" to "move", "tokens" to listOf(t2, copy["token"]), "collection" to "b"))
    assertThat(moved.list("photos").map { it["collection"] }).containsOnly("b")
    assertThat(api("GET", "/api/collections/b")["photo_count"]).isEqualTo(3L)
    // all-or-nothing
    assertThat(call("POST", "/api/photos/batch", mapOf("op" to "delete", "tokens" to listOf(t2, "missing"))).code).isEqualTo(404)
    assertThat(api("GET", "/api/collections/b")["photo_count"]).isEqualTo(3L)

    // t1 and its copy share a blob: purging one must keep the bytes for the other
    val sha = copy["sha256"] as String
    api("POST", "/api/photos/batch", mapOf("op" to "delete", "tokens" to listOf(t1)))
    assertThat(api("POST", "/api/admin/purge?older_than_days=0")).isEqualTo(mapOf("photos_purged" to 1L, "blobs_deleted" to 0L))
    assertThat(blobs.original(sha).exists()).isTrue()
    api("DELETE", "/api/photos/${copy["token"]}")
    assertThat(api("POST", "/api/admin/purge?older_than_days=0")["blobs_deleted"]).isEqualTo(1L)
    assertThat(blobs.original(sha).exists()).isFalse()
    assertThat(call("GET", "/api/photos/$t1").code).isEqualTo(404)
  }

  @Test
  fun `feed publishes every mutation and consumers page with a cursor`() {
    api("POST", "/api/collections", mapOf("name" to "pantry"))
    api("POST", "/api/collections", mapOf("name" to "work"))
    val t1 = upload("pantry", jpeg(color = Color.BLUE))["token"]
    val t2 = upload("work", jpeg(color = Color.GREEN))["token"]
    val t3 = upload("pantry", jpeg(color = Color.CYAN))["token"]

    // nothing is visible until published
    assertThat(api("GET", "/_feeds/fetch/photos").list("entries")).isEmpty()
    assertThat(api("POST", "/_feeds/publish")["published"]).isEqualTo(5L)

    val page1 = api("GET", "/_feeds/fetch/photos?limit=2")
    assertThat(page1.list("entries").map { it["token"] }).isEqualTo(listOf(t1, t2))
    assertThat(page1["cursor"]).isEqualTo(2L)
    val page2 = api("GET", "/_feeds/fetch/photos?limit=2&after=2")
    assertThat(page2.list("entries").map { it["token"] }).isEqualTo(listOf(t3))
    assertThat(page2["cursor"]).isEqualTo(3L)
    // caught up: empty page, cursor holds
    assertThat(api("GET", "/_feeds/fetch/photos?after=3")).containsEntry("cursor", 3L)

    assertThat(api("GET", "/_feeds/fetch/photos?collection=pantry").list("entries").map { it["token"] }).isEqualTo(listOf(t1, t3))

    // a mutation requeues the row behind everything already published; a delete arrives as a tombstone
    api("PATCH", "/api/photos/$t1", mapOf("note" to "edited"))
    api("DELETE", "/api/photos/$t2")
    publisher.publishAll()
    val page3 = api("GET", "/_feeds/fetch/photos?after=3").list("entries")
    assertThat(page3.map { it["token"] }).isEqualTo(listOf(t1, t2))
    assertThat(page3[0]["note"]).isEqualTo("edited")
    assertThat(page3[1]["deleted_at"]).isNotNull()
    assertThat(page3.map { it["feed_sync_id"] }).isEqualTo(listOf(4L, 5L))

    // shards partition the feed
    val s0 = api("GET", "/_feeds/fetch/photos?shard=0&shard_count=2").list("entries").map { it["token"] }
    val s1 = api("GET", "/_feeds/fetch/photos?shard=1&shard_count=2").list("entries").map { it["token"] }
    assertThat(s0 + s1).containsExactlyInAnyOrder(t1, t2, t3)

    // server-side cursors
    assertThat(api("GET", "/_feeds/cursors/orders?feed=photos")["cursor"]).isEqualTo(0L)
    api("PUT", "/_feeds/cursors/orders", mapOf("feed" to "photos", "cursor" to 3))
    assertThat(api("GET", "/_feeds/cursors/orders?feed=photos")["cursor"]).isEqualTo(3L)
    val status = api("GET", "/_feeds/status")
    val photos = status.list("feeds").first { it["feed"] == "photos" }
    assertThat(photos).containsEntry("sequence", 5L).containsEntry("pending", 0L).containsEntry("rows", 3L)
    assertThat(status.list("cursors").single()).containsEntry("consumer", "orders").containsEntry("lag", 2L)

    assertThat(api("GET", "/_feeds/fetch/collections").list("entries").map { it["slug"] }).isEqualTo(listOf("pantry", "work"))
    assertThat(call("GET", "/_feeds/fetch/nope").code).isEqualTo(404)
  }

  @Test
  fun `records are a kv with a feed, and identical puts do not republish`() {
    val r = obj(call("PUT", "/api/records/order_items/0012345678905", """{"name":"beans","qty":2,"tags":["a"]}"""))
    assertThat(r["v"]).isEqualTo(mapOf("name" to "beans", "qty" to 2L, "tags" to listOf("a")))
    publisher.publishAll()
    assertThat(api("GET", "/api/records/order_items/0012345678905")["feed_sync_id"]).isEqualTo(1L)

    obj(call("PUT", "/api/records/order_items/0012345678905", """{"name":"beans","qty":2,"tags":["a"]}"""))
    assertThat(publisher.publishAll()).isEqualTo(0)
    obj(call("PUT", "/api/records/order_items/0012345678905", """{"name":"beans","qty":3,"tags":["a"]}"""))
    assertThat(publisher.publishAll()).isEqualTo(1)

    obj(call("PUT", "/api/records/other/k", "\"just a string\""))
    publisher.publishAll()
    assertThat(api("GET", "/api/records").list("namespaces").map { it["ns"] to it["count"] }).isEqualTo(listOf("order_items" to 1L, "other" to 1L))
    assertThat(api("GET", "/api/records/order_items?prefix=0012").list("records")).hasSize(1)
    assertThat(api("GET", "/_feeds/fetch/records?ns=order_items").list("entries").map { (it["v"] as Map<*, *>)["qty"] }).isEqualTo(listOf(3L))

    api("DELETE", "/api/records/order_items/0012345678905")
    assertThat(call("GET", "/api/records/order_items/0012345678905").code).isEqualTo(404)
    publisher.publishAll()
    assertThat(api("GET", "/_feeds/fetch/records?ns=order_items").list("entries").single()["deleted_at"]).isNotNull()
    assertThat(call("PUT", "/api/records/x/y", "{not json").code).isEqualTo(400)
  }

  @Test
  fun `script tokens can be minted, used and revoked`() {
    val created = api("POST", "/api/tokens", mapOf("name" to "pantry-orders"))
    val token = created["token"] as String
    assertThat(token).startsWith("snapz_")
    assertThat(obj(call("GET", "/api/me", token = token))["principal"]).isEqualTo("tester")
    val listed = api("GET", "/api/tokens").list("tokens").single()
    assertThat(listed).containsEntry("name", "pantry-orders").containsEntry("kind", "script").doesNotContainKey("token")
    assertThat(db.tx { c -> c.select("SELECT token_hash FROM api_tokens") { it.getString(1) } }.single()).isNotEqualTo(token).hasSize(64)

    api("DELETE", "/api/tokens/${(created["id"] as Long)}")
    assertThat(call("GET", "/api/me", token = token).code).isEqualTo(401)
  }

  @Test
  fun `token login gives the browser a session cookie, logout revokes it`() {
    val r = call("POST", "/auth/token-login", mapOf("token" to "test-token"), token = null)
    assertThat(r.code).isEqualTo(200)
    val setCookie = r.header("Set-Cookie")!!
    assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax")
    val cookie = setCookie.substringBefore(';')
    assertThat(call("GET", "/api/me", token = null, cookie = cookie).code).isEqualTo(200)
    assertThat(call("POST", "/auth/token-login", mapOf("token" to "wrong"), token = null).code).isEqualTo(401)

    assertThat(call("POST", "/auth/logout", token = null, cookie = cookie).code).isEqualTo(200)
    assertThat(call("GET", "/api/me", token = null, cookie = cookie).code).isEqualTo(401)
  }

  private fun startLogin(query: String): Pair<String, okhttp3.HttpUrl> {
    val r = call("GET", "/auth/login$query", token = null)
    assertThat(r.code).isEqualTo(302)
    val authorize = okhttp3.HttpUrl.Companion.run { r.header("Location")!!.toHttpUrl() }
    assertThat(authorize.toString()).startsWith("${FakeIdp.issuer}/authorize")
    assertThat(authorize.queryParameter("client_id")).isEqualTo("snapz")
    assertThat(authorize.queryParameter("code_challenge_method")).isEqualTo("S256")
    return authorize.queryParameter("state")!! to authorize
  }

  @Test
  fun `oidc web login`() {
    FakeIdp.user = mapOf("sub" to "u1", "email" to "travis@example.com")
    val (state, authorize) = startLogin("?return_to=/%23pantry")
    val cb = call("GET", "/auth/callback?code=c0de&state=$state", token = null)
    assertThat(cb.code).isEqualTo(302)
    assertThat(cb.header("Location")).isEqualTo("/#pantry")
    // the IdP got our secret, the code, and the verifier matching the challenge we sent
    assertThat(FakeIdp.lastTokenForm).containsEntry("client_secret", "s3cret").containsEntry("code", "c0de")
    assertThat(OidcClient.s256(FakeIdp.lastTokenForm["code_verifier"]!!)).isEqualTo(authorize.queryParameter("code_challenge"))
    val cookie = cb.header("Set-Cookie")!!.substringBefore(';')
    assertThat(obj(call("GET", "/api/me", token = null, cookie = cookie))["principal"]).isEqualTo("travis@example.com")

    // state is single-use
    assertThat(call("GET", "/auth/callback?code=c0de&state=$state", token = null).code).isEqualTo(400)
    // open-redirect attempts fall back to /
    val (s2, _) = startLogin("?return_to=//evil.example")
    assertThat(call("GET", "/auth/callback?code=x&state=$s2", token = null).header("Location")).isEqualTo("/")
  }

  @Test
  fun `oidc login is refused for users outside the allowlist, allowed by group`() {
    FakeIdp.user = mapOf("sub" to "u2", "email" to "someone@else.com")
    val (state, _) = startLogin("")
    val denied = call("GET", "/auth/callback?code=x&state=$state", token = null)
    assertThat(denied.code).isEqualTo(403)
    assertThat(denied.header("Set-Cookie")).isNull()

    FakeIdp.user = mapOf("sub" to "u3", "email" to "ally@else.com", "groups" to listOf("snapz-users"))
    val (s2, _) = startLogin("")
    assertThat(call("GET", "/auth/callback?code=x&state=$s2", token = null).code).isEqualTo(302)
  }

  @Test
  fun `ios login ends in a device token bound to the app's verifier`() {
    FakeIdp.user = mapOf("sub" to "u1", "email" to "travis@example.com")
    assertThat(call("GET", "/auth/login?client=ios", token = null).code).isEqualTo(400)
    val verifier = randomSecret(32)
    val (state, _) = startLogin("?client=ios&challenge=${OidcClient.s256(verifier)}")
    val cb = call("GET", "/auth/callback?code=c0de&state=$state", token = null)
    assertThat(cb.header("Set-Cookie")).isNull()
    val location = cb.header("Location")!!
    assertThat(location).startsWith("snapz://auth?code=")
    val code = location.substringAfter("code=")

    // someone who intercepted the redirect but doesn't hold the verifier gets nothing — and burns the code
    assertThat(call("POST", "/auth/device-token", mapOf("code" to code, "verifier" to "wrong"), token = null).code).isEqualTo(401)
    assertThat(call("POST", "/auth/device-token", mapOf("code" to code, "verifier" to verifier), token = null).code).isEqualTo(401)

    val (s2, _) = startLogin("?client=ios&challenge=${OidcClient.s256(verifier)}")
    val code2 = call("GET", "/auth/callback?code=c0de&state=$s2", token = null).header("Location")!!.substringAfter("code=")
    val tok = obj(call("POST", "/auth/device-token", mapOf("code" to code2, "verifier" to verifier, "name" to "Travis iPhone"), token = null))
    assertThat(tok["principal"]).isEqualTo("travis@example.com")
    assertThat(obj(call("GET", "/api/me", token = tok["token"] as String))["principal"]).isEqualTo("travis@example.com")
    assertThat(api("GET", "/api/tokens").list("tokens").map { it["kind"] to it["name"] }).contains("device" to "Travis iPhone")
  }

  @Test
  fun `thumbnails honor exif orientation`() {
    // 40x20 landscape pixels: left half red, right half blue.
    val img = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
    img.createGraphics().apply { paint = Color.RED; fillRect(0, 0, 20, 20); paint = Color.BLUE; fillRect(20, 0, 20, 20); dispose() }
    fun isRed(rgb: Int) = Color(rgb).let { it.red > 150 && it.blue < 100 }

    // 6 = rotate 90° CW to display: the left (red) edge becomes the top.
    val r6 = BlobStore.orient(img, 6)
    assertThat(r6.width to r6.height).isEqualTo(20 to 40)
    assertThat(isRed(r6.getRGB(10, 5))).isTrue()
    assertThat(isRed(r6.getRGB(10, 35))).isFalse()
    // 8 = rotate 90° CCW: red ends up at the bottom.
    val r8 = BlobStore.orient(img, 8)
    assertThat(r8.width to r8.height).isEqualTo(20 to 40)
    assertThat(isRed(r8.getRGB(10, 35))).isTrue()
    // 3 = 180°: red moves to the right.
    val r3 = BlobStore.orient(img, 3)
    assertThat(isRed(r3.getRGB(35, 10))).isTrue()
    assertThat(isRed(r3.getRGB(5, 10))).isFalse()

    // The tag is read from a real APP1 segment (big-endian TIFF, orientation=6) spliced in after SOI.
    val plain = jpeg()
    assertThat(BlobStore.exifOrientation(plain)).isEqualTo(1)
    val tiff = byteArrayOf(0x4d, 0x4d, 0, 0x2a, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0)
    val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
    val app1 = byteArrayOf(0xff.toByte(), 0xe1.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
    val tagged = plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
    assertThat(BlobStore.exifOrientation(tagged)).isEqualTo(6)

    api("POST", "/api/collections", mapOf("name" to "c"))
    val p = upload("c", tagged)
    // jpeg() is 64x48 stored; displayed rotated it's 48x64
    assertThat(p["width"] to p["height"]).isEqualTo(48L to 64L)
  }
}
