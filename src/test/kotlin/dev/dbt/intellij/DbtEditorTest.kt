package dev.dbt.intellij

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dbt.intellij.editor.DbtSyntaxHighlighter
import dev.dbt.intellij.lang.DbtFileType
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtVariable
import dev.dbt.intellij.psi.LocalMacros

class DbtEditorTest : BasePlatformTestCase() {
    private fun parse(text: String) = myFixture.configureByText(DbtFileType.INSTANCE, text)
    private fun errors(text: String): List<String> = PsiTreeUtil.findChildrenOfType(parse(text), PsiErrorElement::class.java).map { it.errorDescription }

    fun testRunningExampleAndNestedBlocks() {
        val source = """
            {{ config(materialized='incremental') }}
            {% set status_column = 'status' %}
            select customer_id, {{ analytics.normalize_status(status_column) }} as normalized_status
            from {{ ref('stg_customers') }}
            {% if is_incremental() %}
            where updated_at > (select max(updated_at) from {{ this }})
            {% endif %}
        """.trimIndent()
        assertEmpty(errors(source))
        assertEquals(source, myFixture.file.node.text)
        assertEmpty(errors("{% for x in rows %}{% if x is not none %}{{ x|upper }}{% else %}null{% endif %}{% endfor %}"))
        assertEmpty(errors("{{ config(materialization='view') }} {{ obj.filter(test=1e-3) }}"))
    }

    fun testMacroSignatureCallsAndLocalNavigation() {
        val file = parse("{% macro normalize(value, fallback='unknown') %}{{ value }}{% endmacro %}select {{ normalize('x') }}")
        val macro = LocalMacros.declarations(file).single()
        assertEquals("normalize", macro.name)
        assertEquals(listOf("value", "fallback"), macro.parameters)
        val reference = PsiTreeUtil.findChildrenOfType(file, DbtVariable::class.java).mapNotNull { it.reference }.single()
        assertEquals(macro, reference.resolve())
        assertEquals(file.text.indexOf("normalize('x')"), reference.element.textOffset)
        assertEquals(1, ReferencesSearch.search(macro).findAll().size)
    }

    fun testAmbiguityAndQualifiedCallsAreNotMisresolved() {
        val file = parse("{% macro m() %}{% endmacro %}{% macro m(x) %}{% endmacro %}{{ m() }} {{ pkg.m() }} {{ adapter.dispatch('m')() }}")
        val calls = PsiTreeUtil.findChildrenOfType(file, DbtVariable::class.java).mapNotNull { it.reference }
        assertEquals(1, calls.size)
        assertNull(calls.single().resolve())
        assertEquals(2, (calls.single() as com.intellij.psi.PsiPolyVariantReference).multiResolve(false).size)
    }

    fun testIncompleteExpressionRecoversAtNextTag() {
        val file = parse("select {{ broken( {% macro later(x) %}{{ x }}{% endmacro %}{{ later(1) }}")
        assertEquals(listOf("later"), LocalMacros.declarations(file).map { it.name })
        assertNotEmpty(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java))
        assertEquals(file.viewProvider.contents.toString(), file.node.text)
    }

    fun testParametersAndFiltersDoNotResolveAsMacros() {
        val file = parse("{% macro m() %}{% endmacro %}{% macro other(m) %}{{ m() }}{% endmacro %}{{ 'x'|m() }}")
        val calls = PsiTreeUtil.findChildrenOfType(file, DbtVariable::class.java).mapNotNull { it.reference }
        assertEquals(1, calls.size)
        assertNull(calls.single().resolve())
        val assigned = parse("{% macro m() %}{% endmacro %}{% set m = callback %}{{ m() }}")
        assertNull(PsiTreeUtil.findChildrenOfType(assigned, DbtVariable::class.java).mapNotNull { it.reference }.single().resolve())
        val filtered = parse("{% macro m() %}{% endmacro %}{{ m()|upper }} {{ 'x'|replace(m()) }} {{ 'x'|m()() }}")
        val resolved = PsiTreeUtil.findChildrenOfType(filtered, DbtVariable::class.java).mapNotNull { it.reference?.resolve() }
        assertEquals(2, resolved.size)
    }

    fun testUnclosedBlocksAndWrongClosersAreDiagnosed() {
        assertTrue(errors("{% if x %}select 1").any { it.contains("endif") })
        assertTrue(errors("{% endfor %}").any { it.contains("Unexpected") })
        assertTrue(errors("{{ f('unclosed) }}").any { it.contains("Unclosed string") })
        assertEmpty(errors("{% raw %}{{ arbitrary( {% nonsense %}{% endraw %}"))
        assertEmpty(errors("{% set content %}{{ 'x' }}{% endset %}"))
    }

    fun testPrefixEditsAlwaysRetainText() {
        val source = "{% macro m(x, y='a') %}{{ m(x, y=y) }}{% endmacro %}"
        for (length in 0..source.length) {
            val prefix = source.take(length)
            assertEquals(prefix, parse(prefix).node.text)
        }
    }

    fun testCompletionInJinjaAndNotInSqlOrStrings() {
        parse("{% macro normalize(value) %}{% endmacro %}{% macro normalizer(value) %}{% endmacro %}select {{ norm<caret> }}")
        assertContainsElements(myFixture.completeBasic().map { it.lookupString }, "normalize")
        parse("select unrelated<caret>")
        assertEquals(0, myFixture.completeBasic()?.size ?: 0)
        parse("{{ ref('norm<caret>') }}")
        assertEquals(0, myFixture.completeBasic()?.size ?: 0)
    }

    fun testSqlAndJinjaHighlightingRetainsOffsets() {
        val source = "select {{ ref('orders') }} -- comment\nfrom t"
        val lexer = DbtSyntaxHighlighter().highlightingLexer
        lexer.start(source)
        val ranges = mutableListOf<Pair<Int, Int>>()
        val types = mutableSetOf<com.intellij.psi.tree.IElementType>()
        while (lexer.tokenType != null) {
            ranges += lexer.tokenStart to lexer.tokenEnd; types += lexer.tokenType!!; lexer.advance()
        }
        assertEquals(source, ranges.joinToString("") { source.substring(it.first, it.second) })
        assertTrue(types.containsAll(setOf(T.SQL_KEYWORD, T.EXPR_OPEN, T.IDENTIFIER, T.STRING, T.SQL_COMMENT)))
    }
}
