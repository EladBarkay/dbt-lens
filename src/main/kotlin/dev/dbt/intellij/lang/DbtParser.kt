package dev.dbt.intellij.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

/** Structural template parser plus a Pratt expression parser. Never discards source text. */
class DbtParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        Parser(builder).body(null, emptySet(), 0)
        file.done(root)
        return builder.treeBuilt
    }

    private class Parser(val b: PsiBuilder) {
        private fun isType(type: IElementType) = b.tokenType == type
        private fun isName() = isType(DbtTypes.IDENTIFIER) ||
            (isType(DbtTypes.KEYWORD) && b.tokenText !in LITERALS + setOf("and", "or", "not", "in", "is", "if", "else"))
        private fun eat(type: IElementType): Boolean {
            if (!isType(type)) return false
            b.advanceLexer(); return true
        }
        private fun tagName(): String? = if (isType(DbtTypes.STMT_OPEN)) b.lookAhead(1)?.let {
            var step = 1
            while (b.rawLookup(step) == com.intellij.psi.TokenType.WHITE_SPACE) step++
            b.rawTokenTypeStart(step).let { start ->
                b.originalText.subSequence(start, b.rawTokenTypeStart(step + 1)).toString()
            }
        } else null

        fun body(expected: String?, ancestors: Set<String>, depth: Int) {
            if (depth > 128) {
                b.error("Template nesting is too deep")
                while (!b.eof()) b.advanceLexer()
                return
            }
            while (!b.eof()) {
                val name = tagName()
                if (name == expected && expected != null) { tag(false); return }
                if (name != null && name in ancestors) {
                    b.error("Expected {% $expected %}"); return
                }
                when (b.tokenType) {
                    DbtTypes.EXPR_OPEN -> output()
                    DbtTypes.STMT_OPEN -> {
                        val closing = CLOSING[name]
                        val captureSet = name == "set" && !headerHasAssignment()
                        if (closing != null || captureSet) {
                            val block = b.mark()
                            tag(name == "macro")
                            val end = closing ?: "endset"
                            body(end, ancestors + listOfNotNull(expected), depth + 1)
                            block.done(if (name == "macro") DbtTypes.MACRO else DbtTypes.BLOCK)
                        } else {
                            if (name?.startsWith("end") == true) b.error("Unexpected {% $name %}")
                            else if (name in setOf("else", "elif") && expected !in setOf("endif", "endfor"))
                                b.error("$name requires an enclosing if or for block")
                            tag(false)
                        }
                    }
                    DbtTypes.COMMENT -> {
                        if (b.tokenText?.endsWith("#}") != true) b.error("Unclosed Jinja comment")
                        b.advanceLexer()
                    }
                    else -> b.advanceLexer()
                }
            }
            if (expected != null) b.error("Expected {% $expected %}")
        }

        private fun headerHasAssignment(): Boolean {
            var look = 1
            while (true) {
                val type = b.lookAhead(look) ?: return false
                if (type == DbtTypes.STMT_CLOSE || type in setOf(DbtTypes.EXPR_OPEN, DbtTypes.STMT_OPEN)) return false
                // Assignment is always a single '=' token; comparisons use separate token text.
                if (type == DbtTypes.OPERATOR) {
                    var raw = 1; var significant = 0
                    while (significant < look) {
                        if (b.rawLookup(raw) != com.intellij.psi.TokenType.WHITE_SPACE) significant++
                        if (significant < look) raw++
                    }
                    val from = b.rawTokenTypeStart(raw)
                    val to = b.rawTokenTypeStart(raw + 1)
                    if (b.originalText.subSequence(from, to).toString() == "=") return true
                }
                look++
            }
        }

        private fun tag(macro: Boolean) {
            val tag = b.mark()
            b.advanceLexer() // {%
            val keyword = b.tokenText
            if (b.tokenType == DbtTypes.KEYWORD || b.tokenType == DbtTypes.IDENTIFIER) b.advanceLexer()
            else b.error("Expected a Jinja statement")
            if (macro) {
                name(DbtTypes.DECLARATION_NAME)
                if (eat(DbtTypes.LPAREN)) {
                    while (!b.eof() && !boundary() && !isType(DbtTypes.RPAREN)) {
                        val offset = b.currentOffset
                        name(DbtTypes.PARAMETER_NAME)
                        if (b.tokenText == "=") { b.advanceLexer(); expression(0, 0) }
                        if (!eat(DbtTypes.COMMA) && !isType(DbtTypes.RPAREN)) b.error("Expected ',' or ')'")
                        if (b.currentOffset == offset) b.advanceLexer()
                    }
                    if (!eat(DbtTypes.RPAREN)) b.error("Expected ')'")
                } else b.error("Expected macro parameters")
            }
            when (keyword) {
                "set" -> {
                    name(DbtTypes.BINDING_NAME)
                    // Namespace attribute assignment binds the namespace, not the attribute's name.
                    while (eat(DbtTypes.DOT)) {
                        if (isName()) b.advanceLexer() else b.error("Expected a name")
                    }
                    while (eat(DbtTypes.COMMA)) name(DbtTypes.BINDING_NAME)
                    if (b.tokenText == "=") { b.advanceLexer(); expression(0, 0) }
                }
                "for" -> {
                    name(DbtTypes.BINDING_NAME)
                    while (eat(DbtTypes.COMMA)) name(DbtTypes.BINDING_NAME)
                    if (b.tokenText == "in") { b.advanceLexer(); expression(6, 0) }
                    else b.error("Expected 'in'")
                    if (b.tokenText == "if") { b.advanceLexer(); expression(0, 0) }
                }
                "if", "elif", "do" -> expression(0, 0)
            }
            // Retain unsupported header syntax and recover without losing subsequent tags.
            val brackets = ArrayDeque<IElementType>()
            while (!b.eof() && !boundary()) {
                val type = b.tokenType
                when (type) {
                    DbtTypes.LPAREN -> brackets.addLast(DbtTypes.RPAREN)
                    DbtTypes.LBRACKET -> brackets.addLast(DbtTypes.RBRACKET)
                    DbtTypes.LBRACE -> brackets.addLast(DbtTypes.RBRACE)
                    DbtTypes.RPAREN, DbtTypes.RBRACKET, DbtTypes.RBRACE ->
                        if (brackets.lastOrNull() == type) brackets.removeLast() else b.error("Unexpected closing delimiter")
                    DbtTypes.STRING -> checkString()
                }
                b.advanceLexer()
            }
            if (brackets.isNotEmpty()) b.error("Unclosed delimiter in statement")
            if (!eat(DbtTypes.STMT_CLOSE)) b.error("Expected %}")
            tag.done(DbtTypes.STATEMENT)
        }

        private fun name(type: IElementType) {
            val marker = b.mark()
            if (isName()) b.advanceLexer() else b.error("Expected a name")
            marker.done(type)
        }

        private fun output() {
            val output = b.mark()
            b.advanceLexer()
            expression(0, 0)
            if (!eat(DbtTypes.EXPR_CLOSE)) {
                b.error("Expected }}")
                while (!b.eof() && !boundary()) b.advanceLexer()
                eat(DbtTypes.EXPR_CLOSE)
            }
            output.done(DbtTypes.EXPRESSION)
        }

        private fun boundary() = b.tokenType in setOf(DbtTypes.EXPR_OPEN, DbtTypes.STMT_OPEN, DbtTypes.EXPR_CLOSE, DbtTypes.STMT_CLOSE)

        private fun checkString() {
            val value = b.tokenText ?: return
            var escapes = 0
            var p = value.length - 2
            while (p >= 0 && value[p--] == '\\') escapes++
            if (value.length < 2 || value.last() != value.first() || escapes % 2 == 1) b.error("Unclosed string")
        }

        private fun expression(minimum: Int, depth: Int): PsiBuilder.Marker? {
            if (depth > 128) { b.error("Expression nesting is too deep"); return null }
            if (b.eof() || boundary() || b.tokenType in setOf(DbtTypes.RPAREN, DbtTypes.RBRACKET, DbtTypes.RBRACE, DbtTypes.COMMA, DbtTypes.COLON)) {
                b.error("Expected an expression"); return null
            }
            var left = b.mark()
            when (b.tokenType) {
                DbtTypes.IDENTIFIER -> { b.advanceLexer(); left.done(DbtTypes.VARIABLE) }
                DbtTypes.STRING -> { checkString(); b.advanceLexer(); left.done(DbtTypes.VALUE) }
                DbtTypes.NUMBER -> { b.advanceLexer(); left.done(DbtTypes.VALUE) }
                DbtTypes.KEYWORD -> if (b.tokenText in LITERALS) {
                    b.advanceLexer(); left.done(DbtTypes.VALUE)
                } else if (b.tokenText == "not") {
                    b.advanceLexer(); expression(25, depth + 1); left.done(DbtTypes.UNARY)
                } else if (isName()) {
                    b.advanceLexer(); left.done(DbtTypes.VARIABLE)
                } else { b.error("Expected an expression"); b.advanceLexer(); left.done(DbtTypes.VALUE) }
                DbtTypes.OPERATOR -> if (b.tokenText in setOf("+", "-")) {
                    b.advanceLexer(); expression(65, depth + 1); left.done(DbtTypes.UNARY)
                } else { b.error("Unexpected operator"); b.advanceLexer(); left.done(DbtTypes.VALUE) }
                DbtTypes.LPAREN, DbtTypes.LBRACKET, DbtTypes.LBRACE -> {
                    val close = when (b.tokenType) {
                        DbtTypes.LPAREN -> DbtTypes.RPAREN
                        DbtTypes.LBRACKET -> DbtTypes.RBRACKET
                        else -> DbtTypes.RBRACE
                    }
                    b.advanceLexer()
                    while (!b.eof() && !boundary() && !isType(close)) {
                        val offset = b.currentOffset
                        expression(0, depth + 1)
                        if (eat(DbtTypes.COLON)) expression(0, depth + 1)
                        if (!eat(DbtTypes.COMMA)) break
                        if (b.currentOffset == offset) break
                    }
                    if (!eat(close)) b.error("Expected $close")
                    left.done(DbtTypes.VALUE)
                }
                else -> { b.error("Unexpected token"); b.advanceLexer(); left.done(DbtTypes.VALUE) }
            }
            while (!b.eof() && !boundary()) {
                if (b.tokenType in setOf(DbtTypes.LPAREN, DbtTypes.DOT, DbtTypes.LBRACKET) && minimum <= 90) {
                    val outer = left.precede()
                    when (b.tokenType) {
                        DbtTypes.LPAREN -> {
                            b.advanceLexer()
                            while (!b.eof() && !boundary() && !isType(DbtTypes.RPAREN)) {
                                val argument = b.mark(); val offset = b.currentOffset
                                if (isName() && b.lookAhead(1) == DbtTypes.OPERATOR && tokenAfterIdentifierIsEquals()) {
                                    name(DbtTypes.ARGUMENT_NAME); b.advanceLexer()
                                }
                                expression(0, depth + 1); argument.done(DbtTypes.ARGUMENT)
                                if (!eat(DbtTypes.COMMA)) break
                                if (b.currentOffset == offset) break
                            }
                            if (!eat(DbtTypes.RPAREN)) b.error("Expected ')'")
                            outer.done(DbtTypes.CALL)
                        }
                        DbtTypes.DOT -> {
                            b.advanceLexer(); name(DbtTypes.VARIABLE); outer.done(DbtTypes.ACCESS)
                        }
                        else -> {
                            b.advanceLexer(); expression(0, depth + 1)
                            if (eat(DbtTypes.COLON) && !isType(DbtTypes.RBRACKET)) expression(0, depth + 1)
                            if (!eat(DbtTypes.RBRACKET)) b.error("Expected ']'")
                            outer.done(DbtTypes.ACCESS)
                        }
                    }
                    left = outer; continue
                }
                // Jinja inline conditional: value if condition else alternative. The condition
                // is an or-expression; the alternative associates to the right. A for-header's
                // iterable starts above this precedence so its trailing if remains a loop filter.
                if (b.tokenText == "if" && minimum <= 5) {
                    val outer = left.precede()
                    b.advanceLexer()
                    expression(6, depth + 1)
                    if (b.tokenText == "else") {
                        b.advanceLexer()
                        expression(5, depth + 1)
                    }
                    outer.done(DbtTypes.CONDITIONAL); left = outer
                    continue
                }
                val operator = b.tokenText ?: break
                val precedence = PRECEDENCE[operator] ?: break
                if (precedence < minimum) break
                val outer = left.precede(); b.advanceLexer()
                if ((operator == "is" || operator == "in") && b.tokenText == "not") b.advanceLexer()
                if (operator == "not" && b.tokenText == "in") b.advanceLexer()
                expression(if (operator == "**") precedence else precedence + 1, depth + 1)
                outer.done(DbtTypes.BINARY); left = outer
            }
            return left
        }

        private fun tokenAfterIdentifierIsEquals(): Boolean {
            var raw = 1
            while (b.rawLookup(raw) == com.intellij.psi.TokenType.WHITE_SPACE) raw++
            return b.originalText.subSequence(b.rawTokenTypeStart(raw), b.rawTokenTypeStart(raw + 1)).toString() == "="
        }
    }

    companion object {
        private val CLOSING = DbtBlocks.CLOSING.filterKeys { it != "set" }
        private val LITERALS = setOf("true", "false", "none", "True", "False", "None")
        private val PRECEDENCE = mapOf("or" to 10, "and" to 20, "is" to 30, "in" to 30, "not" to 30,
            "==" to 30, "!=" to 30, "<" to 30, ">" to 30, "<=" to 30, ">=" to 30, "~" to 40,
            "+" to 50, "-" to 50, "*" to 60, "/" to 60, "//" to 60, "%" to 60, "**" to 70, "|" to 80)
    }
}
