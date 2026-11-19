package tcj.snapz

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import misk.exceptions.BadRequestException
import misk.exceptions.NotFoundException
import misk.logging.getLogger
import misk.security.authz.Authenticated
import misk.security.authz.Unauthenticated
import misk.web.Delete
import misk.web.Get
import misk.web.PathParam
import misk.web.Post
import misk.web.RequestBody
import misk.web.RequestContentType
import misk.web.Response
import misk.web.ResponseContentType
import misk.web.actions.WebAction
import misk.web.mediatype.MediaTypes
import okhttp3.Headers

/**
 * The slice of Pocket ID's admin API an invitation needs: find or create the person's account (in
 * the group that is allowed to sign in here) and mint a one-time sign-in link, with which they get
 * in once and register a passkey. Only there when `snapz.pocket_id` is configured.
 */
@Singleton
class PocketId @Inject constructor(private val config: SnapzConfig, private val json: Json) {
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
  private val cfg get() = config.snapz.pocket_id

  val configured: Boolean get() = cfg != null

  private fun call(method: String, path: String, body: Any? = null): Pair<Int, String> {
    val c = cfg ?: error("pocket_id is not configured")
    val req = HttpRequest.newBuilder(URI(c.url.trimEnd('/') + path)).timeout(Duration.ofSeconds(10))
      .header("X-API-KEY", c.api_key).header("Content-Type", "application/json").header("Accept", "application/json")
      .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(json.write(body)))
    val res = http.send(req.build(), HttpResponse.BodyHandlers.ofString())
    return res.statusCode() to res.body()
  }

  private fun ok(r: Pair<Int, String>, what: String): String {
    if (r.first !in 200..299) throw IllegalStateException("identity provider: $what -> ${r.first} ${r.second.take(200)}")
    return r.second
  }

  @Suppress("UNCHECKED_CAST")
  private fun data(body: String): List<Map<String, Any?>> = (json.parseObject(body)["data"] as? List<Map<String, Any?>>).orEmpty()

  /** The account with this email, made now if there is none. Returns its id and whether it was created. */
  fun ensureUser(email: String, name: String?): Pair<String, Boolean> {
    val c = cfg!!
    val found = data(ok(call("GET", "/api/users?search=${URLEncoder.encode(email, Charsets.UTF_8)}&pagination[limit]=50"), "search users"))
      .firstOrNull { (it["email"] as? String)?.equals(email, ignoreCase = true) == true }
    val group = c.users_group?.let { g ->
      data(ok(call("GET", "/api/user-groups?search=${URLEncoder.encode(g, Charsets.UTF_8)}&pagination[limit]=50"), "search groups"))
        .firstOrNull { it["name"] == g }?.get("id") as? String ?: throw IllegalStateException("identity provider has no group '$g'")
    }
    if (found != null) {
      val id = found["id"] as String
      if (group != null) {
        // Keep the groups they have; add ours.
        val have = data("""{"data":${ok(call("GET", "/api/users/$id/groups"), "user groups")}}""").mapNotNull { it["id"] as? String }
        if (group !in have) ok(call("PUT", "/api/users/$id/user-groups", mapOf("userGroupIds" to have + group)), "add to group")
      }
      return id to false
    }
    val base = email.substringBefore('@').lowercase().replace(Regex("[^a-z0-9._-]"), "").ifEmpty { "user" }.take(40)
    val display = name?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')
    for (n in 1..20) {
      val r = call(
        "POST", "/api/users",
        mapOf(
          "username" to if (n == 1) base else "$base$n", "email" to email, "firstName" to display.take(50), "lastName" to "",
          "displayName" to display.take(100), "isAdmin" to false, "userGroupIds" to listOfNotNull(group),
        ),
      )
      if (r.first in 200..299) return (json.parseObject(r.second)["id"] as String) to true
      // A taken username is the one failure worth another try.
      if (!r.second.contains("username", ignoreCase = true)) ok(r, "create user")
    }
    throw IllegalStateException("identity provider: no free username for $email")
  }

  /** A link that signs the account in once, without a passkey; good for an hour. */
  fun oneTimeLink(userId: String): String {
    var r = call("POST", "/api/users/$userId/one-time-access-token", mapOf("ttl" to "1h"))
    if (r.first == 400) r = call("POST", "/api/users/$userId/one-time-access-token", mapOf<String, Any>())
    val token = json.parseObject(ok(r, "one-time access token"))["token"] as String
    return config.snapz.oidc!!.issuer.trimEnd('/') + "/lc/" + token
  }
}

data class InviteReq(val email: String, val role: String?, val name: String?)

