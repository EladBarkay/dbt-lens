package dev.dbt.intellij.artifacts

enum class LineageDirection(val title: String) {
    BOTH("Upstream and downstream"),
    UPSTREAM("Upstream"),
    DOWNSTREAM("Downstream"),
}

/** What to include in a lineage graph around the selected resource. */
data class LineageQuery(
    val depth: Int,
    val direction: LineageDirection,
    val includeTestsAndMacros: Boolean = true,
)

data class LineageSlice(
    val layers: Map<String, Int>,
    val edges: Set<Pair<String, String>>,
    val truncated: Boolean,
) {
    companion object {
        private val TESTS_AND_MACROS = setOf("test", "unit_test", "macro")

        fun build(
            snapshot: ArtifactSnapshot,
            active: String,
            query: LineageQuery,
            limit: Int = ArtifactLimits.LINEAGE_RESOURCE_LIMIT,
        ): LineageSlice {
            if (active !in snapshot.nodes) return LineageSlice(emptyMap(), emptySet(), false)
            val layers = linkedMapOf(active to 0)
            var truncated = false
            var examined = 0
            fun walk(up: Boolean) {
                var frontier = setOf(active)
                val visited = mutableSetOf(active)
                for (distance in 1..query.depth.coerceIn(1, ArtifactLimits.MAX_LINEAGE_DEPTH)) {
                    val next = linkedSetOf<String>()
                    for (id in frontier) {
                        val adjacent =
                            if (up) snapshot.nodes[id]?.parents.orEmpty()
                            else snapshot.children[id].orEmpty()
                        for (neighbor in adjacent) {
                            // Missing/disabled IDs must not bypass the UI's bounded traversal.
                            if (++examined > ArtifactLimits.MAX_LINEAGE_EDGES_EXAMINED) {
                                truncated = true
                                return
                            }
                            val kind = snapshot.nodes[neighbor]?.kind ?: continue
                            if (!query.includeTestsAndMacros && kind in TESTS_AND_MACROS) continue
                            if (!visited.add(neighbor)) continue
                            if (neighbor !in layers) {
                                if (layers.size >= limit) {
                                    truncated = true
                                    return
                                }
                                layers[neighbor] = if (up) -distance else distance
                            }
                            next += neighbor
                        }
                    }
                    frontier = next
                }
            }
            if (query.direction != LineageDirection.DOWNSTREAM) walk(true)
            if (query.direction != LineageDirection.UPSTREAM) walk(false)
            val edges =
                layers.keys
                    .flatMap { child ->
                        layers.keys
                            .filter { it in snapshot.nodes[child]?.parents.orEmpty() }
                            .map { it to child }
                    }
                    .toSet()
            return LineageSlice(layers.toMap(), edges, truncated)
        }
    }
}
