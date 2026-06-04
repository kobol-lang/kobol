plugins {
    kotlin("jvm")
}

dependencies {
    implementation(libs.javalin)

    // NoSQL document store bridge — compileOnly so programs that don't use NoSQL
    // don't pay the classpath cost; users add the driver to their own kobol.toml deps.
    compileOnly(libs.mongodb)
    testImplementation(libs.mongodb)

    // Cache (key-value) bridge — same compileOnly contract as above.
    compileOnly(libs.jedis)
    testImplementation(libs.jedis)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // H2 in-memory DB for JDBC integration tests
    testRuntimeOnly(libs.h2)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