data class InviteDto(
  val code: String,
  /** What to send them. */
  val url: String,
  val email: String,
  val vault: String,
  val role: String,
  val invited_by: String,
  val created_at: String,
  val expires_at: String,
  val accepted_at: String?,
  /** created | existing: what happened at the identity provider. manual: none configured, make the account yourself. */
  val account: String? = null,
)

data class InviteList(val invites: List<InviteDto>)

@Singleton
class InviteStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: SnapzConfig,
  private val members: MemberStore,
  private val pocket: PocketId,
) {
  private val select = "SELECT i.*, v.slug AS vault, v.name AS vault_name FROM invites i JOIN vaults v ON v.id = i.vault_id"

  private fun url(code: String) = config.snapz.public_url.trimEnd('/') + "/invite/" + code

  private fun dto(rs: java.sql.ResultSet) = InviteDto(
    code = rs.getString("code"), url = url(rs.getString("code")), email = rs.getString("email"), vault = rs.getString("vault"),
    role = rs.getString("role"), invited_by = rs.getString("invited_by"), created_at = iso(rs.getLong("created_at_ms")),
    expires_at = iso(rs.getLong("expires_at_ms")), accepted_at = isoOrNull(rs.longOrNull("accepted_at_ms")),
  )

  class Open(val email: String, val name: String?, val vault: String, val vaultName: String, val invitedBy: String, val idpUserId: String?, val state: String)

  /**
   * One action: an account at the identity provider (when one is configured), a place in the
   * vault, and a link to send. Inviting someone again replaces their earlier, unused links.
   */
  fun create(vault: Vault, email: String, role: String, name: String?, by: String): InviteDto {
    val who = email.trim().lowercase()
    if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(who) || who.length > 255) throw BadRequestException("'$email' is not an email address")
    val (idpUser, account) = if (pocket.configured) pocket.ensureUser(who, name).let { it.first to if (it.second) "created" else "existing" } else null to "manual"
    members.put(vault, who, role)
    val code = randomSecret(24)
    val now = clock.millis()
    db.tx { c ->
      c.exec("DELETE FROM invites WHERE email = ? AND vault_id = ? AND accepted_at_ms IS NULL", listOf(who, vault.id))
      c.exec(
        "INSERT INTO invites (code, email, name, vault_id, role, invited_by, idp_user_id, created_at_ms, expires_at_ms) VALUES (?,?,?,?,?,?,?,?,?)",
        listOf(code, who, name?.trim()?.takeIf { it.isNotEmpty() }?.take(255), vault.id, role, by, idpUser, now, now + config.snapz.invite_ttl_days * 86_400_000L),
      )
    }
    return db.tx { c -> c.select("$select WHERE i.code = ?", listOf(code), ::dto).first() }.copy(account = account)
  }

  fun list(vaultId: Long): List<InviteDto> = db.tx { c -> c.select("$select WHERE i.vault_id = ? ORDER BY i.id DESC", listOf(vaultId), ::dto) }

  fun revoke(code: String): Boolean = db.tx { c -> c.exec("DELETE FROM invites WHERE code = ?", listOf(code)) > 0 }

  /** What the invitation page needs; null for a code that never existed (or was revoked). */
  fun open(code: String): Open? = db.tx { c ->
    c.select("$select WHERE i.code = ?", listOf(code)) { rs ->
      val state = when {
        rs.longOrNull("accepted_at_ms") != null -> "accepted"
        rs.getLong("expires_at_ms") < clock.millis() -> "expired"
        else -> "open"
      }
      Open(rs.getString("email"), rs.getString("name"), rs.getString("vault"), rs.getString("vault_name"), rs.getString("invited_by"), rs.getString("idp_user_id"), state)
    }.firstOrNull()
  }

  /** They signed in here: the one-time way in has done its job. */
  fun accepted(email: String) {
    db.tx { c -> c.exec("UPDATE invites SET accepted_at_ms = ? WHERE email = ? AND accepted_at_ms IS NULL", listOf(clock.millis(), email.trim().lowercase())) }
  }
}

/**
 * Inviting someone new. Operators only: it creates accounts at the identity provider.
 *
 *   POST   /api/vaults/{slug}/invites   {"email", "role"?: viewer|editor|owner, "name"?}  -> the link to send
 *   GET    /api/vaults/{slug}/invites
 *   DELETE /api/invites/{code}
 *   GET    /invite/{code}               the page the link opens (no sign-in: they cannot sign in yet)
 *   GET    /invite/{code}/setup         -> a one-time sign-in at the identity provider, to register a passkey
 */
