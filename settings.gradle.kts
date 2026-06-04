// Root Gradle settings
// gradle/libs.versions.toml is auto-discovered by Gradle 7.4+ as the default
// version catalog (libs.*). No explicit dependencyResolutionManagement needed.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Foojay toolchain resolver — allows Gradle to auto-download JDK 21 when no
// matching JDK is found locally (removes the need to pre-install a JDK).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "kobol"

include(
    "compiler",
    "runtime",
    "stdlib",
    "kobol-gradle-plugin",
)
