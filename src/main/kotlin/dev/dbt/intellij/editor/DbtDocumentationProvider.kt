package dev.dbt.intellij.editor

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.psi.DbtVariable

/** Static documentation only: never renders Jinja or executes a macro. */
class DbtDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val macro = macro(element) ?: macro(originalElement) ?: return null
        val header = macro.firstChild ?: return null
        val signature = header.text.removePrefix("{%-").removePrefix("{%")
            .removeSuffix("-%}").removeSuffix("%}").trim().replace(Regex("\\s+"), " ")
        val comment = leadingComment(macro)
        return "<div class='definition'><pre>${escape(signature)}</pre></div>" +
            (comment?.let { "<div class='content'><pre>${escape(it)}</pre></div>" } ?: "") +
            "<div class='bottom'>${escape(macro.containingFile.name)}</div>"
    }

    private fun macro(element: PsiElement?): DbtMacro? {
        if (element is DbtMacro) return element
        if (element == null) return null
        val variable = PsiTreeUtil.getParentOfType(element, DbtVariable::class.java, false)
        if (variable != null) return variable.reference?.resolve() as? DbtMacro
        // Declaration names are wrapper PSI rather than a variable reference.
        return PsiTreeUtil.getParentOfType(element, DbtMacro::class.java)?.takeIf {
            it.nameIdentifier?.textRange?.contains(element.textRange) == true
        }
    }

    internal fun leadingComment(macro: DbtMacro): String? {
        var child = macro.firstChild?.nextSibling
        while (child != null && child.text.isBlank()) child = child.nextSibling
        if (child?.node?.elementType == T.COMMENT) {
            val text = child.text
            if (!text.endsWith("#}")) return null
            return text.removePrefix("{#-").removePrefix("{#").removeSuffix("-#}").removeSuffix("#}").trimIndent().trim()
        }
        val text = child?.takeIf { it.node.elementType == T.TEXT }?.text?.trimStart() ?: return null
        if (text.startsWith("/*")) {
            val end = text.indexOf("*/", 2)
            if (end >= 0) return text.substring(2, end).trimIndent().trim()
        }
        if (text.startsWith("--")) return text.lineSequence().takeWhile { it.trimStart().startsWith("--") }
            .joinToString("\n") { it.trimStart().removePrefix("--").trimStart() }
        return null
    }
    private fun escape(text: String) = StringUtil.escapeXmlEntities(text)
}
