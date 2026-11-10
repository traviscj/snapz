package tcj.snapz

import javax.inject.Qualifier
import misk.config.Config
import misk.jdbc.DataSourceConfig
import misk.web.WebConfig

data class SnapzConfig(
  val web: WebConfig,
  val data_source: DataSourceConfig,
  val snapz: SnapzSettings = SnapzSettings(),
) : Config

data class SnapzSettings(
  /** Content-addressed originals + derived thumbnails live here. */
  val blob_dir: String = "data/blobs",
  /** Externally visible base URL; the OIDC redirect_uri is derived from it. */
  val public_url: String = "http://localhost:8788",
  val max_upload_bytes: Long = 64L * 1024 * 1024,
  val feed_publish_interval_ms: Long = 500,
  /** How often the in-process worker looks for pending intents of built-in transforms. */
  val transform_interval_ms: Long = 500,
  /** Where "snap first, file later" photos land; GET /api/inbox ranks destinations for them. */
  val inbox_slug: String = "inbox",
  /** Create the inbox (with the suggest-collection transform attached) at startup if missing. */
  val auto_create_inbox: Boolean = true,
  /** Data shards assigned at write time; consumer shards map onto these by modulus. */
  val feed_shards: Int = 16,
  val thumb_px: Int = 400,
  /** Custom URL scheme the iOS app registers; /auth/callback redirects to <scheme>://auth?code=. */
  val ios_redirect_scheme: String = "snapz",
  val session_ttl_days: Long = 60,
  /** null = OIDC login disabled (only bearer tokens work). */
  val oidc: OidcConfig? = null,
  /** Static bearer tokens defined in config. For local dev / tests / first-boot bootstrap. */
  val bootstrap_tokens: List<BootstrapToken> = listOf(),
)

data class OidcConfig(
  /** e.g. https://id.example.net — endpoints come from <issuer>/.well-known/openid-configuration */
  val issuer: String,
  val client_id: String,
  val client_secret: String,
  val scopes: String = "openid profile email groups",
  /** Caller must match at least one of allowed_emails / allowed_groups. Both empty = nobody. */
  val allowed_emails: List<String> = listOf(),
  val allowed_groups: List<String> = listOf(),
) {
  override fun toString() = "OidcConfig(issuer=$issuer, client_id=$client_id, client_secret=████)"
}

data class BootstrapToken(val token: String, val principal: String) {
  override fun toString() = "BootstrapToken(principal=$principal)"
}

@Qualifier
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
annotation class SnapzDb
