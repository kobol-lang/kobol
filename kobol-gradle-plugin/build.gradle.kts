plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    alias(libs.plugins.plugin.publish)
}

group = "dev.kobol"
// version inherited from root allprojects { } — single source: gradle.properties (kobolVersion)

gradlePlugin {
    website = "https://github.com/mrtinkz/kobol"
    vcsUrl  = "https://github.com/mrtinkz/kobol"

    plugins {
        create("kobol") {
            id                  = "dev.kobol"
            implementationClass = "dev.kobol.gradle.KobolPlugin"
            displayName         = "Kobol Gradle Plugin"
            description         = "Compiles Kobol (.kbl) source files to JVM bytecode and wires them into the Java build lifecycle."
            tags                = listOf("kobol", "cobol", "jvm", "compiler", "language")
        }
    }
}

dependencies {
    // Kobol compiler on the plugin classpath — tasks call KobolCompiler directly.
    // During development we reference the local project; for a Portal release the
    // compiler must be published first and referenced as an external coordinate:
    //   implementation("dev.kobol:compiler:0.1.0")
    implementation(project(":compiler"))

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

// ---------------------------------------------------------------------------
// Gradle Plugin Portal publishing
//
// Credentials are read from environment variables at publish time:
//   GRADLE_PUBLISH_KEY   — API key from plugins.gradle.org
//   GRADLE_PUBLISH_SECRET — API secret from plugins.gradle.org
//
// Publish command:
//   ./gradlew :kobol-gradle-plugin:publishPlugins
// ---------------------------------------------------------------------------
System.getenv("GRADLE_PUBLISH_KEY")?.let    { key    -> System.setProperty("gradle.publish.key",    key) }
System.getenv("GRADLE_PUBLISH_SECRET")?.let { secret -> System.setProperty("gradle.publish.secret", secret) }
