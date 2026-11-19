package tcj.snapz

import javax.inject.Inject
import javax.inject.Singleton
import misk.Action
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
      call.setResponseHeader("X-Frame-Options", "DENY")
      call.setResponseHeader("Cross-Origin-Resource-Policy", "same-origin")
      call.setResponseHeader("Content-Security-Policy", if (call.url.encodedPath.startsWith("/blobs/")) BLOB_CSP else PAGE_CSP)
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
    const val BLOB_CSP = "default-src 'none'; sandbox; frame-ancestors 'none'"
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
