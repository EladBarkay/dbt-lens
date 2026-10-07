package dev.dbt.intellij.editor

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.psi.DbtVariable
import dev.dbt.intellij.semantic.DbtTypeInference

/** Static documentation only: never renders Jinja or executes a macro. */
class DbtDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val macro = macro(element) ?: macro(originalElement)
        if (macro == null) {
            val variable = listOfNotNull(originalElement, element).firstNotNullOfOrNull {
                PsiTreeUtil.getParentOfType(it, DbtVariable::class.java, false)
            } ?: return null
            val symbol = DbtTypeInference.symbol(variable) ?: DbtTypeInference.contextual(variable)[variable.text]
                ?.takeIf { dev.dbt.intellij.psi.LocalVariables.candidates(variable).isEmpty() } ?: return null
            val signature = symbol.name + symbol.signature?.let { "($it)" }.orEmpty()
            return "<div class='definition'><pre>${escape(signature)}</pre></div>" +
                "<div class='content'>${escape(symbol.description)}<br>Type: ${escape(symbol.type.name)}</div>" +
                "<div class='bottom'><a href='${escape(symbol.url)}'>Documentation</a></div>"
        }
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
        var child = macro.prevSibling
        while (child != null && child.text.isBlank()) child = child.prevSibling
        if (child?.node?.elementType == T.COMMENT) {
            val text = child.text
            if (!text.endsWith("#}")) return null
            return text.removePrefix("{#-").removePrefix("{#").removeSuffix("-#}").removeSuffix("#}").trimIndent().trim()
        }
        val text = child?.takeIf { it.node.elementType == T.TEXT }?.text ?: return null
        // SQL text can include code before the documentation. Lex it rather than treating
        // comment-looking content in strings, or an earlier macro's body, as documentation.
        val lexer = SqlColorLexer(); lexer.start(text)
        val tokens = mutableListOf<Pair<com.intellij.psi.tree.IElementType, String>>()
        while (lexer.tokenType != null) {
            val part = text.substring(lexer.tokenStart, lexer.tokenEnd)
            if (part.isNotBlank()) tokens += lexer.tokenType!! to part
            lexer.advance()
        }
        if (tokens.lastOrNull()?.first != T.SQL_COMMENT) return null
        val last = tokens.last().second
        if (last.startsWith("/*")) return last.takeIf { it.endsWith("*/") }
            ?.removePrefix("/*")?.removeSuffix("*/")?.trimIndent()?.trim()
        return tokens.asReversed().takeWhile { it.first == T.SQL_COMMENT && it.second.startsWith("--") }
            .asReversed().joinToString("\n") { it.second.removePrefix("--").trimStart() }
    }
    private fun escape(text: String) = StringUtil.escapeXmlEntities(text)
}
