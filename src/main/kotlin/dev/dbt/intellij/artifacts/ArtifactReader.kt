package dev.dbt.intellij.artifacts

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.intellij.openapi.progress.ProgressManager
import dev.dbt.intellij.io.BoundedFiles
import java.nio.file.Files
import java.nio.file.Path

data class ArtifactColumn(val name: String, val description: String = "", val type: String? = null)

data class ArtifactArgument(val name: String, val description: String, val type: String?)

data class ArtifactNode(
    val id: String,
    val kind: String,
    val name: String,
    val packageName: String,
    val originalPath: String,
    val description: String,
    val columns: List<ArtifactColumn>,
    val materialized: String?,
    val relation: String?,
    val version: String?,
    val latestVersion: String?,
    val sourceName: String?,
    val rawCode: String?,
    val compiledCode: String?,
    val compiledPath: String?,
    val parents: Set<String>,
    val arguments: List<ArtifactArgument> = emptyList(),
    val language: String? = null,
    val fqn: List<String> = emptyList(),
    val wasCompiled: Boolean = false,
) {
    val label
        get() =
            "$packageName.${sourceName?.let { "$it." }.orEmpty()}$name${version?.let { " v$it" }.orEmpty()} ($kind)"
}

data class ArtifactSnapshot(
    val nodes: Map<String, ArtifactNode> = emptyMap(),
    val generatedAt: String? = null,
    val catalogGeneratedAt: String? = null,
    val messages: List<String> = emptyList(),
    val valid: Boolean = false,
) {
    val children: Map<String, Set<String>> by lazy {
        val result = mutableMapOf<String, MutableSet<String>>()
        nodes.values.forEach { n ->
            ProgressManager.checkCanceled()
            n.parents.forEach {
                ProgressManager.checkCanceled()
                result.getOrPut(it) { linkedSetOf() }.add(n.id)
            }
        }
        result
    }
}

data class ArtifactRequest(
    val root: Path,
    val projectName: String?,
    val target: Path?,
    val problem: String? = null,
)

/** Stream one resource at a time; never executes Jinja, dbt or warehouse queries. */
object ArtifactReader {
    const val MAX_BYTES = 64L * 1024 * 1024
    private val sections =
        setOf(
            "nodes",
            "sources",
            "macros",
            "exposures",
            "metrics",
            "semantic_models",
            "saved_queries",
            "unit_tests",
        )

    private fun JsonObject.string(key: String): String? =
        get(key)
            ?.takeIf { it.isJsonPrimitive }
            ?.asJsonPrimitive
            ?.takeIf { it.isString || it.isNumber }
            ?.asString

