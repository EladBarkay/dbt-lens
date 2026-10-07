package dev.dbt.intellij.editor

import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TextExpression
import dev.dbt.intellij.lang.DbtBlocks
import dev.dbt.intellij.lang.DbtTypes as T

/** Templates run only after the user accepts a tag completion, never while an incomplete name is typed. */
object DbtBlockTemplates {
    fun insert(keyword: String, context: InsertionContext) {
        val closing = DbtBlocks.CLOSING[keyword]
        if (closing == null || keyword == "set") {
            val offset = context.tailOffset
            if (context.document.charsSequence.getOrNull(offset)?.isWhitespace() != true)
                context.document.insertString(offset, " ")
            context.editor.caretModel.moveToOffset(offset + 1)
            return
        }
        context.commitDocument()
        val leaf = context.file.findElementAt(context.startOffset)
        val header = generateSequence(leaf) { if (it is com.intellij.psi.PsiFile) null else it.parent }.firstOrNull { it.node?.elementType == T.STATEMENT }
        if (header != null && DbtBlocks.closingFor(header.node) != null) return
        val offset = context.tailOffset
        val rest = context.document.charsSequence.substring(offset)
        val end = Regex("^[ \\t]*[-+]?%}").find(rest)?.value?.length ?: 0
        // Leave a partially written header and its arguments alone.
        if (end == 0 && rest.isNotBlank()) return
        if (end > 0) context.document.deleteString(offset, offset + end)
        val lineStart = context.document.getLineStartOffset(context.document.getLineNumber(context.startOffset))
        val indent = context.document.charsSequence.subSequence(lineStart, context.startOffset).takeWhile { it == ' ' || it == '\t' }.toString()
        val manager = TemplateManager.getInstance(context.project)
        val template = manager.createTemplate("", "dbt Lens")
        fun variable(name: String, default: String) { template.addVariable(name, TextExpression(default), TextExpression(default), true) }
        template.addTextSegment(" ")
        when (keyword) {
            "macro", "test" -> { variable("NAME", if (keyword == "macro") "macro_name" else "test_name"); template.addTextSegment("("); variable("ARGS", if (keyword == "test") "model" else ""); template.addTextSegment(")") }
            "if" -> variable("CONDITION", "condition")
            "for" -> { variable("ITEM", "item"); template.addTextSegment(" in "); variable("ITEMS", "items") }
            "with" -> { variable("NAME", "value"); template.addTextSegment(" = "); variable("VALUE", "none") }
            "call" -> { variable("CALL", "macro_name"); template.addTextSegment("()") }
            "filter" -> variable("FILTER", "upper")
            "materialization" -> { variable("NAME", "custom"); template.addTextSegment(", default") }
            "block", "docs", "snapshot" -> variable("NAME", "name")
            "raw" -> Unit
        }
        template.addTextSegment(" %}\n$indent    ")
        template.addEndVariable()
        template.addTextSegment("\n$indent{% $closing %}")
        template.setToReformat(false)
        template.setToIndent(false)
        context.editor.caretModel.moveToOffset(offset)
        manager.startTemplate(context.editor, template)
        context.setAddCompletionChar(false)
    }
}
