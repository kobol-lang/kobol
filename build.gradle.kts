plugins {
    kotlin("jvm") version "2.3.20" apply false
    id("org.graalvm.buildtools.native") version "0.10.3" apply false
}

allprojects {
    group   = "dev.kobol"
    version = "0.1.0-dev"

    repositories {
        mavenCentral()
    }
}
