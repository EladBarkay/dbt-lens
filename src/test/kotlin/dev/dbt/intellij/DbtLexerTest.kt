package dev.dbt.intellij

import dev.dbt.intellij.lang.DbtLexer
import dev.dbt.intellij.lang.DbtTypes as T
import org.junit.Assert.*
import org.junit.Test

class DbtLexerTest {
    private data class Token(val type: String, val text: String, val start: Int, val end: Int, val state: Int)
    private fun tokens(source: String, offset: Int = 0, state: Int = 0): List<Token> {
        val lexer = DbtLexer(); lexer.start(source, offset, source.length, state)
        return buildList {
            while (lexer.tokenType != null) {
                add(Token(lexer.tokenType.toString(), source.substring(lexer.tokenStart, lexer.tokenEnd), lexer.tokenStart, lexer.tokenEnd, lexer.state))
                assertTrue("Lexer must make progress", lexer.tokenEnd > lexer.tokenStart)
                lexer.advance()
            }
        }
    }

    @Test fun `all source characters are retained and states restart at every token`() {
        val cases = listOf(
            "select {{- normalize('}}', x=1) -}} from {{ ref('orders') }}",
            "{% macro m(a, b='x') %}{# comment #}{{ a + b }}{% endmacro %}",
            "{% raw %}{{ not_jinja }} {% if x %}{% endraw %}{{ real() }}",
            "select {{ m(\"escaped \\\" quote\", {'x': [1, 2]}) }}",
            "{% if x %}{{ broken( {% endif %}", "{{ missing", "{# missing", "{% raw %}{{ missing"
        )
        for (source in cases) {
            val all = tokens(source)
            assertEquals(source, all.joinToString("") { it.text })
            all.forEachIndexed { index, token -> assertEquals("Restart at $token", all.drop(index), tokens(source, token.start, token.state)) }
        }
    }

    @Test fun `raw tags and string delimiters do not create false expressions`() {
        val raw = tokens("{% raw %}{{ ignored }}{% endraw %}{{ '{{' }}")
        assertEquals(1, raw.count { it.type == T.EXPR_OPEN.toString() })
        assertTrue(raw.any { it.type == T.TEXT.toString() && it.text == "{{ ignored }}" })
        assertTrue(raw.any { it.type == T.STRING.toString() && it.text == "'{{'" })
    }

    @Test fun `incomplete input never loops or drops characters`() {
        val source = "{% macro normalize(x='a') %}select {{ normalize(x) }}{% endmacro %}"
        for (length in 0..source.length) {
            val prefix = source.take(length)
            assertEquals(prefix, tokens(prefix).joinToString("") { it.text })
        }
    }
}
