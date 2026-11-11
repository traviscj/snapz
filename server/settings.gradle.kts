pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

rootProject.name = "snapz"

// tcj:misk-oidc is not published yet: build it from a sibling checkout (next to the snapz repo) when there is one.
file("../../misk-oidc").takeIf { it.isDirectory }?.let { includeBuild(it) }
