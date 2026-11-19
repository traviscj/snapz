package tcj.snapz

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import misk.MiskCaller
import misk.exceptions.BadRequestException
import misk.exceptions.NotFoundException
import misk.exceptions.UnauthenticatedException
import misk.exceptions.UnauthorizedException
import misk.logging.getLogger
import misk.scope.ActionScoped
import misk.security.authz.Authenticated
import misk.security.authz.MiskCallerAuthenticator
import misk.security.authz.Unauthenticated
import misk.web.DispatchMechanism
import misk.web.Get
import misk.web.HttpCall
import misk.web.Post
import misk.web.QueryParam
import misk.web.RequestBody
import misk.web.RequestContentType
import misk.web.Response
import misk.web.ResponseContentType
import misk.web.actions.WebAction
import misk.web.mediatype.MediaTypes
import okhttp3.Headers
import tcj.oidc.OidcAuthConfig
import tcj.oidc.OidcVerifier

/** The one capability: this is a single-tenant app, a caller is either in or out. */
const val USER = "snapz_user"
const val SESSION_COOKIE = "snapz_session"
const val READ_ONLY = "read_only"

/** misk-feedz: a consumer committing its own cursor. */
private val CURSOR_COMMIT = Regex("/_feeds/[^/]+/cursors/[^/]+/commit")

/** `Authorization: Bearer <token>` (scripts, iOS) or the session cookie (web). */
fun HttpCall.presentedToken(): String? {
  val auth = requestHeaders["Authorization"]
  if (auth != null && auth.startsWith("Bearer ", ignoreCase = true)) return auth.substring(7).trim().takeIf { it.isNotEmpty() }
  return cookies.firstOrNull { it.name == SESSION_COOKIE }?.value?.takeIf { it.isNotEmpty() }
}

class SnapzAuthenticator @Inject constructor(
  private val httpCall: @JvmSuppressWildcards ActionScoped<HttpCall>,
  private val tokens: TokenStore,
  private val oidc: OidcClient,
) : MiskCallerAuthenticator {
  override fun getAuthenticatedCaller(): MiskCaller? {
    val call = httpCall.get()
    val token = call.presentedToken() ?: return null
    if (call.isCrossSiteWrite()) return null
    val resolved = tokens.resolve(token) ?: oidc.bearer(token) ?: return null
    // Read-only: reads, plus the one write a stateless feed consumer needs (its own cursor).
    val mayCall = !resolved.readOnly || call.dispatchMechanism == DispatchMechanism.GET ||
      (call.dispatchMechanism == DispatchMechanism.PUT && call.url.encodedPath.startsWith("/_feeds/cursors/")) ||
      (call.dispatchMechanism == DispatchMechanism.POST && CURSOR_COMMIT.matches(call.url.encodedPath))
    return MiskCaller(
      user = resolved.principal,
      capabilities = buildSet {
        if (mayCall) add(USER)
        if (resolved.vault == null && !resolved.readOnly && resolved.admin) add(ADMIN)
        if (resolved.admin) add(OPERATOR)
        resolved.vault?.let { add(vaultCapability(it)) }
        if (resolved.readOnly) add(READ_ONLY)
      },
    )
  }
}

data class OidcUser(val sub: String, val email: String?, val name: String?, val groups: List<String>)

