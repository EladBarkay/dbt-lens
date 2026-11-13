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
            val call =
                generateSequence(source) { if (it is com.intellij.psi.PsiFile) null else it.parent }
                    .firstOrNull { it.node?.elementType == T.CALL }
                    ?.let(ResourceCall::from) ?: continue
            val service = source.project.getService(DbtArtifacts::class.java)
            val snapshot = service.snapshot(source)?.takeIf { it.valid } ?: continue
            val resources = service.resources(source, call, snapshot)
            if (resources.size != 1)
                return null // Duplicate/versioned identities must not silently pick one.
            val node = resources.single()
            return "<div class='definition'><pre>${escape(node.label)}</pre></div>" +
                body(source, node, snapshot)
        }
        return null
    }

    fun macro(macro: DbtMacro): String? {
        val service = macro.project.getService(DbtArtifacts::class.java)
        val snapshot = service.snapshot(macro)?.takeIf { it.valid } ?: return null
        val node =
            service.forFile(macro, snapshot).singleOrNull {
                it.kind == "macro" && it.name == macro.name
            } ?: return null
        return body(macro, node, snapshot)
    }

    fun body(
        source: PsiElement,
        node: ArtifactNode,
        snapshot: ArtifactSnapshot,
        showStaleness: Boolean = true,
    ): String {
        val service = source.project.getService(DbtArtifacts::class.java)
        val changed = showStaleness && service.changed(source, node)
        return buildString {
            append("<div class='content'>")
            if (showStaleness && changed)
                append(
                    "<p><b>Source has changed since this artifact. Metadata and output may be outdated.</b></p>",
                )
            if (node.description.isNotBlank())
                append("<p>${escape(node.description).replace("\n", "<br>")}</p>")
            node.materialized?.let { append("<p>Materialization: ${escape(it)}</p>") }
            node.relation?.let { append("<p>Relation: <code>${escape(it)}</code></p>") }
            if (node.arguments.isNotEmpty()) {
                append("<h3>Documented arguments</h3><ul>")
                node.arguments.forEach {
                    append(
                        "<li>${escape(it.name)}${it.type?.let { t -> ": ${escape(t)}" }.orEmpty()} — ${escape(it.description)}</li>",
                    )
                }
                append("</ul>")
            }
            if (node.columns.isNotEmpty()) appendColumns(node.columns)
            val tests =
                snapshot.children[node.id]
                    .orEmpty()
                    .mapNotNull { snapshot.nodes[it] }
                    .filter { it.kind in setOf("test", "unit_test") }
            if (tests.isNotEmpty()) {
                append("<h3>Tests</h3><ul>")
                tests.take(ArtifactLimits.MAX_SHOWN_TESTS).forEach {
                    append("<li>${escape(it.name)}</li>")
                }
                append("</ul>")
                if (tests.size > ArtifactLimits.MAX_SHOWN_TESTS)
                    append("<p>Showing the first ${ArtifactLimits.MAX_SHOWN_TESTS} tests.</p>")
            }
            append(
                "</div><div class='bottom'>Saved dbt metadata${snapshot.generatedAt?.let { " · ${escape(it)}" }.orEmpty()}",
            )
            snapshot.catalogGeneratedAt?.let { append("<br>Catalog: ${escape(it)}") }
            snapshot.messages.forEach { append("<br>${escape(it)}") }
            append("</div>")
        }
    }

    private fun StringBuilder.appendColumns(columns: List<ArtifactColumn>) {
        val hasTypes = columns.any { !it.type.isNullOrBlank() }
        val hasDescriptions = columns.any { it.description.isNotBlank() }
        append("<h3>Columns</h3><table><tr><th")
        if (hasDescriptions)
            append(
                " width='${if (hasTypes) ArtifactLayout.NAME_WIDTH_WITH_TYPES else ArtifactLayout.NAME_WIDTH}%'",
            )
        append(">Name</th>")
        if (hasTypes) append("<th width='20%'>Type</th>")
        if (hasDescriptions) append("<th>Description</th>")
        append("</tr>")
        columns.take(ArtifactLimits.MAX_SHOWN_COLUMNS).forEach {
            append("<tr><td nowrap><code>${escape(it.name)}</code></td>")
            if (hasTypes) append("<td>${escape(it.type.orEmpty())}</td>")
            if (hasDescriptions) append("<td>${escape(it.description).replace("\n", "<br>")}</td>")
            append("</tr>")
        }
        append("</table>")
        if (columns.size > ArtifactLimits.MAX_SHOWN_COLUMNS)
            append("<p>Showing the first ${ArtifactLimits.MAX_SHOWN_COLUMNS} columns.</p>")
    }
}
