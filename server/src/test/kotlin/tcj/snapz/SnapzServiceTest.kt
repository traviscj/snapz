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
import okhttp3.ResponseBody.Companion.toResponseBody
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
      reply(ex, """{"issuer":"$issuer","jwks_uri":"${tcj.oidc.FakeIdp.issuer}/jwks","authorization_endpoint":"$issuer/authorize","token_endpoint":"$issuer/token","userinfo_endpoint":"$issuer/userinfo"}""")
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
    // The corner of Pocket ID's admin API that invitations use.
    server.createContext("/api/") { ex ->
      check(ex.requestHeaders.getFirst("X-API-KEY") == "idp-key") { "wrong api key" }
      val path = ex.requestURI.path
      val body = String(ex.requestBody.readAllBytes())
      fun arr(items: List<String>) = items.joinToString(",", "[", "]")
      fun account(a: Map<String, Any?>) = """{"id":"${a["id"]}","username":"${a["username"]}","email":"${a["email"]}"}"""
      val userGroups = Regex("^/api/users/([^/]+)/(groups|user-groups)$").matchEntire(path)
      val oneTime = Regex("^/api/users/([^/]+)/one-time-access-token$").matchEntire(path)
      when {
        path == "/api/user-groups" -> reply(ex, """{"data":[{"id":"g-users","name":"snapz-users"},{"id":"g-other","name":"other"}]}""")
        path == "/api/users" && ex.requestMethod == "GET" -> reply(ex, """{"data":${arr(accounts.map(::account))}}""")
        path == "/api/users" -> {
          val f = { k: String -> Regex("\"$k\":\"([^\"]*)\"").find(body)?.groupValues?.get(1) }
          val a = mapOf("id" to "acct-${accounts.size + 1}", "username" to f("username"), "email" to f("email"), "displayName" to f("displayName"),
            "groups" to Regex("\"userGroupIds\":\\[([^\\]]*)\\]").find(body)!!.groupValues[1].split(',').map { it.trim('"') }.filter { it.isNotEmpty() })
          accounts.add(a)
          reply(ex, account(a))
        }
        userGroups != null && ex.requestMethod == "GET" -> reply(ex, arr((accounts.first { it["id"] == userGroups.groupValues[1] }["groups"] as List<*>).map { """{"id":"$it"}""" }))
        userGroups != null -> {
          val i = accounts.indexOfFirst { it["id"] == userGroups.groupValues[1] }
          accounts[i] = accounts[i] + ("groups" to Regex("\"userGroupIds\":\\[([^\\]]*)\\]").find(body)!!.groupValues[1].split(',').map { it.trim('"') })
          reply(ex, "{}")
        }
        oneTime != null -> reply(ex, """{"token":"once-${oneTime.groupValues[1]}"}""")
        else -> error("unexpected $path")
      }
    }
    server.start()
  }

  /** Accounts at the fake provider: id, username, email, displayName, groups. */
  val accounts = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
}

