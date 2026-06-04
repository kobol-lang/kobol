package dev.kobol

/**
 * Library versions emitted in the dependency manifest ([FeatureDetector]).
 *
 * These must stay in sync with [gradle/libs.versions.toml].
 * A single `grep -r "DepVersions\|libs.versions" .` surfaces all touch points.
 */
internal object DepVersions {
    const val JAVALIN  = "6.3.0"
    const val MONGODB  = "5.2.0"
    const val JEDIS    = "5.1.0"
    const val SLF4J    = "2.0.16"
}
