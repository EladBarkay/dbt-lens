package dev.dbt.intellij.artifacts

/** Bounds for reading saved dbt artifacts and showing them in the tool window. */
internal object ArtifactLimits {
    // Reading manifest.json and catalog.json.
    val SUPPORTED_MANIFEST_VERSIONS = 8..12
    const val MAX_ENTRIES = 100_000
    const val MAX_COLUMNS = 1000
    const val MAX_MACRO_ARGUMENTS = 200
    const val MAX_FQN_PARTS = 100
    const val MAX_COMPILED_CHARS = 2_000_000
    const val MAX_COMPILED_FILE_BYTES = 2_000_000L
    const val MAX_CACHED_SNAPSHOTS = 64

    // Documentation and lineage views.
    const val MAX_SHOWN_COLUMNS = 100
    const val MAX_SHOWN_TESTS = 30
    const val MAX_LINEAGE_DEPTH = 3
    const val LINEAGE_RESOURCE_LIMIT = 80
    const val MAX_LINEAGE_EDGES_EXAMINED = 6_400
}
