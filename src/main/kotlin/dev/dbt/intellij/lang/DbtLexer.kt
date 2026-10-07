package dev.dbt.intellij.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** Token-start state is restartable, including whitespace-control and raw blocks. */
class DbtLexer : LexerBase() {
    private var source: CharSequence = ""
    private var limit = 0
    private var start = 0
    private var end = 0
    private var state = 0
    private var followingState = 0
    private var type: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        source = buffer
        limit = endOffset
        start = startOffset
        state = initialState
        locate()
    }

    override fun getState() = state

    override fun getTokenType() = type

    override fun getTokenStart() = start

    override fun getTokenEnd() = end

    override fun getBufferSequence() = source

    override fun getBufferEnd() = limit

    override fun advance() {
        start = end
        state = followingState
        locate()
    }

    private fun at(offset: Int, text: String): Boolean =
        offset + text.length <= limit && text.indices.all { source[offset + it] == text[it] }

    private fun tagWord(offset: Int): String? {
        if (!at(offset, "{%")) return null
        var p = offset + 2
        if (p < limit && source[p] in "-+") p++
        while (p < limit && source[p].isWhitespace()) p++
        val first = p
        while (p < limit && source[p].isLetter()) p++
        val word = source.subSequence(first, p).toString()
        while (p < limit && source[p].isWhitespace()) p++
        if (p < limit && source[p] in "-+") p++
        return if (at(p, "%}")) word else null
    }

    private fun locate() {
        type = null
        end = start
        followingState = state
        if (start >= limit) return
        if (state == RAW && tagWord(start) != "endraw") {
            end = start + 1
            while (end < limit && tagWord(end) != "endraw") end++
            type = DbtTypes.TEXT
            return
        }
        if (at(start, "{#")) {
            end = start + 2
            while (end < limit && !at(end, "#}")) end++
            if (end < limit) end += 2
            type = DbtTypes.COMMENT
            return
        }
        if (at(start, "{{") || at(start, "{%")) {
            val expression = at(start, "{{")
            type = if (expression) DbtTypes.EXPR_OPEN else DbtTypes.STMT_OPEN
            followingState =
                if (expression) EXPR else if (tagWord(start) == "raw") RAW_HEADER else STMT
            end = start + 2
            if (end < limit && (source[end] == '-' || (!expression && source[end] == '+'))) end++
            return
        }
        if (state == OUTSIDE || state == RAW) {
            end = start + 1
            while (end < limit && !at(end, "{{") && !at(end, "{%") && !at(end, "{#")) end++
            type = DbtTypes.TEXT
            followingState = OUTSIDE
            return
        }
        val close = if (state == EXPR) "}}" else "%}"
        val closeStart =
            if (source[start] == '-' || (state != EXPR && source[start] == '+')) start + 1
            else start
        if (at(closeStart, close)) {
            end = closeStart + 2
            type = if (state == EXPR) DbtTypes.EXPR_CLOSE else DbtTypes.STMT_CLOSE
            followingState = if (state == RAW_HEADER) RAW else OUTSIDE
            return
        }
        val c = source[start]
        end = start + 1
        type =
            when {
                c.isWhitespace() -> {
                    while (end < limit && source[end].isWhitespace()) end++
                    TokenType.WHITE_SPACE
                }
                c == '\'' || c == '"' -> {
                    while (end < limit) {
                        val next = source[end++]
                        if (next == '\\' && end < limit) end++ else if (next == c) break
                    }
                    DbtTypes.STRING
                }
                c.isLetter() || c == '_' -> {
                    while (
                        end < limit && (source[end].isLetterOrDigit() || source[end] == '_')
                    ) end++
                    if (source.subSequence(start, end).toString() in KEYWORDS) DbtTypes.KEYWORD
                    else DbtTypes.IDENTIFIER
                }
                c.isDigit() -> {
                    while (
                        end < limit &&
                            (source[end].isDigit() ||
                                (source[end] == '_' &&
                                    end + 1 < limit &&
                                    source[end + 1].isDigit()))
                    ) end++
                    if (end + 1 < limit && source[end] == '.' && source[end + 1].isDigit()) {
                        end++
                        while (
                            end < limit &&
                                (source[end].isDigit() ||
                                    (source[end] == '_' &&
                                        end + 1 < limit &&
                                        source[end + 1].isDigit()))
                        ) end++
                    }
                    if (end < limit && source[end] in "eE") {
                        var exponent = end + 1
                        if (exponent < limit && source[exponent] in "+-") exponent++
                        if (exponent < limit && source[exponent].isDigit()) {
                            end = exponent + 1
                            while (
                                end < limit &&
                                    (source[end].isDigit() ||
                                        (source[end] == '_' &&
                                            end + 1 < limit &&
                                            source[end + 1].isDigit()))
                            ) end++
                        }
                    }
                    DbtTypes.NUMBER
                }
                c == '(' -> DbtTypes.LPAREN
                c == ')' -> DbtTypes.RPAREN
                c == '[' -> DbtTypes.LBRACKET
                c == ']' -> DbtTypes.RBRACKET
                c == '{' -> DbtTypes.LBRACE
                c == '}' -> DbtTypes.RBRACE
                c == ',' -> DbtTypes.COMMA
                c == '.' -> DbtTypes.DOT
                c == ':' -> DbtTypes.COLON
                c in "+-*/%=<>!|~" -> {
                    if (
                        end < limit &&
                            source.subSequence(start, end + 1).toString() in DOUBLE_OPERATORS
                    )
                        end++
                    DbtTypes.OPERATOR
                }
                else -> TokenType.BAD_CHARACTER
            }
    }

    companion object {
        private const val OUTSIDE = 0
        private const val EXPR = 1
        private const val STMT = 2
        private const val RAW = 3
        private const val RAW_HEADER = 4
        val KEYWORDS =
            setOf(
                "macro",
                "endmacro",
                "if",
                "elif",
                "else",
                "endif",
                "for",
                "endfor",
                "in",
                "not",
                "and",
                "or",
                "is",
                "set",
                "endset",
                "call",
                "endcall",
                "filter",
                "endfilter",
                "block",
                "endblock",
                "raw",
                "endraw",
                "with",
                "endwith",
                "do",
                "import",
                "from",
                "as",
                "include",
                "extends",
                "true",
                "false",
                "none",
                "True",
                "False",
                "None",
                "recursive",
                "snapshot",
                "endsnapshot",
                "test",
                "endtest",
                "materialization",
                "endmaterialization",
                "docs",
                "enddocs",
                "break",
                "continue",
            )
        private val DOUBLE_OPERATORS = setOf("==", "!=", "<=", ">=", "//", "**")
    }
}
