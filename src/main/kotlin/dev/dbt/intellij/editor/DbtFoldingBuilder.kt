package dev.dbt.intellij.editor

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import dev.dbt.intellij.lang.DbtBlocks
import dev.dbt.intellij.lang.DbtTypes as T

private const val COMMENT_OPEN = "{#"
private const val TRIMMED_COMMENT_OPEN = "{#-"
private const val COMMENT_CLOSE = "#}"
private const val TRIMMED_COMMENT_CLOSE = "-#}"

/** Structural, index-independent folds remain available while the IDE updates indexes. */
class DbtFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ): Array<FoldingDescriptor> {
        val folds = linkedMapOf<TextRange, FoldingDescriptor>()
        fun add(node: ASTNode, start: Int, end: Int, placeholder: String = "…") {
            if (end <= start + 1 || end > document.textLength) return
            if (document.getLineNumber(start) == document.getLineNumber(end - 1)) return
            if (document.charsSequence.subSequence(start, end).isBlank()) return
            val range = TextRange(start, end)
            folds.putIfAbsent(range, FoldingDescriptor(node, range, null, placeholder))
        }
        fun bodyStart(header: ASTNode): Int {
            val firstLine = document.getLineNumber(header.startOffset)
            return if (document.getLineNumber(header.textRange.endOffset - 1) > firstLine)
                document.getLineEndOffset(firstLine)
            else header.textRange.endOffset
        }
        fun blockPlaceholder(header: ASTNode): String {
            val start = bodyStart(header)
            if (start == header.textRange.endOffset) return "…"
            val brackets = ArrayDeque<String>()
            fun tokens(node: ASTNode) {
                if (node.startOffset >= start) return
                val children = node.getChildren(null)
                if (children.isNotEmpty()) {
                    children.forEach(::tokens)
                    return
                }
                when (node.elementType) {
                    T.LPAREN -> brackets.addLast(")")
                    T.LBRACKET -> brackets.addLast("]")
                    T.LBRACE -> brackets.addLast("}")
                    T.RPAREN,
                    T.RBRACKET,
                    T.RBRACE -> if (brackets.lastOrNull() == node.text) brackets.removeLast()
                }
            }
            tokens(header)
            val close = header.findChildByType(T.STMT_CLOSE)?.text ?: "%}"
            return "…${brackets.reversed().joinToString("")} $close …"
        }
        fun visit(node: ASTNode) {
            // One control on the opening line folds the entire block. Header-only and argument
            // folds would compete with it, especially for multiline macro signatures.
            if (node.elementType == T.STATEMENT && DbtBlocks.isStructuralHeader(node)) return
            val children = node.getChildren(null)
            when (node.elementType) {
                T.MACRO,
                T.BLOCK -> {
                    val headers = DbtBlocks.headers(node)
                    val opening = headers.firstOrNull()
                    val closing = opening?.let { DbtBlocks.closingFor(it) }
                    if (opening != null && closing != null) {
                        if (node.elementType == T.MACRO)
                            add(node, opening.textRange.endOffset, closing.startOffset)
                        else
                            add(
                                node,
                                bodyStart(opening),
                                closing.startOffset,
                                blockPlaceholder(opening),
                            )
                        // Each elif/else body can also be collapsed independently.
                        val branches =
                            headers.filter {
                                DbtBlocks.keyword(it)?.text in setOf("elif", "else")
                            } + closing
                        for (i in 0 until branches.lastIndex) {
                            add(
                                branches[i],
                                bodyStart(branches[i]),
                                branches[i + 1].startOffset,
                                blockPlaceholder(branches[i]),
                            )
                        }
                    }
                }
                T.COMMENT -> {
                    val text = node.text
                    if (text.endsWith("#}")) {
                        val prefix =
                            if (text.startsWith(TRIMMED_COMMENT_OPEN)) TRIMMED_COMMENT_OPEN.length
                            else COMMENT_OPEN.length
                        val suffix =
                            if (text.endsWith(TRIMMED_COMMENT_CLOSE)) TRIMMED_COMMENT_CLOSE.length
                            else COMMENT_CLOSE.length
                        add(node, node.startOffset + prefix, node.textRange.endOffset - suffix)
                    }
                }
                T.TEXT -> {
                    val lexer = SqlColorLexer()
                    lexer.start(
                        document.charsSequence,
                        node.startOffset,
                        node.textRange.endOffset,
                        0,
                    )
                    while (lexer.tokenType != null) {
                        if (lexer.tokenType == T.SQL_COMMENT) {
                            val text =
                                document.charsSequence.subSequence(lexer.tokenStart, lexer.tokenEnd)
                            if (text.startsWith("/*") && text.endsWith("*/")) {
                                add(node, lexer.tokenStart + 2, lexer.tokenEnd - 2)
                            }
                        }
                        lexer.advance()
                    }
                }
                T.STATEMENT,
                T.EXPRESSION -> {
                    val first = children.firstOrNull { it.elementType != TokenType.WHITE_SPACE }
                    val last = children.lastOrNull { it.elementType != TokenType.WHITE_SPACE }
                    if (
                        first?.elementType in setOf(T.STMT_OPEN, T.EXPR_OPEN) &&
                            last?.elementType in setOf(T.STMT_CLOSE, T.EXPR_CLOSE)
                    ) {
                        add(node, first!!.textRange.endOffset, last!!.startOffset)
                    }
                }
                T.CALL,
                T.VALUE,
                T.ACCESS -> {
                    val opening = children.firstOrNull {
                        it.elementType in setOf(T.LPAREN, T.LBRACKET, T.LBRACE)
                    }
                    val close =
                        when (opening?.elementType) {
                            T.LPAREN -> T.RPAREN
                            T.LBRACKET -> T.RBRACKET
                            T.LBRACE -> T.RBRACE
                            else -> null
                        }
                    val closing =
                        children.lastOrNull()?.takeIf { close != null && it.elementType == close }
                    if (opening != null && closing != null)
                        add(node, opening.textRange.endOffset, closing.startOffset)
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
