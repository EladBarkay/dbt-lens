package dev.dbt.intellij.editor

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import dev.dbt.intellij.lang.DbtTypes as T

/** Structural, index-independent folds remain available while the IDE updates indexes. */
class DbtFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val folds = linkedMapOf<TextRange, FoldingDescriptor>()
        fun add(node: ASTNode, start: Int, end: Int) {
            if (end <= start + 1 || end > document.textLength) return
            if (document.getLineNumber(start) == document.getLineNumber(end - 1)) return
            if (document.charsSequence.subSequence(start, end).isBlank()) return
            val range = TextRange(start, end)
            folds.putIfAbsent(range, FoldingDescriptor(node, range))
        }
        fun keyword(node: ASTNode): String? = node.getChildren(null)
            .firstOrNull { it.elementType in setOf(T.KEYWORD, T.IDENTIFIER) }?.text
        fun visit(node: ASTNode) {
            val children = node.getChildren(null)
            when (node.elementType) {
                T.MACRO, T.BLOCK -> {
                    val headers = children.filter { it.elementType == T.STATEMENT }
                    val opening = headers.firstOrNull()
                    val closing = headers.lastOrNull()?.takeIf { keyword(it)?.startsWith("end") == true }
                    if (opening != null && closing != null && opening !== closing) {
                        add(node, opening.textRange.endOffset, closing.startOffset)
                        // Each elif/else body can also be collapsed independently.
                        val branches = headers.filter { keyword(it) in setOf("elif", "else") } + closing
                        for (i in 0 until branches.lastIndex) {
                            add(branches[i], branches[i].textRange.endOffset, branches[i + 1].startOffset)
                        }
                    }
                }
                T.STATEMENT, T.EXPRESSION -> {
                    val first = children.firstOrNull { it.elementType != TokenType.WHITE_SPACE }
                    val last = children.lastOrNull { it.elementType != TokenType.WHITE_SPACE }
                    if (first?.elementType in setOf(T.STMT_OPEN, T.EXPR_OPEN) &&
                        last?.elementType in setOf(T.STMT_CLOSE, T.EXPR_CLOSE)) {
                        add(node, first!!.textRange.endOffset, last!!.startOffset)
                    }
                }
                T.CALL, T.VALUE, T.ACCESS -> {
                    val opening = children.firstOrNull { it.elementType in setOf(T.LPAREN, T.LBRACKET, T.LBRACE) }
                    val close = when (opening?.elementType) {
                        T.LPAREN -> T.RPAREN
                        T.LBRACKET -> T.RBRACKET
                        T.LBRACE -> T.RBRACE
                        else -> null
                    }
                    val closing = children.lastOrNull()?.takeIf { close != null && it.elementType == close }
                    if (opening != null && closing != null) add(node, opening.textRange.endOffset, closing.startOffset)
                }
            }
            children.forEach { visit(it) }
        }
        visit(root.node)
        return folds.values.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode) = "…"
    override fun isCollapsedByDefault(node: ASTNode) = false
}
