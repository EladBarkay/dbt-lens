package dev.dbt.intellij.resources

import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.*

/** Native YAML PSI preserves definition offsets, quotes, comments and document structure. */
class DbtYamlResourceMetadata : DbtResourceMetadata {
    override fun definitions(file: PsiFile): List<DbtResource> {
        if (file !is YAMLFile) return emptyList()
        val result = mutableListOf<DbtResource>()
        fun scalar(mapping: YAMLMapping, key: String): YAMLScalar? =
            (mapping.getKeyValueByKey(key)?.value as? YAMLScalar)?.takeIf {
                !it.textValue.contains("{{") && it.textValue.isNotBlank()
            }
        fun mappings(mapping: YAMLMapping, key: String) =
            (mapping.getKeyValueByKey(key)?.value as? YAMLSequence)
                ?.items
                ?.mapNotNull { it.value as? YAMLMapping }
                .orEmpty()
        for (document in file.documents) {
            val root = document.topLevelValue as? YAMLMapping ?: continue
            for (source in mappings(root, "sources")) {
                val name = scalar(source, "name")?.textValue ?: continue
                for (table in mappings(source, "tables")) {
                    val tableName = scalar(table, "name") ?: continue
                    result +=
                        DbtResource(
                            ResourceKind.SOURCE,
                            tableName.textValue,
                            file.virtualFile,
                            tableName.textOffset,
                            source = name,
                        )
                }
            }
            for ((key, kind) in
                mapOf(
                    "models" to ResourceKind.MODEL,
                    "snapshots" to ResourceKind.SNAPSHOT,
                    "exposures" to ResourceKind.EXPOSURE,
                    "metrics" to ResourceKind.METRIC,
                )) {
                for (mapping in mappings(root, key)) {
                    val name = scalar(mapping, "name") ?: continue
                    val versions =
                        if (kind == ResourceKind.MODEL) mappings(mapping, "versions")
                        else emptyList()
                    if (versions.isEmpty())
                        result +=
                            DbtResource(kind, name.textValue, file.virtualFile, name.textOffset)
                    else {
                        val latest =
                            scalar(mapping, "latest_version")?.textValue
                                ?: versions
                                    .mapNotNull { scalar(it, "v")?.textValue }
                                    .filter { it.toIntOrNull() != null }
                                    .maxByOrNull { it.toInt() }
                        for (version in versions) {
                            val value = scalar(version, "v") ?: continue
                            result +=
                                DbtResource(
                                    kind,
                                    name.textValue,
                                    file.virtualFile,
                                    value.textOffset,
                                    version = value.textValue,
                                    latestVersion = latest,
                                    definedIn = scalar(version, "defined_in")?.textValue,
                                )
                        }
                    }
                }
            }
        }
        return result
    }
}
