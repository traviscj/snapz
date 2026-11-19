package tcj.snapz

import javax.inject.Inject
import javax.inject.Singleton
import misk.Action
import misk.MiskCaller
import misk.exceptions.UnauthenticatedException
import misk.exceptions.UnauthorizedException
import misk.scope.ActionScoped
import misk.web.DispatchMechanism
import misk.web.HttpCall
import misk.web.NetworkChain
import misk.web.NetworkInterceptor

/**
 * Response headers every reply carries. The web client is one page with inline script and style,
 * same-origin API calls and same-origin images, so that is all the page may load; image bytes are
 * served so that nothing in them can run even if a browser is talked into rendering them as a page.
 */
@Singleton
class SecurityHeaders @Inject constructor(config: SnapzConfig) : NetworkInterceptor.Factory {
  private val https = config.snapz.public_url.startsWith("https://")

  private val interceptor = object : NetworkInterceptor {
    override fun intercept(chain: NetworkChain) {
      val call = chain.httpCall
      call.setResponseHeader("X-Content-Type-Options", "nosniff")
      call.setResponseHeader("Referrer-Policy", "no-referrer")
      val path = call.url.encodedPath
      val own = path == "/" || OWN_PREFIXES.any { path.startsWith(it) }
      // The feeds page is shown as a tab (an iframe) of the admin dashboard, so it alone may be framed, by us.
      val framed = path == FEEDS_PAGE
      call.setResponseHeader("X-Frame-Options", if (own && !framed) "DENY" else "SAMEORIGIN")
      call.setResponseHeader("Cross-Origin-Resource-Policy", "same-origin")
      call.setResponseHeader(
        "Content-Security-Policy",
        when {
          path.startsWith("/blobs/") -> BLOB_CSP
          framed -> PAGE_CSP.replace("frame-ancestors 'none'", "frame-ancestors 'self'")
          own -> PAGE_CSP
          else -> DASHBOARD_CSP
        },
      )
      // Only when the deployment says it is https: on a plain-http LAN server this would be a lie.
      if (https) call.setResponseHeader("Strict-Transport-Security", "max-age=31536000")
      chain.proceed(call)
    }
  }

  override fun create(action: Action): NetworkInterceptor = interceptor

  companion object {
    const val PAGE_CSP =
      "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' data: blob:; " +
        "connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
    /** misk's admin dashboard (admin-only) brings its own scripts, styles and CDN assets. */
    const val DASHBOARD_CSP = "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob: https:; frame-ancestors 'self'"
    /** Everything snapz itself serves; any other path belongs to misk. */
    val OWN_PREFIXES = listOf("/api/", "/auth/", "/_feeds/", "/blobs/", "/icon.png")
    const val FEEDS_PAGE = "/_feeds/ui"
    const val BLOB_CSP = "default-src 'none'; sandbox; frame-ancestors 'none'"
  }
}

/**
 * misk's dashboard guards its tabs but serves its shell page (a catch-all route) to anyone. Here
 * the whole of it is for admins: any path that is neither snapz's own nor a health check needs
 * the admin capability, whatever misk would have let through.
 */
@Singleton
class DashboardGate @Inject constructor(private val caller: @JvmSuppressWildcards ActionScoped<MiskCaller?>) : NetworkInterceptor.Factory {
  private val interceptor = object : NetworkInterceptor {
    override fun intercept(chain: NetworkChain) {
      val path = chain.httpCall.url.encodedPath
      val open = path == "/" || SecurityHeaders.OWN_PREFIXES.any { path.startsWith(it) } || path in HEALTH
      if (!open) {
        val who = caller.get() ?: throw UnauthenticatedException("sign in first")
        if (ADMIN !in who.capabilities) throw UnauthorizedException("${who.principal} is not an admin")
      } else if (path.startsWith("/_feeds/") && !path.startsWith("/_feeds/fetch/") && path != "/_feeds/status") {
        // Cursors, publishing and replication are shared, server-wide state: operators only.
        // (Fetching is scoped to the caller's vaults by the fetch endpoint itself.)
        val who = caller.get() ?: throw UnauthenticatedException("sign in first")
        if (OPERATOR !in who.capabilities) throw UnauthorizedException("${who.principal} does not run this server")
      }
      chain.proceed(chain.httpCall)
    }
  }

  override fun create(action: Action): NetworkInterceptor = interceptor

  private companion object {
    val HEALTH = setOf("/_status", "/_liveness", "/_readiness")
  }
}

/**
 * Cross-site request guard for cookie sessions. The session cookie is SameSite=Lax, which already
 * keeps it off cross-site POSTs; this is the second lock. A write that arrives on the cookie (not a
 * bearer token: scripts and the app are not browsers) and that the browser itself labels as coming
 * from another site is treated as unauthenticated.
 */
fun HttpCall.isCrossSiteWrite(): Boolean {
  if (dispatchMechanism == DispatchMechanism.GET) return false
  if (requestHeaders["Authorization"] != null) return false
  val site = requestHeaders["Sec-Fetch-Site"]
  if (site != null) return site != "same-origin" && site != "none"
  // Older browsers: no Sec-Fetch-Site, but writes carry Origin. Compare it with the host we were asked for.
  val origin = requestHeaders["Origin"] ?: return false
  val host = requestHeaders["X-Forwarded-Host"] ?: requestHeaders["Host"] ?: return false
  return origin.substringAfter("://") != host
}

/** What is wrong with a configuration, judged as a deployment. Fatal ones stop the server from starting. */
object Posture {
  data class Finding(val fatal: Boolean, val message: String)

  const val MIN_BOOTSTRAP_TOKEN = 32

  fun check(config: SnapzConfig): List<Finding> = buildList {
    val s = config.snapz
    val public = s.public_url.startsWith("https://")
    for (t in s.bootstrap_tokens) {
      if (t.token.length < MIN_BOOTSTRAP_TOKEN) {
        add(Finding(public, "bootstrap token for '${t.principal}' is ${t.token.length} chars; a guessable static token is a master key (use $MIN_BOOTSTRAP_TOKEN+ random chars, or none)"))
      }
    }
    if (!public) return@buildList
    if (s.bootstrap_tokens.isNotEmpty()) add(Finding(false, "${s.bootstrap_tokens.size} bootstrap token(s) in config: remove them once a script token exists"))
    if (config.data_source.username == "root") add(Finding(false, "database user is root: give snapz its own user with rights on its own schema only"))
    if (config.data_source.password.isNullOrEmpty()) add(Finding(false, "database password is empty"))
    if (s.oidc == null) add(Finding(false, "no OIDC: the only way in is a bearer token"))
    else if (s.oidc.allowed_emails.isEmpty() && s.oidc.allowed_groups.isEmpty()) add(Finding(false, "OIDC is configured but nobody is on the allowlist"))
  }
}
