pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

rootProject.name = "snapz"

// tcj:misk-oidc is not on Maven Central. With a sibling checkout (next to the snapz repo) it is built
// from there, so both can be worked on together; otherwise Gradle clones it and builds the tag that
// matches the requested version.
val sibling = file("../../misk-oidc")
if (sibling.isDirectory) {
  includeBuild(sibling)
} else {
  sourceControl {
    gitRepository(uri("https://github.com/traviscj/misk-oidc.git")) { producesModule("tcj:misk-oidc") }
  }
}
