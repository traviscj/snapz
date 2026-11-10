package tcj.snapz

import misk.MiskApplication
import misk.MiskRealServiceModule
import misk.config.ConfigModule
import misk.config.MiskConfig
import misk.environment.DeploymentModule
import misk.web.MiskWebModule
import wisp.deployment.DEVELOPMENT

fun main(args: Array<String>) {
  // DEVELOPMENT: SchemaMigratorService applies pending migrations at startup, which is what a
  // single self-hosted instance wants. Secrets and per-host settings come from the override file
  // named by SNAPZ_CONFIG (layered over snapz-common.yaml).
  val deployment = DEVELOPMENT
  val overrides = listOfNotNull(System.getenv("SNAPZ_CONFIG")?.takeIf { it.isNotBlank() }?.let { "filesystem:$it" })
  val config = MiskConfig.load<SnapzConfig>("snapz", deployment, overrides)
  MiskApplication(
    MiskRealServiceModule(),
    MiskWebModule(config.web),
    DeploymentModule(deployment),
    ConfigModule.create("snapz", config),
    SnapzModule(config),
  ).run(args)
}