/** Minimal OIDC authorization-code client (confidential client + PKCE). No JWT handling: identity comes from userinfo. */
@Singleton
class OidcClient @Inject constructor(
  private val config: SnapzConfig,
  private val json: Json,
  clock: Clock,
) {
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  /** Verifies the issuer's JWTs presented as bearer tokens. `email` missing from the token sends it to userinfo. */
  private val verifier: OidcVerifier? = config.snapz.oidc?.let {
    OidcVerifier(OidcAuthConfig(issuer = it.issuer, audiences = listOf(it.client_id) + it.bearer_audiences, user_claim = "email"), clock)
  }

  /**
   * A JWT from the issuer, for one of our audiences, naming someone on the allowlist: the same
   * standing as that person's web session (every vault, admin). Anything else is null.
   */
  fun bearer(token: String): ResolvedToken? {
    if (verifier == null || token.count { it == '.' } != 2) return null
    val id = verifier.verify(token) ?: return null
    val user = OidcUser(id.subject, id.username, id.claims["name"] as? String, id.groups)
    if (!allowed(user)) return null
    return ResolvedToken(null, "oidc", user.email ?: user.sub, admin = isAdmin(user))
  }

  @Volatile private var discovery: Map<String, Any?>? = null

  val oidc: OidcConfig get() = config.snapz.oidc ?: throw NotFoundException("OIDC login is not configured on this server")
  val redirectUri: String get() = config.snapz.public_url.trimEnd('/') + "/auth/callback"

  private fun discover(): Map<String, Any?> {
    discovery?.let { return it }
    val url = oidc.issuer.trimEnd('/') + "/.well-known/openid-configuration"
    val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
    check(res.statusCode() == 200) { "OIDC discovery $url -> ${res.statusCode()}" }
    return json.parseObject(res.body()).also { discovery = it }
  }

  private fun endpoint(name: String) = discover()[name] as? String ?: error("OIDC discovery has no $name")

  fun authorizeUrl(state: String, codeChallenge: String): String {
    val q = linkedMapOf(
      "response_type" to "code",
      "client_id" to oidc.client_id,
      "redirect_uri" to redirectUri,
      "scope" to oidc.scopes,
      "state" to state,
      "code_challenge" to codeChallenge,
      "code_challenge_method" to "S256",
    )
    return endpoint("authorization_endpoint") + "?" + q.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
  }

  fun exchange(code: String, codeVerifier: String): OidcUser {
    val form = linkedMapOf(
      "grant_type" to "authorization_code",
      "code" to code,
      "redirect_uri" to redirectUri,
      "client_id" to oidc.client_id,
      "client_secret" to oidc.client_secret,
      "code_verifier" to codeVerifier,
    ).entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
    val tokenRes = http.send(
      HttpRequest.newBuilder(URI(endpoint("token_endpoint"))).timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
      HttpResponse.BodyHandlers.ofString(),
    )
    if (tokenRes.statusCode() != 200) {
      logger.warn { "OIDC token exchange failed: ${tokenRes.statusCode()} ${tokenRes.body().take(300)}" }
      throw UnauthenticatedException("identity provider rejected the login")
    }
    val accessToken = json.parseObject(tokenRes.body())["access_token"] as? String ?: throw UnauthenticatedException("no access_token from identity provider")
    val infoRes = http.send(
      HttpRequest.newBuilder(URI(endpoint("userinfo_endpoint"))).timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer $accessToken").header("Accept", "application/json").GET().build(),
      HttpResponse.BodyHandlers.ofString(),
    )
    if (infoRes.statusCode() != 200) throw UnauthenticatedException("userinfo -> ${infoRes.statusCode()}")
    val info = json.parseObject(infoRes.body())
    return OidcUser(
      sub = info["sub"]?.toString() ?: throw UnauthenticatedException("userinfo has no sub"),
      email = info["email"] as? String,
      name = (info["name"] ?: info["preferred_username"]) as? String,
      groups = (info["groups"] as? List<*>).orEmpty().map { it.toString() },
    )
  }

  fun isAdmin(u: OidcUser): Boolean = oidc.admin_groups.isEmpty() || u.groups.any { it in oidc.admin_groups }

  fun allowed(u: OidcUser): Boolean =
    (u.email != null && oidc.allowed_emails.any { it.equals(u.email, ignoreCase = true) }) || u.groups.any { it in oidc.allowed_groups }

  companion object {
    private val logger = getLogger<OidcClient>()
    fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
    fun s256(verifier: String): String =
      Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
  }
}

/** In-flight logins. In-memory is fine for one instance: a restart mid-login just means "sign in again". */
@Singleton
class LoginStates @Inject constructor(private val clock: Clock) {
  data class Pending(val client: String, val appChallenge: String?, val returnTo: String, val idpVerifier: String, val atMs: Long)
  data class IosCode(val principal: String, val appChallenge: String, val atMs: Long, val admin: Boolean = true)

  private val pending = ConcurrentHashMap<String, Pending>()
  private val iosCodes = ConcurrentHashMap<String, IosCode>()

  private fun sweep() {
    val cutoff = clock.millis() - TTL_MS
    pending.values.removeIf { it.atMs < cutoff }
    iosCodes.values.removeIf { it.atMs < cutoff }
  }

