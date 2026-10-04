package dev.dbt.intellij.dispatch

import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.*

/** Optional native-YAML bridge: only literal namespaces and literal ordered package lists. */
class DispatchYamlConfiguration : DispatchConfiguration {
    override fun order(file: PsiFile, namespace: String): DispatchOrder {
        if (file !is YAMLFile) return DispatchOrder(configured = true)
        val documents = file.documents
        if (documents.size != 1) return DispatchOrder(configured = true)
        val root = documents.single().topLevelValue as? YAMLMapping ?: return DispatchOrder(configured = true)
        val definitions = root.keyValues.filter { it.keyText == "dispatch" }
        if (definitions.isEmpty()) return DispatchOrder()
        if (definitions.size != 1) return DispatchOrder(configured = true)
        val entries = definitions.single().value as? YAMLSequence ?: return DispatchOrder(configured = true)
        var result: List<String>? = null
        for (item in entries.items) {
            val mapping = item.value as? YAMLMapping ?: return DispatchOrder(configured = true)
            val ns = literal(mapping.getKeyValueByKey("macro_namespace")?.value) ?: return DispatchOrder(configured = true)
            if (ns != namespace) continue
            if (result != null || mapping.keyValues.count { it.keyText in setOf("macro_namespace", "search_order") } != 2) return DispatchOrder(configured = true)
            val sequence = mapping.getKeyValueByKey("search_order")?.value as? YAMLSequence ?: return DispatchOrder(configured = true)
            val packages = sequence.items.map { literal(it.value) ?: return DispatchOrder(configured = true) }
            if (packages.isEmpty()) return DispatchOrder(configured = true)
            result = packages
        }
        return if (result == null) DispatchOrder() else DispatchOrder(result, true)
    }
    private fun literal(value: YAMLValue?): String? = (value as? YAMLScalar)?.textValue?.takeIf {
        it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))
    }
}
