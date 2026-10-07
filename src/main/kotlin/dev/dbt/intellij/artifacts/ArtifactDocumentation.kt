package dev.dbt.intellij.artifacts

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.resources.ResourceCall

object ArtifactDocumentation {
    private fun escape(text: String) = StringUtil.escapeXmlEntities(text)
    fun resource(element: PsiElement?, original: PsiElement?): String? {
        for (source in listOfNotNull(original, element)) {
            val call = generateSequence(source) { if (it is com.intellij.psi.PsiFile) null else it.parent }
                .firstOrNull { it.node?.elementType == T.CALL }?.let(ResourceCall::from) ?: continue
            val service = source.project.getService(DbtArtifacts::class.java)
            val snapshot = service.snapshot(source)?.takeIf { it.valid } ?: continue
            val resources = service.resources(source, call, snapshot)
            if (resources.size != 1) return null // Duplicate/versioned identities must not silently pick one.
            val node = resources.single()
            return "<div class='definition'><pre>${escape(node.label)}</pre></div>" + body(source, node, snapshot)
        }
        return null
    }
    fun macro(macro: DbtMacro): String? {
        val service = macro.project.getService(DbtArtifacts::class.java)
        val snapshot = service.snapshot(macro)?.takeIf { it.valid } ?: return null
        val node = service.forFile(macro, snapshot).singleOrNull { it.kind == "macro" && it.name == macro.name } ?: return null
        return body(macro, node, snapshot)
    }
    fun body(source: PsiElement, node: ArtifactNode, snapshot: ArtifactSnapshot): String {
        val service = source.project.getService(DbtArtifacts::class.java)
        val changed = service.changed(source, node)
        return buildString {
            append("<div class='content'>")
            if (changed) append("<p><b>Source has changed since this artifact. Metadata and output may be outdated.</b></p>")
            if (node.description.isNotBlank()) append("<p>${escape(node.description).replace("\n", "<br>")}</p>")
            node.materialized?.let { append("<p>Materialization: ${escape(it)}</p>") }
            node.relation?.let { append("<p>Relation: ${escape(it)}</p>") }
            if (node.arguments.isNotEmpty()) {
                append("<p><b>Documented arguments</b></p><ul>")
                node.arguments.forEach { append("<li>${escape(it.name)}${it.type?.let { t -> ": ${escape(t)}" }.orEmpty()} — ${escape(it.description)}</li>") }
                append("</ul>")
            }
            if (node.columns.isNotEmpty()) {
                append("<p><b>Columns</b></p><table>")
                node.columns.take(100).forEach { append("<tr><td>${escape(it.name)}</td><td>${escape(it.type.orEmpty())}</td><td>${escape(it.description)}</td></tr>") }
                append("</table>")
                if (node.columns.size > 100) append("<p>Showing the first 100 columns.</p>")
            }
            val tests = snapshot.children[node.id].orEmpty().mapNotNull { snapshot.nodes[it] }.filter { it.kind in setOf("test", "unit_test") }
            if (tests.isNotEmpty()) append("<p>Tests: ${escape(tests.take(30).joinToString { it.name })}</p>")
            append("</div><div class='bottom'>Saved dbt metadata${snapshot.generatedAt?.let { " · ${escape(it)}" }.orEmpty()}")
            snapshot.catalogGeneratedAt?.let { append("<br>Catalog: ${escape(it)}") }
            snapshot.messages.forEach { append("<br>${escape(it)}") }
            append("</div>")
        }
    }
}
