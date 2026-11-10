import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  application
  alias(libs.plugins.kotlinJvm)
}

group = "tcj"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

application {
  mainClass.set("tcj.snapz.SnapzAppKt")
}

dependencies {
  implementation(libs.misk)
  implementation(libs.miskActions)
  implementation(libs.miskConfig)
  implementation(libs.miskCore)
  implementation(libs.miskInject)
  implementation(libs.miskJdbc)
  implementation(libs.miskService)
  implementation(libs.mysql)
  implementation(libs.slf4jSimple)

  testImplementation(libs.miskTesting)
  testImplementation(platform(libs.junitBom))
  testImplementation(libs.junitApi)
  testRuntimeOnly(libs.junitEngine)
  testRuntimeOnly(libs.junitPlatformLauncher)
  testImplementation(libs.assertj)
}

java {
  sourceCompatibility = JavaVersion.VERSION_21
  targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_21)
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
}
