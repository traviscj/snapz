pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

rootProject.name = "snapz"

// tcj:misk-oidc and tcj:misk-feedz are not on Maven Central. With a sibling checkout (next to the
// snapz repo) each is built from there, so they can be worked on together; otherwise Gradle clones
// it and builds the tag that matches the requested version.
for (lib in listOf("misk-oidc", "misk-feedz")) {
  val sibling = file("../../$lib")
  if (sibling.isDirectory) {
    includeBuild(sibling)
  } else {
    sourceControl {
      gitRepository(uri("https://github.com/traviscj/$lib.git")) { producesModule("tcj:$lib") }
    }
  }
}