class InviteActions @Inject constructor(
  private val store: InviteStore,
  private val ctx: VaultContext,
  private val pocket: PocketId,
  private val config: SnapzConfig,
) : WebAction {
  @Post("/api/vaults/{slug}/invites")
  @Authenticated(capabilities = [ADMIN])
  @RequestContentType(MediaTypes.APPLICATION_JSON)
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun invite(@PathParam slug: String, @RequestBody req: InviteReq): InviteDto {
    val role = req.role ?: Role.VIEWER
    if (role !in Role.all) throw BadRequestException("role is one of ${Role.all.joinToString()}")
    return store.create(ctx.membership(slug).vault, req.email, role, req.name, ctx.principal())
  }

  @Get("/api/vaults/{slug}/invites")
  @Authenticated(capabilities = [ADMIN])
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun list(@PathParam slug: String) = InviteList(store.list(ctx.membership(slug).vault.id))

  @Delete("/api/invites/{code}")
  @Authenticated(capabilities = [ADMIN])
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun revoke(@PathParam code: String): Ok {
    if (!store.revoke(code)) throw NotFoundException("no such invitation")
    return Ok()
  }

  @Get("/invite/{code}")
  @Unauthenticated
  @ResponseContentType(MediaTypes.TEXT_HTML)
  fun page(@PathParam code: String): String {
    val inv = store.open(code) ?: return shell("This invitation does not exist", "<p>It may have been withdrawn. Ask whoever sent it for a new one.</p>")
    val open = "/auth/login?return_to=" + URLEncoder.encode("/#/@${inv.vault}/photos/~all", Charsets.UTF_8)
    val what = "<p><b>${esc(inv.invitedBy)}</b> invited <b>${esc(inv.email)}</b> to <b>${esc(inv.vaultName)}</b> on snapz.</p>"
    return when (inv.state) {
      "expired" -> shell("This invitation has expired", "$what<p>Ask for a new one.</p>")
      "accepted" -> shell("You're already set up", "$what<p><a class=b href=\"$open\">Open snapz</a></p><p class=d>Sign in with the passkey you registered. Lost it? Ask for a new invitation.</p>")
      else -> shell(
        "You're invited",
        what + if (pocket.configured && inv.idpUserId != null) {
          "<ol><li><a class=b href=\"/invite/${esc(code)}/setup\" target=_blank rel=noopener>Set up your sign-in</a>" +
            "<div class=d>Opens the sign-in service in a new tab. Add a passkey there (Face ID, Touch ID, or a security key); that is how you sign in from now on. The link works once.</div></li>" +
            "<li><a class=b href=\"$open\">Open snapz</a><div class=d>Come back to this page and continue once the passkey is added.</div></li></ol>"
        } else {
          "<p><a class=b href=\"$open\">Open snapz</a></p><p class=d>Sign in with the account ${esc(inv.invitedBy)} set up for you.</p>"
        },
      )
    }
  }

  @Get("/invite/{code}/setup")
  @Unauthenticated
  fun setup(@PathParam code: String): Response<String> {
    val inv = store.open(code)?.takeIf { it.state == "open" && it.idpUserId != null && pocket.configured } ?: throw NotFoundException("this invitation can't be used to set up a sign-in")
    val link = try {
      pocket.oneTimeLink(inv.idpUserId!!)
    } catch (e: Exception) {
      logger.warn { "invite setup for ${inv.email} failed: $e" }
      throw NotFoundException("the sign-in service did not answer; try again in a minute")
    }
    return Response("", Headers.Builder().add("Location", link).add("Cache-Control", "no-store").build(), 302)
  }

  private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private fun shell(title: String, body: String) = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex"><meta name="referrer" content="no-referrer"><title>${esc(title)} · snapz</title>
<style>body{font:15px/1.45 ui-monospace,SFMono-Regular,Menlo,monospace;background:#0b0e11;color:#d7dde3;margin:0;padding:24px 16px}
main{max-width:520px;margin:0 auto}h1{font-size:18px;color:#58a6ff;margin:0 0 12px}b{color:#fff}ol{padding-left:20px}li{margin:0 0 16px}
.b{display:inline-block;background:#1f6feb;color:#fff;text-decoration:none;padding:8px 14px;border-radius:4px;font-weight:700}.d{color:#8b949e;font-size:13px;margin-top:6px}
img{height:28px;width:28px;border-radius:6px;vertical-align:middle;margin-right:8px}</style></head>
<body><main><h1><img src="/icon.png" alt="">${esc(title)}</h1>$body</main></body></html>"""

  companion object {
    private val logger = getLogger<InviteActions>()
  }
}
