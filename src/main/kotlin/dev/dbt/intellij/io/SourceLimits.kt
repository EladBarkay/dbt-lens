package dev.dbt.intellij.io

/** Bounds for reading, caching and analyzing project sources. */
internal object SourceLimits {
    /** Largest project source or YAML file read for navigation and documentation. */
    const val MAX_FILE_BYTES = 2_000_000L
    const val MAX_CACHED_CATALOGS = 128
    const val MAX_CACHED_DIRECTORIES = 512
    const val MAX_TEMPLATE_NESTING = 128
    const val MAX_INFERENCE_DEPTH = 20
}