  fun begin(client: String, appChallenge: String?, returnTo: String): Pair<String, String> {
    sweep()
    if (pending.size > 2000) throw BadRequestException("too many logins in flight")
    val state = randomSecret(24)
    val verifier = randomSecret(48)
    pending[state] = Pending(client, appChallenge, returnTo, verifier, clock.millis())
    return state to verifier
  }

  fun finish(state: String): Pending? = pending.remove(state)?.takeIf { it.atMs >= clock.millis() - TTL_MS }

  fun issueIosCode(principal: String, appChallenge: String, admin: Boolean): String {
    sweep()
    return randomSecret(24).also { iosCodes[it] = IosCode(principal, appChallenge, clock.millis(), admin) }
  }

  fun redeemIosCode(code: String): IosCode? = iosCodes.remove(code)?.takeIf { it.atMs >= clock.millis() - TTL_MS }

  companion object {
    const val TTL_MS = 10 * 60 * 1000L
  }
}

@Singleton
class Sessions @Inject constructor(private val config: SnapzConfig, private val tokens: TokenStore) {
  private val ttlMs get() = Duration.ofDays(config.snapz.session_ttl_days).toMillis()

  /** Set-Cookie value for a fresh web session. */
  fun start(principal: String, name: String, vault: String? = null, readOnly: Boolean = false, admin: Boolean = true): String {
    val (_, token) = tokens.create("session", name, principal, ttlMs, vault, readOnly, admin)
    return cookie(token, ttlMs / 1000)
  }

  fun clear(): String = cookie("", 0)

  private fun cookie(value: String, maxAgeS: Long): String {
    val secure = if (config.snapz.public_url.startsWith("https://")) "; Secure" else ""
    return "$SESSION_COOKIE=$value; Path=/; Max-Age=$maxAgeS; HttpOnly; SameSite=Lax$secure"
  }
}

private fun redirect(to: String, vararg extra: Pair<String, String>): Response<String> {
  val h = Headers.Builder().add("Location", to).add("Cache-Control", "no-store")
  extra.forEach { h.add(it.first, it.second) }
  return Response("", h.build(), 302)
}

/** Only same-site relative paths: never bounce a fresh session to an attacker-chosen origin. */
private fun safeReturnTo(s: String?): String = s?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.contains('\\') } ?: "/"

/**
 * GET /auth/login                       -> web login, ends with a session cookie
 * GET /auth/login?client=ios&challenge= -> app login, ends at <scheme>://auth?code=<one-time code>
 */
class LoginAction @Inject constructor(
  private val oidc: OidcClient,
  private val states: LoginStates,
) : WebAction {
  @Get("/auth/login")
  @Unauthenticated
  fun login(@QueryParam client: String?, @QueryParam challenge: String?, @QueryParam return_to: String?): Response<String> {
    val kind = if (client == "ios") "ios" else "web"
    if (kind == "ios" && (challenge == null || challenge.length < 43)) throw BadRequestException("ios login needs an S256 'challenge'")
    val (state, verifier) = states.begin(kind, challenge, safeReturnTo(return_to))
    return redirect(oidc.authorizeUrl(state, OidcClient.s256(verifier)))
  }
}

class CallbackAction @Inject constructor(
  private val invites: InviteStore,
  private val oidc: OidcClient,
  private val states: LoginStates,
  private val sessions: Sessions,
  private val config: SnapzConfig,
) : WebAction {
  @Get("/auth/callback")
  @Unauthenticated
  fun callback(@QueryParam code: String?, @QueryParam state: String?, @QueryParam error: String?): Response<String> {
    val pending = state?.let(states::finish) ?: throw BadRequestException("unknown or expired login; start again at /auth/login")
    if (error != null || code == null) throw UnauthenticatedException("identity provider returned: ${error ?: "no code"}")
    val user = oidc.exchange(code, pending.idpVerifier)
    if (!oidc.allowed(user)) {
      logger.warn { "login denied for sub=${user.sub} email=${user.email} groups=${user.groups}" }
      throw UnauthorizedException("${user.email ?: user.sub} is not allowed to use this server")
    }
    val principal = user.email ?: user.sub
    logger.info { "login ok: $principal via ${pending.client}" }
    user.email?.let(invites::accepted)
    return if (pending.client == "ios") {
      redirect("${config.snapz.ios_redirect_scheme}://auth?code=" + states.issueIosCode(principal, pending.appChallenge!!, oidc.isAdmin(user)))
    } else {
      redirect(pending.returnTo, "Set-Cookie" to sessions.start(principal, "web", admin = oidc.isAdmin(user)))
    }
  }

  companion object {
    private val logger = getLogger<CallbackAction>()
  }
}

