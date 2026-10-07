package dev.dbt.intellij.editor

import com.intellij.lexer.LayeredLexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.dbt.intellij.lang.DbtLexer
import dev.dbt.intellij.lang.DbtTypes as T

class DbtSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) =
        DbtSyntaxHighlighter()
}

class DbtSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() =
        LayeredLexer(DbtLexer()).apply {
            registerSelfStoppingLayer(SqlColorLexer(), arrayOf(T.TEXT), emptyArray())
        }

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        pack(KEYS[tokenType])

    companion object {
        private fun key(name: String, fallback: TextAttributesKey) =
            TextAttributesKey.createTextAttributesKey("DBT_$name", fallback)

        val KEYS =
            mapOf(
                T.EXPR_OPEN to key("DELIMITER", Colors.BRACES),
                T.EXPR_CLOSE to key("DELIMITER", Colors.BRACES),
                T.STMT_OPEN to key("DELIMITER", Colors.BRACES),
                T.STMT_CLOSE to key("DELIMITER", Colors.BRACES),
                T.KEYWORD to key("KEYWORD", Colors.KEYWORD),
                T.IDENTIFIER to key("IDENTIFIER", Colors.IDENTIFIER),
                T.STRING to key("STRING", Colors.STRING),
                T.NUMBER to key("NUMBER", Colors.NUMBER),
                T.COMMENT to key("COMMENT", Colors.BLOCK_COMMENT),
                T.OPERATOR to key("OPERATOR", Colors.OPERATION_SIGN),
                T.SQL_KEYWORD to key("SQL_KEYWORD", Colors.KEYWORD),
                T.SQL_STRING to key("SQL_STRING", Colors.STRING),
                T.SQL_COMMENT to key("SQL_COMMENT", Colors.LINE_COMMENT),
                T.SQL_NUMBER to key("SQL_NUMBER", Colors.NUMBER),
                TokenType.BAD_CHARACTER to HighlighterColors.BAD_CHARACTER,
            )
    }
}

/**
 * Lexical fallback only: deliberately does not claim SQL PSI, dialect completion or SQL
 * diagnostics.
 */
class SqlColorLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var limit = 0
    private var from = 0
    private var to = 0
    private var type: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        limit = endOffset
        from = startOffset
        locate()
    }

    override fun getState() = 0

    override fun getTokenType() = type

    override fun getTokenStart() = from

    override fun getTokenEnd() = to

    override fun getBufferSequence() = buffer

    override fun getBufferEnd() = limit

    override fun advance() {
        from = to
        locate()
    }

    private fun at(offset: Int, text: String) =
        offset + text.length <= limit && text.indices.all { buffer[offset + it] == text[it] }

    private fun locate() {
        type = null
        to = from
        if (from >= limit) return
        to++
        val char = buffer[from]
        type =
            when {
                at(from, "--") -> {
                    while (to < limit && buffer[to] != '\n') to++
                    T.SQL_COMMENT
                }
                at(from, "/*") -> {
                    to = from + 2
                    while (to < limit && !at(to, "*/")) to++
                    if (to < limit) to += 2
                    T.SQL_COMMENT
                }
                char == '\'' || char == '"' -> {
                    while (to < limit) {
                        if (buffer[to++] == char) {
                            if (to < limit && buffer[to] == char) to++ else break
                        }
                    }
                    T.SQL_STRING
                }
                char.isLetter() || char == '_' -> {
                    while (to < limit && (buffer[to].isLetterOrDigit() || buffer[to] in "_$")) to++
                    if (buffer.subSequence(from, to).toString().lowercase() in SQL_WORDS)
                        T.SQL_KEYWORD
                    else T.TEXT
                }
                char.isDigit() ||
                    (char == '.' && from + 1 < limit && buffer[from + 1].isDigit()) -> {
                    while (to < limit && buffer[to].isDigit()) to++
                    if (char != '.' && to < limit && buffer[to] == '.') {
                        to++
                        while (to < limit && buffer[to].isDigit()) to++
                    }
                    if (to < limit && buffer[to] in "eE") {
                        var exponent = to + 1
                        if (exponent < limit && buffer[exponent] in "+-") exponent++
                        val digits = exponent
                        while (exponent < limit && buffer[exponent].isDigit()) exponent++
                        if (exponent > digits) to = exponent
                    }
                    T.SQL_NUMBER
                }
                else -> T.TEXT
            }
    }

    companion object {
        private val SQL_WORDS =
            setOf(
                "select",
                "from",
                "where",
                "as",
                "with",
                "join",
                "left",
                "right",
                "inner",
                "outer",
                "on",
                "and",
                "or",
                "not",
                "null",
                "case",
                "when",
                "then",
                "else",
                "end",
                "group",
                "by",
                "order",
                "having",
                "union",
                "all",
                "distinct",
                "limit",
                "qualify",
                "over",
                "partition",
                "create",
                "table",
                "insert",
                "into",
                "values",
            )
    }
}