    private fun JsonObject.obj(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(key: String) =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()

    private fun JsonObject.strings(key: String) =
        array(key)
            .mapNotNull {
                it.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }
            .toSet()

    private fun objectValue(reader: JsonReader): JsonObject =
        JsonParser.parseReader(reader).takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("Expected a JSON object")

    private fun columns(value: JsonElement?): List<ArtifactColumn> {
        val records =
            when {
                value?.isJsonObject == true ->
                    value.asJsonObject.entrySet().map { it.key to it.value }
                value?.isJsonArray == true -> value.asJsonArray.map { "" to it }
                else -> emptyList()
            }
        return records.take(1000).mapNotNull { (key, record) ->
            record
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let {
                    val name =
                        it.string("name") ?: key.takeIf(String::isNotBlank) ?: return@let null
                    ArtifactColumn(
                        name,
                        it.string("description") ?: it.string("comment").orEmpty(),
                        it.string("data_type") ?: it.string("type"),
                    )
                }
        }
    }

    private fun resource(id: String, record: JsonObject): ArtifactNode? {
        val kind = record.string("resource_type") ?: id.substringBefore('.')
        val name = record.string("name") ?: return null
        val pkg = record.string("package_name") ?: return null
        if (!id.startsWith("$kind.$pkg.") || record.string("unique_id")?.let { it != id } == true)
            return null
        if (
            record.obj("config")?.get("enabled")?.let {
                it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean
            } == true
        )
            return null
        val original = record.string("original_file_path") ?: return null
        if (!safeRelative(original)) return null
        val depends = record.obj("depends_on")
        return ArtifactNode(
            id,
            kind,
            name,
            pkg,
            original,
            record.string("description").orEmpty(),
            columns(record.get("columns")),
            record.obj("config")?.string("materialized"),
            record.string("relation_name"),
            record.string("version"),
            record.string("latest_version"),
            record.string("source_name"),
            record.string("raw_code") ?: record.string("raw_sql") ?: record.string("macro_sql"),
            (record.string("compiled_code") ?: record.string("compiled_sql"))?.takeIf {
                it.length <= 2_000_000
            },
            record.string("compiled_path"),
            depends?.let { it.strings("nodes") + it.strings("macros") }.orEmpty(),
            record.array("arguments").take(200).mapNotNull { arg ->
                arg.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.let {
                        ArtifactArgument(
                            it.string("name") ?: return@let null,
                            it.string("description").orEmpty(),
                            it.string("type"),
                        )
                    }
            },
            record.string("language"),
            record.array("fqn").let { parts ->
                if (
                    parts.size <= 100 &&
                        parts.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                )
                    parts.map { it.asString }
                else emptyList()
            },
            record
                .get("compiled")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.asBoolean ?: (record.string("compiled_path") != null),
        )
    }

    internal fun safeRelative(value: String): Boolean =
        value.isNotBlank() &&
            '\\' !in value &&
            '\u0000' !in value &&
            !value.startsWith('/') &&
            !Regex("^[A-Za-z]:").containsMatchIn(value) &&
            value.split('/').none { it in setOf("..", ".", "") }

    private fun read(file: Path, consumer: (String, JsonReader) -> Unit) {
        require(Files.size(file) <= MAX_BYTES) { "Artifact exceeds the 64 MB limit" }
        BoundedFiles.reader(file, MAX_BYTES).use { stream ->
            JsonReader(stream).use { reader ->
                reader.strictness = Strictness.STRICT
                reader.beginObject()
                val seen = hashSetOf<String>()
                while (reader.hasNext()) {
                    ProgressManager.checkCanceled()
                    val key = reader.nextName()
                    require(seen.add(key)) { "Duplicate artifact section: $key" }
                    consumer(key, reader)
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT) {
                    "Trailing content after artifact"
                }
            }
        }
    }

    private fun validateMetadata(metadata: JsonObject?, projectName: String?, type: String) {
        require(metadata != null) { "Artifact metadata is missing" }
        val schema = metadata.string("dbt_schema_version").orEmpty()
        val supported =
            if (type == "manifest")
                (8..12).map { "https://schemas.getdbt.com/dbt/manifest/v$it.json" }
            else listOf("https://schemas.getdbt.com/dbt/catalog/v1.json")
        require(schema in supported) { "Unsupported $type schema: $schema" }
        require(metadata.string("project_name")?.let { it == projectName } != false) {
            "Artifact belongs to a different dbt project"
        }
    }

    fun load(request: ArtifactRequest): ArtifactSnapshot {
        request.problem?.let {
            return ArtifactSnapshot(messages = listOf(it))
        }
        val target =
            request.target
                ?: return ArtifactSnapshot(messages = listOf("Artifact directory is unavailable"))
        val manifest = target.resolve("manifest.json")
        if (!Files.isRegularFile(manifest))
            return ArtifactSnapshot(
                messages =
                    listOf("No manifest.json in $target. Click Generate manifest to create it."),
            )
        try {
            require(
                target.toRealPath().startsWith(request.root.toRealPath()) &&
                    manifest.toRealPath().startsWith(target.toRealPath()),
            ) {
                "Artifact files must remain inside the dbt project's output directory"
            }
            val nodes = linkedMapOf<String, ArtifactNode>()
            val parents = mutableMapOf<String, Set<String>>()
            var metadata: JsonObject? = null
            var count = 0
            read(manifest) { key, reader ->
                when (key) {
                    "metadata" -> metadata = objectValue(reader)
                    in sections -> {
                        reader.beginObject()
                        val seen = hashSetOf<String>()
                        while (reader.hasNext()) {
                            ProgressManager.checkCanceled()
                            require(++count <= 100_000) {
                                "Artifact exceeds the 100,000 resource limit"
                            }
                            val id = reader.nextName()
                            require(seen.add(id)) { "Duplicate resource: $id" }
                            resource(id, objectValue(reader))?.let {
                                require(nodes.put(id, it) == null) { "Duplicate resource: $id" }
                            }
                        }
                        reader.endObject()
                    }
                    "parent_map" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            ProgressManager.checkCanceled()
                            require(parents.size < 100_000) { "Too many lineage entries" }
                            val id = reader.nextName()
                            require(id !in parents) { "Duplicate lineage entry: $id" }
                            val array = JsonParser.parseReader(reader)
                            parents[id] =
                                array
                                    .takeIf { it.isJsonArray }
                                    ?.asJsonArray
                                    ?.mapNotNull {
                                        it.takeIf {
                                                it.isJsonPrimitive && it.asJsonPrimitive.isString
                                            }
                                            ?.asString
                                    }
                                    ?.toSet()
                                    .orEmpty()
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            validateMetadata(metadata, request.projectName, "manifest")
            parents.forEach { (id, deps) ->
                ProgressManager.checkCanceled()
                nodes[id]?.let { nodes[id] = it.copy(parents = it.parents + deps) }
            }
            val messages = mutableListOf<String>()
            val catalog = target.resolve("catalog.json")
            var catalogAt: String? = null
            if (Files.isRegularFile(catalog)) {
                try {
                    require(catalog.toRealPath().startsWith(target.toRealPath())) {
                        "Catalog file points outside the output directory"
                    }
                    val records = linkedMapOf<String, JsonObject>()
                    var catalogMetadata: JsonObject? = null
                    var hasErrors = false
                    read(catalog) { key, reader ->
                        when (key) {
                            "metadata" -> catalogMetadata = objectValue(reader)
                            "errors" -> {
                                val errors = JsonParser.parseReader(reader)
                                hasErrors = errors.isJsonArray && errors.asJsonArray.size() > 0
                            }
                            "nodes",
                            "sources" -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    ProgressManager.checkCanceled()
                                    require(records.size < 100_000) { "Too many catalog entries" }
                                    val id = reader.nextName()
                                    require(records.put(id, objectValue(reader)) == null) {
                                        "Duplicate catalog entry: $id"
                                    }
                                }
                                reader.endObject()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    validateMetadata(catalogMetadata, request.projectName, "catalog")
                    if (hasErrors)
                        messages +=
                            "Catalog generation reported errors; warehouse metadata may be incomplete"
                    catalogAt = catalogMetadata?.string("generated_at")
                    records.forEach { (id, record) ->
                        ProgressManager.checkCanceled()
                        nodes[id]?.let { node ->
                            val warehouse = columns(record.get("columns")).associateBy { it.name }
                            val documented = node.columns.associateBy { it.name }
                            val used = hashSetOf<String>()
                            val merged =
                                documented.values.map { doc ->
                                    val col =
                                        warehouse[doc.name]
                                            ?: warehouse.values
                                                .filter {
                                                    it.name.equals(doc.name, ignoreCase = true)
                                                }
                                                .singleOrNull()
                                                ?.takeIf {
                                                    documented.keys.count { name ->
                                                        name.equals(doc.name, ignoreCase = true)
                                                    } == 1
                                                }
                                    col?.let { used += it.name }
                                    ArtifactColumn(
                                        doc.name,
                                        doc.description.takeIf(String::isNotBlank)
                                            ?: col?.description.orEmpty(),
                                        col?.type ?: doc.type,
                                    )
                                } + warehouse.values.filter { it.name !in used }
                            val physical = record.obj("metadata")
                            val relation =
                                listOfNotNull(
                                        physical?.string("database"),
                                        physical?.string("schema"),
                                        physical?.string("name"),
                                    )
                                    .joinToString(".")
                                    .takeIf(String::isNotBlank)
                            nodes[id] =
                                node.copy(columns = merged, relation = node.relation ?: relation)
                        }
                    }
                } catch (e: Exception) {
                    if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                    messages += "Catalog unavailable: ${e.message}"
                }
            }
            return ArtifactSnapshot(
                nodes.toMap(),
                metadata?.string("generated_at"),
                catalogAt,
                messages,
                true,
            )
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            return ArtifactSnapshot(messages = listOf("Manifest unavailable: ${e.message}"))
        }
    }

    fun compiled(request: ArtifactRequest, node: ArtifactNode): String? {
        if (node.kind == "macro" || node.language == "python" || node.originalPath.endsWith(".py"))
            return null // Definitions/Python are not SQL output.
        node.compiledCode?.let {
            return it
        }
        // parse (and partial compile) leave old files on disk for uncompiled nodes.
        if (!node.wasCompiled) return null
        val target = request.target ?: return null
        val explicit =
            node.compiledPath?.takeIf(::safeRelative)?.let { request.root.resolve(it).normalize() }
        val candidates =
            listOfNotNull(
                explicit,
                target.resolve("compiled/${node.packageName}/${node.originalPath}").normalize(),
            )
        return candidates.firstNotNullOfOrNull { file ->
            if (
                !file.startsWith(target) ||
                    !Files.isRegularFile(file) ||
                    Files.size(file) > 2_000_000 ||
                    !file.toRealPath().startsWith(target.toRealPath())
            )
                null
            else BoundedFiles.text(file, 2_000_000)
        }
    }
}