class SnapzTestModule : KAbstractModule() {
  override fun configure() {
    // SNAPZ_TEST_CONFIG layers a file over snapz-testing.yaml, e.g. to run the suite against MariaDB.
    val base = MiskConfig.load<SnapzConfig>("snapz", TESTING, listOfNotNull(System.getenv("SNAPZ_TEST_CONFIG")?.takeIf { it.isNotBlank() }?.let { "filesystem:$it" }))
    val config = base.copy(
      snapz = base.snapz.copy(
        oidc = OidcConfig(
          issuer = FakeIdp.issuer, client_id = "snapz", client_secret = "s3cret",
          allowed_emails = listOf("travis@example.com"), allowed_groups = listOf("snapz-users"),
          bearer_audiences = listOf("snapz-cli"), admin_groups = listOf("snapz-admins"),
        ),
        pocket_id = PocketIdConfig(url = FakeIdp.issuer, api_key = "idp-key", users_group = "snapz-users"),
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
  @Inject lateinit var publisher: tcj.feedz.FeedPublisher
  @Inject lateinit var blobs: BlobStore
  @Inject lateinit var runner: TransformRunner
  @Inject lateinit var clock: java.time.Clock

  private val http = OkHttpClient.Builder().followRedirects(false).build()
  private val jsonType = "application/json".toMediaType()

  @BeforeEach
  fun setUp() {
    db.tx { c ->
      for (t in listOf(
        "photos", "collections", "records", "vaults", "api_tokens", "users", "vault_members", "invites", "sequences", "feed_cursors",
        "photo_versions", "transforms", "collection_transforms", "transform_intents", "transform_results",
      )) c.exec("DELETE FROM $t")
    }
    runner.seed()
  }

  // ------------------------------------------------------------------------------ helpers

  private fun url(path: String) = jetty.httpServerUrl.resolve(path)!!

  private fun call(method: String, path: String, body: Any? = null, token: String? = "test-token", cookie: String? = null, raw: ByteArray? = null, vault: String? = null, headers: Map<String, String> = mapOf()): Response {
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
    vault?.let { req.header("X-Snapz-Vault", it) }
    headers.forEach { (k, v) -> req.header(k, v) }
    // Read the whole reply now and hand back a detached copy: most assertions look only at the status,
    // and a reply left half-read on a kept-alive connection is how one test's body turns up in the next.
    return http.newCall(req.build()).execute().use { r ->
      val body = r.body!!
      r.newBuilder().body(body.bytes().toResponseBody(body.contentType())).build()
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun obj(r: Response): Map<String, Any?> {
    val text = r.body!!.string()
    assertThat(r.code).withFailMessage { "HTTP ${r.code}: $text" }.isEqualTo(200)
    return json.parseObject(text)
  }

  private fun api(method: String, path: String, body: Any? = null, vault: String? = null) = obj(call(method, path, body, vault = vault))

  @Suppress("UNCHECKED_CAST")
  private fun Map<String, Any?>.list(key: String) = this[key] as List<Map<String, Any?>>

  private fun jpeg(w: Int = 64, h: Int = 48, color: Color = Color.RED): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    img.createGraphics().apply { paint = color; fillRect(0, 0, w, h); dispose() }
    return ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
  }

  private fun upload(collection: String, bytes: ByteArray, query: String = "") = obj(call("POST", "/api/photos?collection=$collection$query", raw = bytes))

  private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)

  @Suppress("UNCHECKED_CAST")
  private fun meta(photo: Map<String, Any?>) = photo["meta"] as Map<String, Any?>

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

    // URLs the API hands out are pinned to the current bytes and cacheable forever; a bare
    // /blobs/{token} follows the current version and must revalidate.
    assertThat(p["url"] as String).startsWith("/blobs/${p["token"]}?v=")
    assertThat(call("GET", "/blobs/${p["token"]}").header("Cache-Control")).isEqualTo("private, no-cache")
    val full = call("GET", p["url"] as String)
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
    assertThat(api("POST", "/_feeds/publish")["published"]).isEqualTo(6L)   // 3 photos, 2 collections, the default vault

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
    assertThat(status.list("cursors").single()).containsEntry("consumer", "orders").containsEntry("token", "3").containsEntry("lag", 2L).containsEntry("enabled", true)

    // the same cursor through misk-feedz: fetch by consumer, commit the page's cursor
    val mine = api("GET", "/_feeds/fetch/photos?consumer=orders")
    assertThat(mine.list("entries").map { it["token"] }).isEqualTo(listOf(t1, t2))
    assertThat(api("POST", "/_feeds/photos/cursors/orders/commit?token=${mine["cursor"]}")).containsEntry("advanced", true)
    assertThat(api("GET", "/_feeds/cursors/orders?feed=photos")["cursor"]).isEqualTo(5L)
    assertThat(call("PUT", "/_feeds/cursors/orders", mapOf("feed" to "photos", "cursor" to 99)).code).describedAs("past the head").isEqualTo(400)
    // a consumer may rewind itself; an operator may switch it off, and then it is told so
    api("PUT", "/_feeds/cursors/orders", mapOf("feed" to "photos", "cursor" to 0))
    assertThat(api("GET", "/_feeds/cursors/orders?feed=photos")["cursor"]).isEqualTo(0L)
    api("POST", "/_feeds/photos/cursors/orders/disable")
    assertThat(call("GET", "/_feeds/cursors/orders?feed=photos").code).isEqualTo(409)
    assertThat(call("GET", "/_feeds/fetch/photos?consumer=orders").code).isEqualTo(409)
    assertThat(call("PUT", "/_feeds/cursors/orders", mapOf("feed" to "photos", "cursor" to 2)).code).isEqualTo(409)
    assertThat(api("GET", "/_feeds/fetch/photos?after=3").list("entries")).describedAs("stateless fetch is not the cursor's business").hasSize(2)

    assertThat(api("GET", "/_feeds/fetch/collections").list("entries").map { it["slug"] }).isEqualTo(listOf("pantry", "work"))
    assertThat(call("GET", "/_feeds/fetch/nope").code).isEqualTo(404)
  }

  @Test
  fun `a feed's publish switch holds rows back until re-enabled or stepped`() {
    api("POST", "/api/collections", mapOf("name" to "pantry"))
    publisher.publishAll()
    api("POST", "/_feeds/photos/pause")
    val t1 = upload("pantry", jpeg(color = Color.BLUE))["token"]
    assertThat(publisher.publishAll()).isEqualTo(0)
    val st = api("GET", "/_feeds/status").list("feeds").first { it["feed"] == "photos" }
    assertThat(st).containsEntry("publishing_enabled", false).containsEntry("pending", 1L)
    assertThat(api("GET", "/_feeds/fetch/photos").list("entries")).isEmpty()
    // other feeds are unaffected
    api("PATCH", "/api/collections/pantry", mapOf("description" to "x"))
    assertThat(publisher.publishAll()).isEqualTo(1)

    // stepping by hand publishes despite the switch
    assertThat(api("POST", "/_feeds/publish?feed=photos&force=true")["published"]).isEqualTo(1L)
    assertThat(api("GET", "/_feeds/fetch/photos").list("entries").map { it["token"] }).isEqualTo(listOf(t1))

    upload("pantry", jpeg(color = Color.GREEN))
    assertThat(publisher.publishAll()).isEqualTo(0)
    api("POST", "/_feeds/photos/resume")
    assertThat(publisher.publishAll()).isEqualTo(1)
    assertThat(call("POST", "/_feeds/nope/resume").code).isEqualTo(404)
  }

  @Test
  fun `info lists the product enums`() {
    val info = api("GET", "/api/info")
    val names = info.list("enums").map { it["name"] }
    assertThat(names).contains("login mechanism", "token kind", "feed", "batch op", "barcode symbology")
    assertThat((info["settings"] as Map<*, *>)["feed_shards"]).isEqualTo(16L)
    assertThat(info.toString()).doesNotContain("s3cret")
    assertThat(call("GET", "/api/info", token = null).code).isEqualTo(401)
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

    // keys and consumer names are stored decoded, however the client escaped them
    obj(call("PUT", "/api/records/my%20ns/a%20b%3Ac%2Bd", "1"))
    assertThat(api("GET", "/api/records/my%20ns").list("records").single()).containsEntry("ns", "my ns").containsEntry("k", "a b:c+d")
    assertThat(api("GET", "/api/records/my%20ns/a%20b:c%2Bd")["v"]).isEqualTo(1L)
    api("PUT", "/_feeds/cursors/worker%3Agray", mapOf("feed" to "records", "cursor" to 1))
    assertThat(api("GET", "/_feeds/status").list("cursors").map { it["consumer"] }).contains("worker:gray")
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
  fun `the issuer's own JWT is a bearer credential for people on the allowlist`() {
    // Signed with the misk-oidc fixture's key, which this test's issuer publishes as its JWKS.
    fun jwt(vararg claims: Pair<String, Any?>) = tcj.oidc.FakeIdp.token(
      mapOf("iss" to FakeIdp.issuer, "aud" to "snapz", "sub" to "u1", "exp" to clock.instant().epochSecond + 600, "email" to "travis@example.com") + claims,
    )
    val me = obj(call("GET", "/api/me", token = jwt()))
    assertThat(me["principal"]).isEqualTo("travis@example.com")
    assertThat(me["admin"]).describedAs("on the allowlist but not in an admin group").isEqualTo(false)
    assertThat(obj(call("GET", "/api/me", token = jwt("groups" to listOf("snapz-admins"))))["admin"]).isEqualTo(true)
    assertThat(call("GET", "/api/me", token = jwt("aud" to "snapz-cli")).code).describedAs("an extra bearer audience").isEqualTo(200)
    assertThat(call("GET", "/api/me", token = jwt("email" to "ally@else.com", "groups" to listOf("snapz-users"))).code).describedAs("allowed by group").isEqualTo(200)

    assertThat(call("GET", "/api/me", token = jwt("email" to "someone@else.com")).code).describedAs("not on the allowlist").isEqualTo(401)
    assertThat(call("GET", "/api/me", token = jwt("aud" to "some-other-client")).code).describedAs("another client's token").isEqualTo(401)
    assertThat(call("GET", "/api/me", token = jwt("exp" to clock.instant().epochSecond - 3600)).code).describedAs("expired").isEqualTo(401)
    assertThat(call("GET", "/api/me", token = tcj.oidc.FakeIdp.token(mapOf("iss" to FakeIdp.issuer, "aud" to "snapz", "sub" to "u1", "exp" to clock.instant().epochSecond + 600, "email" to "travis@example.com"), signWith = tcj.oidc.FakeIdp.newKey())).code)
      .describedAs("signed by someone else").isEqualTo(401)
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

  /** A white canvas with a real, decodable EAN-13 drawn on it. */
  private fun barcodeJpeg(ean13: String, w: Int = 1200, h: Int = 900): ByteArray {
    val bits = com.google.zxing.MultiFormatWriter().encode(ean13, com.google.zxing.BarcodeFormat.EAN_13, 420, 180)
    val code = com.google.zxing.client.j2se.MatrixToImageWriter.toBufferedImage(bits)
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    img.createGraphics().apply { paint = Color.WHITE; fillRect(0, 0, w, h); drawImage(code, 500, 400, null); dispose() }
    return ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
  }

  private fun setChain(collection: String, vararg transforms: String) =
    api("PUT", "/api/collections/$collection/transforms", mapOf("transforms" to transforms.map { mapOf("transform" to it) }))

  @Test
  fun `a rewrite adds a child version, becomes current, and can be stepped back`() {
    api("POST", "/api/collections", mapOf("name" to "work"))
    val p = upload("work", jpeg(3000, 2000))
    val root = p["current_version"] as String
    val intent = api("POST", "/api/photos/${p["token"]}/intents", mapOf("transform" to "upright-2k"))
    assertThat(intent).containsEntry("state", "pending").containsEntry("origin", "manual").containsEntry("version", root)
    assertThat(intent["input_url"]).isEqualTo("/blobs/${p["token"]}?version=$root")
    assertThat(runner.runPending()).isEqualTo(1)

    val after = api("GET", "/api/photos/${p["token"]}")
    assertThat(after["width"] to after["height"]).isEqualTo(2048L to 1365L)
    assertThat(after["current_version"]).isNotEqualTo(root)
    assertThat(after["url"]).isNotEqualTo(p["url"])                 // the cache-buster moved with the bytes
    assertThat(after["feed_sync_id"]).isNull()                       // and the change is queued for the feed

    val h = api("GET", "/api/photos/${p["token"]}/history")
    val versions = h.list("versions")
    assertThat(versions).hasSize(2)
    assertThat(versions[0]).containsEntry("token", root).containsEntry("current", false).doesNotContainKey("parent")
    assertThat(versions[1]).containsEntry("parent", root).containsEntry("transform", "upright-2k").containsEntry("current", true)
    val done = h.list("intents").single()
    assertThat(done).containsEntry("state", "ok")
    @Suppress("UNCHECKED_CAST")
    assertThat((done["results"] as List<Map<String, Any?>>).single()).containsEntry("output_version", versions[1]["token"]).containsEntry("worker", "builtin")

    // the original is still there, byte for byte, and can be made current again
    val orig = call("GET", "/blobs/${p["token"]}?version=$root")
    assertThat(orig.header("Cache-Control")).contains("immutable")
    assertThat(sha256Hex(orig.body!!.bytes())).isEqualTo(p["sha256"])
    val back = api("PUT", "/api/photos/${p["token"]}/current", mapOf("version" to root))
    assertThat(back).containsEntry("width", 3000L).containsEntry("sha256", p["sha256"]).containsEntry("current_version", root)
    assertThat(call("PUT", "/api/photos/${p["token"]}/current", mapOf("version" to "nope")).code).isEqualTo(404)
    assertThat(call("POST", "/api/photos/${p["token"]}/intents", mapOf("transform" to "nope")).code).isEqualTo(404)
  }

  @Test
  fun `a collection chain runs link by link, each on what the previous one left`() {
    api("POST", "/api/collections", mapOf("name" to "pantry"))
    assertThat(setChain("pantry", "barcodes", "crop-detections").list("transforms").map { it["position"] to it["transform"] })
      .isEqualTo(listOf(0L to "barcodes", 1L to "crop-detections"))
    val p = upload("pantry", barcodeJpeg("0016000275287"))
    // only the first link is queued; the second exists once the first has answered
    assertThat(api("GET", "/api/intents").list("intents").map { it["transform"] }).isEqualTo(listOf("barcodes"))
    assertThat(runner.runPending()).isEqualTo(2)

    val after = api("GET", "/api/photos/${p["token"]}")
    assertThat(after["barcodes"]).isEqualTo(listOf("0016000275287"))
    @Suppress("UNCHECKED_CAST")
    val detected = ((after["meta"] as Map<String, Any?>)["barcodes"] as List<Map<String, Any?>>).single()
    assertThat(detected).containsEntry("symbology", "ean13").containsEntry("source", "server")
    @Suppress("UNCHECKED_CAST")
    val box = detected["box"] as List<Number>
    // drawn at x=500..920, y=400..580 of 1200x900 (quiet zones and the digit row excluded)
    assertThat(box[0].toDouble()).isBetween(0.40, 0.50)
    assertThat(box[1].toDouble()).isBetween(0.43, 0.48)
    assertThat(box[1].toDouble() + box[3].toDouble()).isBetween(0.58, 0.66)

    val h = api("GET", "/api/photos/${p["token"]}/history")
    assertThat(h.list("intents").map { Triple(it["transform"], it["chain_position"], it["state"]) })
      .isEqualTo(listOf(Triple("barcodes", 0L, "ok"), Triple("crop-detections", 1L, "ok")))
    // the crop changes geometry, so it is a side version: listed, but not current
    val crop = h.list("versions").last()
    assertThat(crop).containsEntry("transform", "crop-detections").containsEntry("current", false)
    assertThat((crop["width"] as Long)).isLessThan(1200L)
    assertThat(after["width"]).isEqualTo(1200L)
    assertThat(api("GET", "/api/photos?barcode=0016000275287").list("photos")).hasSize(1)

    // a photo with nothing to find: both links answer, the crop as "skipped"
    val blank = upload("pantry", jpeg(color = Color.WHITE))
    runner.runPending()
    assertThat(api("GET", "/api/photos/${blank["token"]}/history").list("intents").map { it["state"] }).isEqualTo(listOf("ok", "skipped"))
  }

  @Test
  fun `external workers consume the intents feed, and halt stops a chain`() {
    api("POST", "/api/collections", mapOf("name" to "work"))
    api("PUT", "/api/transforms/gate", mapOf("kind" to "extract", "description" to "decides whether to go on"))
    api("PUT", "/api/transforms/sepia", mapOf("kind" to "rewrite"))
    assertThat(call("PUT", "/api/transforms/barcodes", mapOf("kind" to "extract")).code).isEqualTo(400) // built-ins can't be overwritten
    assertThat(api("GET", "/api/transforms").list("transforms").map { it["name"] }).contains("gate", "sepia", "barcodes", "suggest-collection")
    setChain("work", "gate", "sepia", "upright-2k")

    val a = upload("work", jpeg(800, 600, Color.BLUE))
    val b = upload("work", jpeg(800, 600, Color.GREEN))
    assertThat(runner.runPending()).isEqualTo(0)                     // nothing built-in is pending
    publisher.publishAll()
    val page = api("GET", "/_feeds/fetch/intents?transform=gate")
    val (ia, ib) = page.list("entries").also { assertThat(it.map { e -> e["photo"] }).isEqualTo(listOf(a["token"], b["token"])) }
    assertThat(ia).containsEntry("state", "pending").containsEntry("collection", "work")

    // a: the gate says go on, twice (a retried worker) — the chain still advances exactly once
    repeat(2) { api("POST", "/api/intents/${ia["token"]}/results", mapOf("data" to mapOf("ok" to true), "meta_patch" to mapOf("gate" to "passed"), "worker" to "w1")) }
    // b: the gate halts
    api("POST", "/api/intents/${ib["token"]}/results", mapOf("status" to "halt", "worker" to "w1"))
    assertThat(api("GET", "/api/photos/${b["token"]}/history").list("intents").map { it["transform"] to it["state"] }).isEqualTo(listOf("gate" to "halt"))
    assertThat((api("GET", "/api/photos/${a["token"]}")["meta"] as Map<*, *>)["gate"]).isEqualTo("passed")

    // a's next link is an external rewrite: the worker fetches the input and posts an image back
    val sepia = api("GET", "/api/intents?transform=sepia").list("intents").single()
    assertThat(sepia).containsEntry("photo", a["token"]).containsEntry("chain_position", 1L)
    assertThat(call("GET", sepia["input_url"] as String).code).isEqualTo(200)
    val out = obj(call("POST", "/api/intents/${sepia["token"]}/output?worker=w2&data=${enc("""{"tone":"warm"}""")}", raw = jpeg(800, 600, Color.ORANGE)))
    assertThat(out).containsEntry("state", "ok")
    // ...which queues the built-in third link, run against the sepia version
    assertThat(runner.runPending()).isEqualTo(1)
    val h = api("GET", "/api/photos/${a["token"]}/history")
    assertThat(h.list("versions").map { it["transform"] }).isEqualTo(listOf(null, "sepia", "upright-2k"))
    assertThat(h.list("versions")[2]["parent"]).isEqualTo(h.list("versions")[1]["token"])
    assertThat(h.list("intents").map { it["state"] }).isEqualTo(listOf("ok", "ok", "ok"))

    publisher.publishAll()
    val results = api("GET", "/_feeds/fetch/results").list("entries")
    assertThat(results.map { it["transform"] to it["status"] }).containsExactly(
      "gate" to "ok", "gate" to "ok", "gate" to "halt", "sepia" to "ok", "upright-2k" to "ok",
    )
    assertThat(call("POST", "/api/intents/${ia["token"]}/results", mapOf("status" to "bogus")).code).isEqualTo(400)

    // moving a photo starts the destination's chain from the top
    api("POST", "/api/collections", mapOf("name" to "other"))
    setChain("other", "gate")
    api("PATCH", "/api/photos/${b["token"]}", mapOf("collection" to "other"))
    assertThat(api("GET", "/api/photos/${b["token"]}/history").list("intents").map { it["collection"] to it["state"] }.last()).isEqualTo("other" to "pending")
  }

  @Test
  fun `inbox photos are ranked by how similar photos were filed`() {
    for (n in listOf("pantry", "work", "inbox")) api("POST", "/api/collections", mapOf("name" to n))
    setChain("inbox", "suggest-collection")
    val barcode = "&meta=" + enc("""{"source":"ios","barcodes":[{"symbology":"ean13","value":"0016000275287"}]}""")
    val plain = "&meta=" + enc("""{"source":"ios"}""")
    // history: barcoded shots go to the pantry, plain ones to work (all far apart in time)
    val day = 86_400_000L
    repeat(6) { i -> upload("pantry", jpeg(color = Color(i * 30, 0, 0)), "$barcode&taken_at=${1_700_000_000_000 + i * day}") }
    repeat(6) { i -> upload("work", jpeg(color = Color(0, i * 30, 0)), "$plain&taken_at=${1_700_000_000_000 + i * day + 3_600_000}") }

    val scanned = upload("inbox", jpeg(color = Color.MAGENTA), "$barcode&taken_at=${1_700_000_000_000 + 30 * day}")
    val desk = upload("inbox", jpeg(color = Color.CYAN), "$plain&taken_at=${1_700_000_000_000 + 31 * day}")
    val inbox = api("GET", "/api/inbox")
    @Suppress("UNCHECKED_CAST")
    fun ranks(token: Any?) = (inbox.list("photos").first { (it["photo"] as Map<*, *>)["token"] == token }["suggestions"] as List<Map<String, Any?>>)
    assertThat(ranks(scanned["token"]).map { it["collection"] }).isEqualTo(listOf("pantry", "work"))
    assertThat(ranks(desk["token"]).map { it["collection"] }).isEqualTo(listOf("work", "pantry"))
    assertThat(ranks(scanned["token"])[0]["p"] as Double).isGreaterThan(0.7)
    assertThat(ranks(scanned["token"]).sumOf { it["p"] as Double }).isBetween(0.99, 1.01)
    assertThat(ranks(scanned["token"]).map { it["collection"] }).doesNotContain("inbox")

    // the same ranking is recorded as a transform result when the inbox chain runs
    assertThat(runner.runPending()).isEqualTo(2)
    @Suppress("UNCHECKED_CAST")
    val result = (api("GET", "/api/photos/${scanned["token"]}/history").list("intents").single()["results"] as List<Map<String, Any?>>).single()
    @Suppress("UNCHECKED_CAST")
    assertThat(((result["data"] as Map<String, Any?>)["suggestions"] as List<Map<String, Any?>>)[0]["collection"]).isEqualTo("pantry")

    // bursts stick together: a plain photo taken a minute after one just filed under pantry leans pantry
    val t = 1_700_000_000_000 + 40 * day
    upload("pantry", jpeg(color = Color.PINK), "$barcode&taken_at=$t")
    val follow = upload("inbox", jpeg(color = Color.YELLOW), "$plain&taken_at=${t + 60_000}")
    val again = api("GET", "/api/inbox").list("photos").first { (it["photo"] as Map<*, *>)["token"] == follow["token"] }
    assertThat((again["features"] as Map<*, *>)["prev"]).isEqualTo("pantry")

    // filing = a move; it leaves the inbox and enters the destination
    api("PATCH", "/api/photos/${scanned["token"]}", mapOf("collection" to "pantry"))
    assertThat(api("GET", "/api/inbox").list("photos").map { (it["photo"] as Map<*, *>)["token"] }).doesNotContain(scanned["token"])
  }

  // -------------------------------------------------------------------------------- vaults

  @Test
  fun `vaults namespace collections, photos, records and feeds`() {
    assertThat(api("GET", "/api/vaults").list("vaults").map { it["slug"] }).containsExactly("personal")
    val work = api("POST", "/api/vaults", mapOf("name" to "Work", "icon" to "💼"))
    assertThat(work).containsEntry("slug", "work").containsEntry("is_default", false)
    assertThat(call("POST", "/api/vaults", mapOf("name" to "Work")).code).isEqualTo(409)

    // the same slug in two vaults is two collections; no vault named = the default one
    api("POST", "/api/collections", mapOf("name" to "pantry"))
    api("POST", "/api/collections", mapOf("name" to "pantry"), vault = "work")
    val mine = upload("pantry", jpeg(color = Color.RED))
    val theirs = obj(call("POST", "/api/photos?collection=pantry&vault=work", raw = jpeg(color = Color.BLUE)))
    assertThat(mine["vault"]).isEqualTo("personal")
    assertThat(theirs["vault"]).isEqualTo("work")
    assertThat(api("GET", "/api/photos").list("photos").map { it["token"] }).containsExactly(mine["token"])
    assertThat(api("GET", "/api/photos", vault = "work").list("photos").map { it["token"] }).containsExactly(theirs["token"])
    assertThat(api("GET", "/api/collections", vault = "work").list("collections").single()).containsEntry("photo_count", 1L).containsEntry("vault", "work")
    assertThat(call("GET", "/api/collections", vault = "nope").code).isEqualTo(404)

    // records: same (ns, k), different vaults
    obj(call("PUT", "/api/records/order_items/x", "1"))
    obj(call("PUT", "/api/records/order_items/x", "2", vault = "work"))
    assertThat(api("GET", "/api/records/order_items/x")["v"]).isEqualTo(1L)
    assertThat(api("GET", "/api/records/order_items/x", vault = "work")["v"]).isEqualTo(2L)

    // feeds: everything by default (each entry says where it lives), one vault when named
    publisher.publishAll()
    assertThat(api("GET", "/_feeds/fetch/photos").list("entries").map { it["vault"] }).containsExactlyInAnyOrder("personal", "work")
    assertThat(api("GET", "/_feeds/fetch/photos", vault = "work").list("entries").map { it["token"] }).containsExactly(theirs["token"])
    assertThat(api("GET", "/_feeds/fetch/records", vault = "work").list("entries").map { it["v"] }).containsExactly(2L)
    assertThat(api("GET", "/_feeds/fetch/vaults").list("entries").map { it["slug"] }).contains("work")

    // a photo is addressed by token wherever it is; moving it to a collection moves it into the request's vault
    val moved = api("PATCH", "/api/photos/${mine["token"]}", mapOf("collection" to "pantry"), vault = "work")
    assertThat(moved["vault"]).isEqualTo("work")
    assertThat(api("GET", "/api/vaults").list("vaults").first { it["slug"] == "work" }).containsEntry("photo_count", 2L).containsEntry("collection_count", 1L)

    // deleting: not the default, not a non-empty one without force
    assertThat(call("DELETE", "/api/vaults/personal").code).isEqualTo(409)
    assertThat(call("DELETE", "/api/vaults/work").code).isEqualTo(409)
    api("DELETE", "/api/vaults/work?force=true")
    assertThat(api("GET", "/api/vaults").list("vaults").map { it["slug"] }).containsExactly("personal")
    assertThat(api("GET", "/api/photos/${theirs["token"]}")["deleted_at"]).isNotNull()
    api("POST", "/api/vaults", mapOf("name" to "Work"))                               // the slug is free again
  }

  @Test
  fun `a token pinned to a vault sees only that vault and no admin routes`() {
    api("POST", "/api/vaults", mapOf("name" to "work"))
    api("POST", "/api/collections", mapOf("name" to "pantry"))
    api("POST", "/api/collections", mapOf("name" to "desk"), vault = "work")
    val home = upload("pantry", jpeg(color = Color.RED))
    val pinned = api("POST", "/api/tokens", mapOf("name" to "work-bot", "vault" to "work"))["token"] as String
    assertThat(api("GET", "/api/tokens").list("tokens").single()).containsEntry("vault", "work").containsEntry("read_only", false)

    val me = obj(call("GET", "/api/me", token = pinned))
    assertThat(me).containsEntry("vault", "work").containsEntry("admin", false)
    // naming nothing lands in its vault, not the default one
    assertThat(obj(call("GET", "/api/collections", token = pinned)).list("collections").map { it["slug"] }).containsExactly("desk")
    assertThat(obj(call("GET", "/api/vaults", token = pinned)).list("vaults").map { it["slug"] }).containsExactly("work")
    assertThat(call("GET", "/api/collections", token = pinned, vault = "personal").code).isEqualTo(403)
    // photos elsewhere don't exist for it, by token or as bytes
    assertThat(call("GET", "/api/photos/${home["token"]}", token = pinned).code).isEqualTo(404)
    assertThat(call("GET", "/blobs/${home["token"]}", token = pinned).code).isEqualTo(404)
    assertThat(call("POST", "/api/photos/batch", mapOf("op" to "move", "tokens" to listOf(home["token"]), "collection" to "desk"), token = pinned).code).isEqualTo(404)
    val own = obj(call("POST", "/api/photos?collection=desk", token = pinned, raw = jpeg(color = Color.GREEN)))
    assertThat(call("GET", "/blobs/${own["token"]}", token = pinned).code).isEqualTo(200)
    // its feed is its vault
    publisher.publishAll()
    assertThat(obj(call("GET", "/_feeds/fetch/photos", token = pinned)).list("entries").map { it["token"] }).containsExactly(own["token"])
    // admin routes are off
    for ((m, path) in listOf("GET" to "/api/tokens", "POST" to "/api/vaults", "POST" to "/api/admin/purge")) {
      assertThat(call(m, path, if (m == "POST") mapOf("name" to "x") else null, token = pinned).code).withFailMessage { "$m $path" }.isEqualTo(403)
    }
    // a browser session started from it inherits the pin
    val cookie = call("POST", "/auth/token-login", mapOf("token" to pinned), token = null).header("Set-Cookie")!!.substringBefore(';')
    assertThat(obj(call("GET", "/api/me", token = null, cookie = cookie))).containsEntry("vault", "work")
    // and the credential dies with the vault
    api("DELETE", "/api/vaults/work?force=true")
    assertThat(call("GET", "/api/me", token = pinned).code).isEqualTo(401)
  }

  @Test
  fun `read-only and expiring tokens`() {
    api("POST", "/api/collections", mapOf("name" to "pantry"))
    val ro = api("POST", "/api/tokens", mapOf("name" to "dashboard", "read_only" to true, "ttl_days" to 7))
    val token = ro["token"] as String
    val listed = api("GET", "/api/tokens").list("tokens").single()
    assertThat(listed).containsEntry("read_only", true)
    assertThat(listed["expires_at"]).isNotNull()
    assertThat(obj(call("GET", "/api/me", token = token))).containsEntry("read_only", true).containsEntry("admin", false)
    assertThat(call("GET", "/api/collections", token = token).code).isEqualTo(200)
    assertThat(call("POST", "/api/collections", mapOf("name" to "x"), token = token).code).isEqualTo(403)
    assertThat(call("POST", "/api/photos?collection=pantry", token = token, raw = jpeg()).code).isEqualTo(403)
    assertThat(call("DELETE", "/api/collections/pantry", token = token).code).isEqualTo(403)
    // the one write a feed consumer needs
    assertThat(call("PUT", "/_feeds/cursors/dash", mapOf("feed" to "photos", "cursor" to 0), token = token).code).isEqualTo(200)
    assertThat(call("POST", "/_feeds/photos/cursors/dash/commit?token=0", token = token).code).isEqualTo(200)
    assertThat(call("POST", "/_feeds/photos/cursors/dash/seek?token=0", token = token).code).describedAs("operator action").isEqualTo(403)
    assertThat(call("POST", "/api/tokens", mapOf("name" to "x", "ttl_days" to 0)).code).isEqualTo(400)
    db.tx { c -> c.exec("UPDATE api_tokens SET expires_at_ms = 1") }
    assertThat(call("GET", "/api/me", token = token).code).isEqualTo(401)
  }

  @Test
  fun `a chain can be backfilled over photos that were already there`() {
    api("POST", "/api/collections", mapOf("name" to "pantry"))
    val a = upload("pantry", jpeg(color = Color.RED))
    upload("pantry", jpeg(color = Color.BLUE))
    setChain("pantry", "upright-2k")
    assertThat(runner.runPending()).isEqualTo(0)                                       // setting a chain runs nothing
    assertThat(api("POST", "/api/collections/pantry/transforms/run")["started"]).isEqualTo(2L)
    assertThat(runner.runPending()).isEqualTo(2)
    assertThat(api("GET", "/api/photos/${a["token"]}/history").list("versions")).hasSize(2)
    assertThat(api("POST", "/api/collections/pantry/transforms/run")["started"]).isEqualTo(0L)   // only untouched photos
    assertThat(api("POST", "/api/collections/pantry/transforms/run?all=true")["started"]).isEqualTo(2L)
  }

  @Test
  fun `a collection's action set is the verbs a photo can be filed with`() {
    for (n in listOf("pantry", "food", "inbox")) api("POST", "/api/collections", mapOf("name" to n))
    val actions = listOf(mapOf("name" to "consume", "label" to "USE", "qty" to true), mapOf("name" to "out"), mapOf("name" to "purchase", "qty" to true))
    val c = api("PUT", "/api/collections/pantry/actions", mapOf("actions" to actions))
    assertThat(c.list("actions").map { it["name"] }).containsExactly("consume", "out", "purchase")
    assertThat(c.list("actions")[0]).containsEntry("label", "USE").containsEntry("qty", true)
    assertThat(api("GET", "/api/collections/food").list("actions")).isEmpty()
    assertThat(call("PUT", "/api/collections/pantry/actions", mapOf("actions" to listOf(mapOf("name" to "Not OK")))).code).isEqualTo(400)

    // filing = collection + verb; a verb outside the destination's set is refused
    val p = upload("inbox", jpeg(color = Color.RED))
    assertThat(call("PATCH", "/api/photos/${p["token"]}", mapOf("collection" to "pantry", "action" to "eat")).code).isEqualTo(400)
    assertThat(call("PATCH", "/api/photos/${p["token"]}", mapOf("collection" to "food", "action" to "consume")).code).isEqualTo(400)
    val filed = api("PATCH", "/api/photos/${p["token"]}", mapOf("collection" to "pantry", "action" to "consume", "qty" to 2))
    assertThat(meta(filed)).containsEntry("action", "consume").containsEntry("qty", 2L)
    // changing the verb in place; one without a quantity drops qty; "" clears
    assertThat(meta(api("PATCH", "/api/photos/${p["token"]}", mapOf("action" to "out")))).containsEntry("action", "out").doesNotContainKey("qty")
    assertThat(meta(api("PATCH", "/api/photos/${p["token"]}", mapOf("action" to "")))).doesNotContainKey("action")
    // batch move files several at once; leaving the collection drops the verb
    val q = upload("inbox", jpeg(color = Color.BLUE))
    val moved = api("POST", "/api/photos/batch", mapOf("op" to "move", "tokens" to listOf(p["token"], q["token"]), "collection" to "pantry", "action" to "purchase", "qty" to 3))
    assertThat(moved.list("photos").map { (it["meta"] as Map<*, *>)["action"] }).containsExactly("purchase", "purchase")
    assertThat(meta(api("PATCH", "/api/photos/${q["token"]}", mapOf("collection" to "food")))).doesNotContainKey("action").doesNotContainKey("qty")

    // the inbox ranks a destination's verbs too
    val r = upload("inbox", jpeg(color = Color.GREEN))
    @Suppress("UNCHECKED_CAST")
    val sugg = api("GET", "/api/inbox").list("photos").single { (it["photo"] as Map<*, *>)["token"] == r["token"] }["suggestions"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val ranked = sugg.first { it["collection"] == "pantry" }["actions"] as List<Map<String, Any?>>
    assertThat(ranked.map { it["action"] }).containsExactlyInAnyOrder("consume", "out", "purchase")
    assertThat(ranked.first()["action"]).isEqualTo("purchase")                       // the only verb pantry photos carry so far
    assertThat(sugg.first { it["collection"] == "food" }).doesNotContainKey("actions")
  }

  @Test
  fun `each vault has its own inbox and its own ranking`() {
    api("POST", "/api/vaults", mapOf("name" to "work"))
    for (n in listOf("pantry", "inbox")) api("POST", "/api/collections", mapOf("name" to n))
    for (n in listOf("receipts", "inbox")) api("POST", "/api/collections", mapOf("name" to n), vault = "work")
    upload("inbox", jpeg(color = Color.RED))
    val w = obj(call("POST", "/api/photos?collection=inbox", raw = jpeg(color = Color.BLUE), vault = "work"))
    val inbox = api("GET", "/api/inbox", vault = "work").list("photos")
    assertThat(inbox.map { (it["photo"] as Map<*, *>)["token"] }).containsExactly(w["token"])
    @Suppress("UNCHECKED_CAST")
    assertThat((inbox.single()["suggestions"] as List<Map<String, Any?>>).map { it["collection"] }).containsExactly("receipts")
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

  @Test
  fun `security headers, cross-site cookie writes refused, weak deployments refused`() {
    // Every reply says what the page may load; image bytes are sandboxed.
    val page = call("GET", "/", token = null)
    assertThat(page.header("X-Content-Type-Options")).isEqualTo("nosniff")
    assertThat(page.header("X-Frame-Options")).isEqualTo("DENY")
    assertThat(page.header("Content-Security-Policy")).contains("frame-ancestors 'none'").contains("connect-src 'self'")
    assertThat(page.header("Strict-Transport-Security")).isNull()   // this test server is plain http
    assertThat(call("GET", "/api/me", token = null).header("X-Content-Type-Options")).isEqualTo("nosniff")   // 401s too
    api("POST", "/api/collections", mapOf("name" to "c"))
    val p = upload("c", jpeg())
    val blob = call("GET", p["url"] as String)
    assertThat(blob.code).isEqualTo(200)
    assertThat(blob.header("Content-Security-Policy")).contains("sandbox")
    assertThat(blob.header("X-Content-Type-Options")).isEqualTo("nosniff")

    // A cookie session: same-origin writes work, writes a browser labels cross-site do not.
    val login = call("POST", "/auth/token-login", mapOf("token" to "test-token"), token = null)
    val cookie = login.header("Set-Cookie")!!.substringBefore(';')
    fun write(headers: Map<String, String>, token: String? = null) =
      call("POST", "/api/collections", mapOf("name" to "x${headers.hashCode()}${token != null}"), token = token, cookie = cookie, headers = headers).code
    assertThat(write(mapOf())).isEqualTo(200)
    assertThat(write(mapOf("Sec-Fetch-Site" to "same-origin"))).isEqualTo(200)
    assertThat(write(mapOf("Sec-Fetch-Site" to "cross-site"))).isEqualTo(401)
    assertThat(write(mapOf("Sec-Fetch-Site" to "same-site"))).isEqualTo(401)
    assertThat(write(mapOf("Origin" to "https://evil.example"))).isEqualTo(401)
    assertThat(write(mapOf("Origin" to "http://${url("/").host}:${url("/").port}"))).isEqualTo(200)
    // Reads are not writes, and a bearer token is not a browser.
    assertThat(call("GET", "/api/me", token = null, cookie = cookie, headers = mapOf("Sec-Fetch-Site" to "cross-site")).code).isEqualTo(200)
    assertThat(write(mapOf("Sec-Fetch-Site" to "cross-site"), token = "test-token")).isEqualTo(200)

    // Posture: a short static token is tolerated on a dev box and fatal on an https deployment.
    val base = MiskConfig.load<SnapzConfig>("snapz", TESTING, listOf<String>())
    fun with(url: String, token: String) = base.copy(snapz = base.snapz.copy(public_url = url, bootstrap_tokens = listOf(BootstrapToken(token, "x"))))
    assertThat(Posture.check(with("http://localhost:8788", "dev-token")).none { it.fatal }).isTrue()
    assertThat(Posture.check(with("https://snapz.example.net", "dev-token")).any { it.fatal }).isTrue()
    assertThat(Posture.check(with("https://snapz.example.net", "x".repeat(40))).none { it.fatal }).isTrue()
    assertThat(Posture.check(with("https://snapz.example.net", "x".repeat(40))).map { it.message }).anyMatch { it.contains("bootstrap token(s) in config") }
  }

  @Test
  @Suppress("UNCHECKED_CAST")
  fun `receive rebuilds a server from another one's feeds, idempotently, in any order`() {
    // A source with a bit of everything.
    api("POST", "/api/vaults", mapOf("name" to "work"))
    api("POST", "/api/collections", mapOf("name" to "pantry"))
    api("PUT", "/api/collections/pantry/actions", mapOf("actions" to listOf(mapOf("name" to "consume", "label" to "USE", "qty" to true), mapOf("name" to "out"))))
    api("POST", "/api/collections", mapOf("name" to "desk"), vault = "work")
    val a = upload("pantry", jpeg(color = Color.RED), "&note=beans&meta=" + java.net.URLEncoder.encode("""{"barcodes":[{"symbology":"ean13","value":"0123456789012"}],"n":3}""", "UTF-8"))
    val b = upload("pantry", jpeg(color = Color.BLUE))
    val w = obj(call("POST", "/api/photos?collection=desk", raw = jpeg(color = Color.GREEN), vault = "work"))
    api("PATCH", "/api/photos/${a["token"]}", mapOf("action" to "consume", "qty" to 2))
    api("DELETE", "/api/photos/${b["token"]}")
    api("PUT", "/api/records/stock/0123456789012", mapOf("qty" to 4, "name" to "beans"))
    api("PUT", "/api/records/notes/x", mapOf("a" to listOf(1, 2)), vault = "work")
    publisher.publishAll()

    fun feed(name: String) = api("GET", "/_feeds/fetch/$name?limit=1000")["entries"] as List<Map<String, Any?>>
    val feeds = listOf("vaults", "collections", "photos", "records").associateWith(::feed)
    assertThat(feeds.getValue("photos")).hasSize(3)

    // What a reader of the API sees; volatile bookkeeping aside, this must survive the trip.
    fun strip(m: Map<String, Any?>) = m - setOf("feed_sync_id", "current_version", "client_id")
    fun state(): Map<String, Any?> = mapOf(
      "photos" to listOf("personal", "work").flatMap { v ->
        (api("GET", "/api/photos?limit=500", vault = v).list("photos") + api("GET", "/api/photos?limit=500&deleted=true", vault = v).list("photos")).map(::strip)
      }.sortedBy { it["token"] as String },
      "collections" to listOf("personal", "work").flatMap { v -> api("GET", "/api/collections", vault = v).list("collections").map(::strip) },
      "stock" to api("GET", "/api/records/stock").list("records").map(::strip),
      "notes" to api("GET", "/api/records/notes", vault = "work").list("records").map(::strip),
    )
    val before = state()

    // An empty server (the default vault and its inbox exist, as on any fresh install), one blob short.
    val lost = b["sha256"] as String
    val lostBytes = blobs.original(lost).readBytes()
    setUp()
    assertThat(blobs.original(lost).delete()).isTrue()

    fun receive(name: String, entries: List<Map<String, Any?>> = feeds.getValue(name)) = api("POST", "/_feeds/receive/$name", mapOf("entries" to entries))
    // Children before parents: photos and records first, vaults last.
    val first = receive("photos")
    assertThat(first["applied"]).isEqualTo(2L)
    assertThat(first.list("missing_blobs").map { it["sha256"] }).containsExactly(lost)
    // The bytes are checked against the name they are pushed under.
    assertThat(call("PUT", "/_feeds/blobs/$lost", raw = jpeg(color = Color.YELLOW)).code).isEqualTo(400)
    assertThat(call("PUT", "/_feeds/blobs/$lost", raw = lostBytes).code).isEqualTo(200)
    assertThat(call("GET", "/_feeds/blobs/$lost").body!!.bytes()).isEqualTo(lostBytes)
    val second = receive("photos")
    assertThat(second["applied"] to second["unchanged"]).isEqualTo(1L to 2L)
    receive("records")
    receive("collections")
    receive("vaults")
    assertThat(state()).isEqualTo(before)

    // Delivered again: nothing is written, so nothing is republished.
    publisher.publishAll()
    for (name in feeds.keys) {
      val again = receive(name)
      assertThat(again["applied"]).withFailMessage { "$name: $again" }.isEqualTo(0L)
    }
    assertThat(publisher.publishAll()).isEqualTo(0)

    // Last writer wins: an older entry loses to the local row, a newer one replaces it.
    val entry = feeds.getValue("photos").first { it["token"] == a["token"] }
    val older = entry + mapOf("note" to "stale", "updated_at" to "2001-01-01T00:00:00Z")
    assertThat(receive("photos", listOf(older))["stale"]).isEqualTo(1L)
    assertThat(api("GET", "/api/photos/${a["token"]}")["note"]).isEqualTo("beans")
    val newer = entry + mapOf("note" to "fresh", "collection" to "elsewhere", "updated_at" to "2100-01-01T00:00:00Z")
    assertThat(receive("photos", listOf(newer))["applied"]).isEqualTo(1L)
    val moved = api("GET", "/api/photos/${a["token"]}")
    assertThat(listOf(moved["note"], moved["collection"], moved["updated_at"])).containsExactly("fresh", "elsewhere", "2100-01-01T00:00:00Z")

    // A collection deleted at the source arrives under its tombstone slug and takes the live one here.
    val pantry = feeds.getValue("collections").first { it["slug"] == "pantry" }
    receive("collections", listOf(pantry + mapOf("slug" to "pantry.deleted.99", "deleted_at" to "2100-01-01T00:00:00Z", "updated_at" to "2100-01-01T00:00:00Z")))
    assertThat(api("GET", "/api/collections").list("collections").map { it["slug"] }).doesNotContain("pantry")

    // Sink endpoints are admin-only, and only replicated tables have one.
    val pinned = api("POST", "/api/tokens", mapOf("name" to "work-bot", "vault" to "work"))["token"] as String
    assertThat(call("POST", "/_feeds/receive/records", mapOf("entries" to listOf<Any>()), token = pinned).code).isEqualTo(403)
    assertThat(call("GET", "/_feeds/blobs/$lost", token = pinned).code).isEqualTo(403)
    val reader = api("POST", "/api/tokens", mapOf("name" to "replicator", "read_only" to true))["token"] as String
    assertThat(call("GET", "/_feeds/blobs/$lost", token = reader).code).isEqualTo(200)
    assertThat(call("PUT", "/_feeds/blobs/$lost", raw = lostBytes, token = reader).code).isEqualTo(403)
    assertThat(call("POST", "/_feeds/receive/intents", mapOf("entries" to listOf<Any>())).code).isEqualTo(400)
  }

  @Test
  fun `admin is a group at the identity provider, and gates misk's dashboard too`() {
    // Signed in through the provider without the admin group: a user, not an admin.
    FakeIdp.user = mapOf("sub" to "u1", "email" to "travis@example.com", "groups" to listOf("snapz-users"))
    val (s1, _) = startLogin("")
    val plain = call("GET", "/auth/callback?code=c0de&state=$s1", token = null).header("Set-Cookie")!!.substringBefore(';')
    assertThat(obj(call("GET", "/api/me", token = null, cookie = plain))).containsEntry("admin", false)
    assertThat(call("GET", "/api/collections", token = null, cookie = plain).code).isEqualTo(200)
    assertThat(call("GET", "/api/tokens", token = null, cookie = plain).code).isEqualTo(403)
    assertThat(call("POST", "/api/vaults", mapOf("name" to "nope"), token = null, cookie = plain).code).isEqualTo(403)
    assertThat(call("GET", "/_admin/", token = null, cookie = plain).code).isEqualTo(403)

    // In the admin group at sign-in: the session carries it.
    FakeIdp.user = mapOf("sub" to "u1", "email" to "travis@example.com", "groups" to listOf("snapz-users", "snapz-admins"))
    val (s2, _) = startLogin("")
    val admin = call("GET", "/auth/callback?code=c0de&state=$s2", token = null).header("Set-Cookie")!!.substringBefore(';')
    assertThat(obj(call("GET", "/api/me", token = null, cookie = admin))).containsEntry("admin", true)
    assertThat(call("GET", "/api/tokens", token = null, cookie = admin).code).isEqualTo(200)
    val dash = call("GET", "/_admin/", token = null, cookie = admin)
    assertThat(dash.code).isEqualTo(200)
    assertThat(dash.header("Content-Security-Policy")).contains("'self'")

    // Nobody signed in gets nothing; tokens minted by an admin keep working as before.
    assertThat(call("GET", "/_admin/", token = null).code).isEqualTo(401)
    assertThat(call("GET", "/no/such/page", token = null).code).describedAs("misk's catch-all is gated too").isEqualTo(401)
    assertThat(call("GET", "/_status", token = null).code).isEqualTo(200)
    assertThat(call("GET", "/_admin/").code).isEqualTo(200)
    val pinned = api("POST", "/api/tokens", mapOf("name" to "work-bot", "vault" to "personal"))["token"] as String
    assertThat(call("GET", "/_admin/", token = pinned).code).isEqualTo(403)
  }

  @Test
  fun `members see their own vaults and what is shared with them, operators see everything`() {
    // The operator (an admin-minted token) has a photo and a record in the default vault.
    api("POST", "/api/collections", mapOf("name" to "pantry"))
    val theirs = upload("pantry", jpeg(color = Color.RED))
    api("PUT", "/api/records/stock/beans", mapOf("qty" to 4))

    // Ally signs in through the identity provider: allowed, not in the admin group.
    FakeIdp.user = mapOf("sub" to "u3", "email" to "Ally@Else.com", "groups" to listOf("snapz-users"))
    val (state, _) = startLogin("")
    val ally = call("GET", "/auth/callback?code=c0de&state=$state", token = null).header("Set-Cookie")!!.substringBefore(';')
    fun asAlly(method: String, path: String, body: Any? = null, vault: String? = null, raw: ByteArray? = null) = call(method, path, body, token = null, cookie = ally, vault = vault, raw = raw)
    assertThat(obj(asAlly("GET", "/api/me"))).containsEntry("admin", false).containsEntry("operator", false)

    // She gets a vault of her own, and it is all she can see.
    val vaults = obj(asAlly("GET", "/api/vaults"))
    assertThat(vaults["default"]).isEqualTo("ally")
    assertThat(vaults.list("vaults").map { listOf(it["slug"], it["role"], it["is_default"]) }).containsExactly(listOf("ally", "owner", true))
    val allyToken = vaults.list("vaults").single()["token"] as String
    assertThat(allyToken).startsWith("v")
    assertThat(obj(asAlly("GET", "/api/collections")).list("collections")).isEmpty()
    assertThat(asAlly("POST", "/api/collections", mapOf("name" to "mine")).code).isEqualTo(200)
    val hers = obj(asAlly("POST", "/api/photos?collection=mine", raw = jpeg(color = Color.BLUE)))
    assertThat(hers["vault"]).isEqualTo("ally")
    assertThat(obj(asAlly("GET", "/api/photos")).list("photos").map { it["token"] }).containsExactly(hers["token"])
    assertThat(obj(asAlly("GET", "/api/collections", vault = allyToken)).list("collections").map { it["slug"] }).containsExactly("mine")   // by token too
    assertThat(obj(asAlly("GET", "/api/records")).list("namespaces")).isEmpty()

    // The operator's vault does not exist for her, by name or through anything named by token.
    val t = theirs["token"]
    assertThat(asAlly("GET", "/api/collections", vault = "personal").code).isEqualTo(404)
    assertThat(asAlly("GET", "/api/photos?vault=personal").code).isEqualTo(404)
    for ((m, path) in listOf("GET" to "/api/photos/$t", "GET" to "/blobs/$t", "GET" to "/api/photos/$t/history", "DELETE" to "/api/photos/$t", "POST" to "/api/photos/$t/restore")) {
      assertThat(asAlly(m, path).code).describedAs("$m $path").isEqualTo(404)
    }
    assertThat(asAlly("PATCH", "/api/photos/$t", mapOf("note" to "hi")).code).isEqualTo(404)
    assertThat(asAlly("POST", "/api/photos/$t/copy", mapOf("collection" to "mine")).code).isEqualTo(404)
    assertThat(asAlly("POST", "/api/photos/batch", mapOf("op" to "move", "tokens" to listOf(t), "collection" to "mine")).code).isEqualTo(404)
    assertThat(asAlly("POST", "/api/photos/$t/intents", mapOf("transform" to "barcodes")).code).isEqualTo(404)

    // Feeds carry her vault only; shared, server-wide state is not hers to touch.
    publisher.publishAll()
    assertThat(obj(asAlly("GET", "/_feeds/fetch/photos?limit=100")).list("entries").map { it["token"] }).containsExactly(hers["token"])
    assertThat(obj(asAlly("GET", "/_feeds/fetch/vaults?limit=100")).list("entries").map { it["slug"] }).containsExactly("ally")
    assertThat(api("GET", "/_feeds/fetch/photos?limit=100").list("entries")).hasSize(2)   // the operator: everything
    for ((m, path, body) in listOf(
      Triple("GET", "/_feeds/fetch/photos?consumer=x", null),
      Triple("GET", "/_feeds/cursors/x?feed=photos", null),
      Triple("PUT", "/_feeds/cursors/x", mapOf("feed" to "photos", "cursor" to 1)),
      Triple("POST", "/_feeds/photos/cursors/x/commit", mapOf("cursor" to 1)),
      Triple("GET", "/_feeds/blobs/${theirs["sha256"]}", null),
      Triple("POST", "/_feeds/receive/records", mapOf("entries" to listOf<Any>())),
      Triple("GET", "/api/tokens", null),
      Triple("POST", "/api/vaults", mapOf("name" to "more")),
      Triple("PUT", "/api/transforms/mine", mapOf("kind" to "extract")),
      Triple("POST", "/api/transforms/run", null),
      Triple("GET", "/_admin/", null),
    )) {
      assertThat(asAlly(m, path, body).code).describedAs("$m $path").isEqualTo(403)
    }

    // The operator sees her vault like any other.
    assertThat(api("GET", "/api/photos", vault = "ally").list("photos").map { it["token"] }).containsExactly(hers["token"])
    assertThat(api("GET", "/api/vaults").list("vaults").map { it["slug"] }).containsExactlyInAnyOrder("personal", "ally")

    // Shared with her to view: she can look, by name and by token, and cannot change anything.
    val shared = api("PUT", "/api/vaults/personal/members", mapOf("principal" to "ally@else.com", "role" to "viewer"))
    assertThat(shared.list("members").map { it["principal"] to it["role"] }).containsExactly("ally@else.com" to "viewer")
    assertThat(obj(asAlly("GET", "/api/vaults")).list("vaults").map { it["slug"] to it["role"] }).containsExactlyInAnyOrder("ally" to "owner", "personal" to "viewer")
    assertThat(obj(asAlly("GET", "/api/photos", vault = "personal")).list("photos").map { it["token"] }).containsExactly(t)
    assertThat(asAlly("GET", "/api/photos/$t").code).isEqualTo(200)
    assertThat(asAlly("GET", "/blobs/$t").code).isEqualTo(200)
    assertThat(obj(asAlly("GET", "/api/records/stock/beans", vault = "personal"))["v"]).isEqualTo(mapOf("qty" to 4L))
    assertThat(asAlly("PATCH", "/api/photos/$t", mapOf("note" to "hi")).code).isEqualTo(403)
    assertThat(asAlly("POST", "/api/photos?collection=pantry", raw = jpeg(), vault = "personal").code).isEqualTo(403)
    assertThat(asAlly("PUT", "/api/records/stock/beans", mapOf("qty" to 0), vault = "personal").code).isEqualTo(403)
    assertThat(asAlly("PUT", "/api/vaults/personal/members", mapOf("principal" to "bob@x.com", "role" to "viewer")).code).describedAs("not hers to share").isEqualTo(403)
    // Requests that name no vault still land in her own.
    assertThat(obj(asAlly("GET", "/api/photos")).list("photos").map { it["token"] }).containsExactly(hers["token"])

    // As an editor she can change things there.
    api("PUT", "/api/vaults/personal/members", mapOf("principal" to "ally@else.com", "role" to "editor"))
    assertThat(asAlly("PATCH", "/api/photos/$t", mapOf("note" to "hi")).code).isEqualTo(200)

    // She shares her own vault with someone who has not signed in yet; a vault keeps an owner.
    val mine = obj(asAlly("PUT", "/api/vaults/ally/members", mapOf("principal" to "bob@x.com", "role" to "editor")))
    assertThat(mine.list("members").map { it["principal"] to it["role"] }).containsExactly("ally@else.com" to "owner", "bob@x.com" to "editor")
    assertThat(asAlly("DELETE", "/api/vaults/ally/members?principal=ally@else.com").code).isEqualTo(409)
    assertThat(asAlly("PUT", "/api/vaults/ally/members", mapOf("principal" to "ally@else.com", "role" to "viewer")).code).isEqualTo(409)

    // Taken away again: gone, as if it never was.
    assertThat(call("DELETE", "/api/vaults/personal/members?principal=ally@else.com").code).isEqualTo(200)
    assertThat(asAlly("GET", "/api/photos/$t").code).isEqualTo(404)
    assertThat(asAlly("GET", "/api/photos?vault=personal").code).isEqualTo(404)
  }

  @Test
  fun `an invitation makes the account, shares the vault and hands over a link`() {
    FakeIdp.accounts.clear()
    api("POST", "/api/collections", mapOf("name" to "pantry"))
    val photo = upload("pantry", jpeg())

    val inv = api("POST", "/api/vaults/personal/invites", mapOf("email" to "JD.Beals@Example.com", "role" to "viewer", "name" to "JD Beals"))
    assertThat(inv).containsEntry("email", "jd.beals@example.com").containsEntry("vault", "personal").containsEntry("role", "viewer").containsEntry("account", "created")
    val code = inv["code"] as String
    assertThat(inv["url"] as String).endsWith("/invite/$code")
    // An account in the group that may sign in, and a place in the vault waiting for them.
    assertThat(FakeIdp.accounts).hasSize(1)
    assertThat(FakeIdp.accounts[0]).containsEntry("email", "jd.beals@example.com").containsEntry("username", "jd.beals").containsEntry("displayName", "JD Beals").containsEntry("groups", listOf("g-users"))
    assertThat(api("GET", "/api/vaults/personal/members").list("members").map { it["principal"] to it["role"] }).containsExactly("jd.beals@example.com" to "viewer")

    // The page the link opens needs no sign-in (they cannot sign in yet) and leads to a one-time sign-in.
    val page = call("GET", "/invite/$code", token = null)
    assertThat(page.code).isEqualTo(200)
    assertThat(page.header("Content-Security-Policy")).contains("frame-ancestors 'none'")
    assertThat(page.body!!.string()).contains("You're invited", "jd.beals@example.com", "/invite/$code/setup", "/auth/login?return_to=")
    val setup = call("GET", "/invite/$code/setup", token = null)
    assertThat(setup.code).isEqualTo(302)
    assertThat(setup.header("Location")).isEqualTo("${FakeIdp.issuer}/lc/once-acct-1")
    assertThat(call("GET", "/invite/nope", token = null).body!!.string()).contains("does not exist")
    assertThat(call("GET", "/invite/nope/setup", token = null).code).isEqualTo(404)

    // They register a passkey and sign in: the shared vault and one of their own.
    FakeIdp.user = mapOf("sub" to "acct-1", "email" to "jd.beals@example.com", "groups" to listOf("snapz-users"))
    val (state, _) = startLogin("")
    val beals = call("GET", "/auth/callback?code=c0de&state=$state", token = null).header("Set-Cookie")!!.substringBefore(';')
    assertThat(obj(call("GET", "/api/vaults", token = null, cookie = beals)).list("vaults").map { it["slug"] to it["role"] })
      .containsExactlyInAnyOrder("personal" to "viewer", "jd-beals" to "owner")
    assertThat(obj(call("GET", "/api/photos", token = null, cookie = beals, vault = "personal")).list("photos").map { it["token"] }).containsExactly(photo["token"])
    assertThat(call("POST", "/api/vaults/personal/invites", mapOf("email" to "x@y.zz"), token = null, cookie = beals).code).describedAs("inviting is the operator's").isEqualTo(403)

    // Used: the link no longer signs anyone in.
    assertThat(call("GET", "/invite/$code", token = null).body!!.string()).contains("already set up")
    assertThat(call("GET", "/invite/$code/setup", token = null).code).isEqualTo(404)
    assertThat(api("GET", "/api/vaults/personal/invites").list("invites").map { it["email"] to (it["accepted_at"] != null) }).containsExactly("jd.beals@example.com" to true)

    // Inviting someone who already has an account (here, with other groups) keeps the account and adds the group.
    FakeIdp.accounts.add(mapOf("id" to "acct-9", "username" to "val", "email" to "val@example.com", "groups" to listOf("g-other")))
    val again = api("POST", "/api/vaults/personal/invites", mapOf("email" to "val@example.com", "role" to "editor"))
    assertThat(again).containsEntry("account", "existing")
    assertThat(FakeIdp.accounts.first { it["id"] == "acct-9" }["groups"]).isEqualTo(listOf("g-other", "g-users"))
    // A second invitation replaces the first unused one; a withdrawn one is gone.
    val third = api("POST", "/api/vaults/personal/invites", mapOf("email" to "val@example.com", "role" to "editor"))
    assertThat(call("GET", "/invite/${again["code"]}", token = null).body!!.string()).contains("does not exist")
    assertThat(call("DELETE", "/api/invites/${third["code"]}").code).isEqualTo(200)
    assertThat(call("GET", "/invite/${third["code"]}/setup", token = null).code).isEqualTo(404)
    assertThat(call("POST", "/api/vaults/personal/invites", mapOf("email" to "not-an-email")).code).isEqualTo(400)
  }
}
