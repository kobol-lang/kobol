plugins {
    kotlin("jvm") version "2.3.20" apply false
    id("org.graalvm.buildtools.native") version "0.10.3" apply false
}

allprojects {
    group   = "dev.kobol"
    version = property("kobolVersion") as String   // single source: gradle.properties

    repositories {
        mavenCentral()
    }
}

// ---------------------------------------------------------------------------
// syncExtensionVersion — regenerates extension/src/version.ts from the Gradle
// version so the VSCode extension's KOBOL_VERSION never drifts. Generated file
// is committed (TS build needs it); rerun this task after a version bump.
// ---------------------------------------------------------------------------
tasks.register("syncExtensionVersion") {
    group = "build"
    description = "Writes extension/src/version.ts from the single-source Gradle version"
    val versionValue = version.toString()
    val outFile = file("extension/src/version.ts")
    inputs.property("version", versionValue)
    outputs.file(outFile)
    doLast {
        outFile.writeText(
            "// GENERATED — do not edit. Single source: root gradle.properties (kobolVersion).\n" +
            "// Regenerate with: ./gradlew syncExtensionVersion\n" +
            "export const KOBOL_VERSION = '$versionValue';\n"
        )
    }
}
