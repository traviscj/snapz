package tcj.snapz

import misk.ServiceModule
import misk.inject.KAbstractModule
import misk.jdbc.JdbcModule
import misk.jdbc.SchemaMigratorService
import misk.security.authz.AccessControlModule
import misk.security.authz.MiskCallerAuthenticator
import misk.web.WebActionModule

/** Everything except deployment + web server wiring, which differ between app and tests. */
class SnapzModule(private val config: SnapzConfig) : KAbstractModule() {
  override fun configure() {
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
    install(WebActionModule.create<CollectionActions>())
    install(WebActionModule.create<PhotoActions>())
    install(WebActionModule.create<BlobAction>())
    install(WebActionModule.create<RecordActions>())
    install(WebActionModule.create<FeedActions>())
    install(WebActionModule.create<TokenActions>())
    install(WebActionModule.create<InfoAction>())
    install(WebActionModule.create<TransformActions>())
    install(WebActionModule.create<InboxAction>())
  }
}