data class DeviceTokenReq(val code: String, val verifier: String, val name: String?)
data class DeviceTokenResp(val token: String, val principal: String)

/** The app trades its one-time code (+ PKCE verifier proving it started the login) for a long-lived device token. */
class DeviceTokenAction @Inject constructor(
  private val states: LoginStates,
  private val tokens: TokenStore,
) : WebAction {
  @Post("/auth/device-token")
  @Unauthenticated
  @RequestContentType(MediaTypes.APPLICATION_JSON)
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun exchange(@RequestBody req: DeviceTokenReq): DeviceTokenResp {
    val issued = states.redeemIosCode(req.code) ?: throw UnauthenticatedException("unknown or expired code")
    if (!MessageDigest.isEqual(OidcClient.s256(req.verifier).toByteArray(), issued.appChallenge.toByteArray())) throw UnauthenticatedException("verifier mismatch")
    val (_, token) = tokens.create("device", req.name?.takeIf { it.isNotBlank() } ?: "ios", issued.principal, admin = issued.admin)
    return DeviceTokenResp(token, issued.principal)
  }
}

data class TokenLoginReq(val token: String)

/** Trade any valid bearer token for a web session cookie: the way into the web UI when OIDC is off or unreachable. */
class TokenLoginAction @Inject constructor(
  private val tokens: TokenStore,
  private val sessions: Sessions,
  private val httpCall: @JvmSuppressWildcards ActionScoped<HttpCall>,
) : WebAction {
  @Post("/auth/token-login")
  @Unauthenticated
  @RequestContentType(MediaTypes.APPLICATION_JSON)
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun login(@RequestBody req: TokenLoginReq): MeDto {
    val resolved = tokens.resolve(req.token.trim()) ?: throw UnauthenticatedException("bad token")
    httpCall.get().setResponseHeader("Set-Cookie", sessions.start(resolved.principal, "web (token login)", resolved.vault, resolved.readOnly, resolved.admin))
    return MeDto(resolved.principal, true, resolved.vault, resolved.readOnly, resolved.vault == null && !resolved.readOnly && resolved.admin, resolved.admin)
  }
}

class LogoutAction @Inject constructor(
  private val tokens: TokenStore,
  private val sessions: Sessions,
  private val httpCall: @JvmSuppressWildcards ActionScoped<HttpCall>,
) : WebAction {
  @Post("/auth/logout")
  @Unauthenticated
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun logout(): Map<String, Boolean> {
    val call = httpCall.get()
    // Revokes whichever credential made the call: a web session, or a device token on app sign-out.
    call.presentedToken()?.let(tokens::resolve)?.id?.let(tokens::revoke)
    call.setResponseHeader("Set-Cookie", sessions.clear())
    return mapOf("ok" to true)
  }
}

/** [vault]: slug this credential is pinned to, if any. [admin]: may manage vaults and tokens. */
data class MeDto(val principal: String, val authenticated: Boolean, val vault: String? = null, val read_only: Boolean = false, val admin: Boolean = true, val operator: Boolean = true)

/** Auth-mode probe for clients: is OIDC available, and (if a credential was sent) who am I. */
data class AuthInfoDto(val oidc: Boolean, val principal: String?)

class AuthInfoAction @Inject constructor(
  private val config: SnapzConfig,
  private val tokens: TokenStore,
  private val httpCall: @JvmSuppressWildcards ActionScoped<HttpCall>,
) : WebAction {
  @Get("/auth/info")
  @Unauthenticated
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun info() = AuthInfoDto(config.snapz.oidc != null, httpCall.get().presentedToken()?.let(tokens::resolve)?.principal)
}

class MeAction @Inject constructor(
  private val caller: @JvmSuppressWildcards ActionScoped<MiskCaller?>,
) : WebAction {
  @Get("/api/me")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun me(): MeDto {
    val caps = caller.get()?.capabilities.orEmpty()
    return MeDto(caller.get()?.principal ?: "?", true, caps.firstOrNull { it.startsWith("vault:") }?.removePrefix("vault:"), READ_ONLY in caps, ADMIN in caps, OPERATOR in caps)
  }
}
