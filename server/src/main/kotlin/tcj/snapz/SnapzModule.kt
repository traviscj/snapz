package tcj.snapz

import misk.ServiceModule
import misk.inject.KAbstractModule
import misk.jdbc.JdbcModule
import misk.jdbc.SchemaMigratorService
import misk.security.authz.AccessAnnotationEntry
import misk.security.authz.AccessControlModule
import misk.security.authz.MiskCallerAuthenticator
import misk.logging.getLogger
import misk.web.NetworkInterceptor
import misk.web.WebActionModule
import misk.web.dashboard.AdminDashboardAccess
import misk.web.dashboard.AdminDashboardModule
import misk.web.metadata.all.AllMetadataAccess

/** Everything except deployment + web server wiring, which differ between app and tests. */
class SnapzModule(private val config: SnapzConfig) : KAbstractModule() {
  override fun configure() {
    // A deployment that would hand out a master key does not start; the rest is said loudly.
    val findings = Posture.check(config)
    findings.filter { !it.fatal }.forEach { logger.warn { "posture: ${it.message}" } }
    findings.filter { it.fatal }.let { fatal ->
      check(fatal.isEmpty()) { "refusing to start: " + fatal.joinToString("; ") { it.message } }
    }
    multibind<NetworkInterceptor.Factory>().to<SecurityHeaders>()
    multibind<NetworkInterceptor.Factory>().to<DashboardGate>()

    // misk's own dashboard at /_admin/ (service status, config with secrets redacted, database,
    // web actions): for holders of the admin capability only, same as snapz's admin routes.
    install(AdminDashboardModule(isDevelopment = false))
    multibind<AccessAnnotationEntry>().toInstance(AccessAnnotationEntry<AdminDashboardAccess>(capabilities = listOf(ADMIN)))
    multibind<AccessAnnotationEntry>().toInstance(AccessAnnotationEntry<AllMetadataAccess>(capabilities = listOf(ADMIN)))

    // Without this, @Authenticated is decorative: misk only enforces it via AccessInterceptor.
    install(AccessControlModule())
    multibind<MiskCallerAuthenticator>().to<SnapzAuthenticator>()

    install(JdbcModule(SnapzDb::class, config.data_source))
    install(ServiceModule<FeedPublisher>().dependsOn<SchemaMigratorService>(SnapzDb::class))
    install(ServiceModule<TransformRunner>().dependsOn<SchemaMigratorService>(SnapzDb::class))

    install(WebActionModule.create<WebUiAction>())
    install(WebActionModule.create<LoginAction>())
    install(WebActionModule.create<CallbackAction>())
    install(WebActionModule.create<DeviceTokenAction>())
    install(WebActionModule.create<TokenLoginAction>())
    install(WebActionModule.create<LogoutAction>())
    install(WebActionModule.create<AuthInfoAction>())
    install(WebActionModule.create<MeAction>())
    install(WebActionModule.create<VaultActions>())
    install(WebActionModule.create<CollectionActions>())
    install(WebActionModule.create<PhotoActions>())
    install(WebActionModule.create<BlobAction>())
    install(WebActionModule.create<RecordActions>())
    install(WebActionModule.create<FeedActions>())
    install(WebActionModule.create<ReplicaActions>())
    install(WebActionModule.create<TokenActions>())
    install(WebActionModule.create<InfoAction>())
    install(WebActionModule.create<TransformActions>())
    install(WebActionModule.create<InboxAction>())
  }
}

private val logger = getLogger<SnapzModule>()
