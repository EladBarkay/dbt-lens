package dev.dbt.intellij

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.editor.FoldRegion
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dbt.intellij.lang.DbtFileType
import dev.dbt.intellij.psi.LocalMacros

class DbtStructureEditorTest : BasePlatformTestCase() {
    private fun parse(text: String) = myFixture.configureByText(DbtFileType.INSTANCE, text)
    private fun targets(offset: Int): List<PsiElement> =
        GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, offset)?.toList() ?: emptyList()
    private fun folds(): List<FoldRegion> {
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
        return myFixture.editor.foldingModel.allFoldRegions.toList()
    }

    fun testEveryClosingTagNavigatesToItsOpeningDeclaration() {
        val cases = mapOf(
            "macro name(a)" to "endmacro", "if condition" to "endif", "for row in rows" to "endfor",
            "set query" to "endset", "call render()" to "endcall", "filter upper" to "endfilter",
            "block content" to "endblock", "raw" to "endraw", "with value=1" to "endwith",
            "snapshot example" to "endsnapshot", "test example(model)" to "endtest",
            "materialization example, default" to "endmaterialization"
        )
        for ((header, closing) in cases) {
            val source = "{%- $header -%}\n  select 1\n{%- $closing -%}"
            parse(source)
            val target = targets(source.lastIndexOf(closing) + 2).single()
            assertEquals(if (closing == "endmacro") source.indexOf("name") else source.indexOf(header), target.textOffset)
        }
    }

    fun testNestedClosingTagsSelectTheCorrectEnclosureWithoutAddingMacroUsages() {
        val source = """
            {% macro outer(rows) %}
              {% for row in rows %}
                {% if row %}
                  {% for inner in row %}select 1{% endfor %}
                {% else %}select 2{% endif %}
              {% endfor %}
            {% endmacro %}
            {{ outer([]) }}
        """.trimIndent()
        val file = parse(source)
        assertEquals(source.indexOf("for inner"), targets(source.indexOf("endfor") + 2).single().textOffset)
        assertEquals(source.indexOf("for row"), targets(source.lastIndexOf("endfor") + 2).single().textOffset)
        assertEquals(source.indexOf("if row"), targets(source.indexOf("endif") + 2).single().textOffset)
        val macro = LocalMacros.declarations(file).single()
        assertEquals(macro, targets(source.indexOf("endmacro") + 2).single())
        assertEquals("Closing tags do not become semantic macro usages", 1, ReferencesSearch.search(macro).findAll().size)
        parse("{% macro broken() %}\nselect 1\n{% endfor %}")
        assertEmpty(targets(myFixture.file.text.indexOf("endfor") + 2))
    }

    fun testMultilineCommentFoldsPreserveDelimitersAndIgnoreStrings() {
        val source = """
            {# first line
               second line #}
            {#- trimmed
                comment -#}
            /* SQL comment
               second line */
            select '/* string
                    literal */'
            {# one line #}
            /* one line */
        """.trimIndent()
        parse(source)
        val regions = folds()
        assertEquals(3, regions.size)
        val interiors = regions.map { source.substring(it.startOffset, it.endOffset) }
        assertTrue(interiors.any { it.contains("first line") && !it.contains("{#") })
        assertTrue(interiors.any { it.contains("trimmed") && !it.contains("-#}") })
        assertTrue(interiors.any { it.contains("SQL comment") && !it.contains("/*") })
        for (region in regions) {
            myFixture.editor.foldingModel.runBatchFoldingOperation { region.isExpanded = false }
            assertFalse(region.isExpanded)
        }
        val before = regions.map { it.startOffset to it.endOffset }.toSet()
        myFixture.doHighlighting()
        assertEquals(before, folds().map { it.startOffset to it.endOffset }.toSet())
        parse("{# unfinished\ncomment")
        assertEmpty(folds())
        parse("/* unfinished\ncomment")
        assertEmpty(folds())
    }

    fun testFirstLineControlFoldsMultilineHeaderAndWholeBody() {
        val cases = mapOf(
            "macro process(first,\n  second,\n  third=options(\n    enabled=true\n  ))" to "endmacro",
            "if ready(\n  value\n)" to "endif",
            "for row in rows(\n  value\n)" to "endfor"
        )
        for ((header, closing) in cases) {
            val source = "{% $header %}\n  select 123\n{% $closing %}"
            parse(source)
            val regions = folds()
            val expectedStart = if (closing == "endmacro") source.indexOf("%}") + 2 else source.indexOf('\n')
            val firstLine = regions.filter { it.startOffset == expectedStart }
            assertEquals("One primary block control, no competing signature folds", 1, firstLine.size)
            val block = firstLine.single()
            assertEquals(expectedStart, block.startOffset)
            assertEquals(source.indexOf("{% $closing"), block.endOffset)
            myFixture.editor.foldingModel.runBatchFoldingOperation { block.isExpanded = false }
            assertTrue("Macro/loop/conditional body is collapsed", myFixture.editor.foldingModel.isOffsetCollapsed(source.indexOf("select 123")))
            myFixture.editor.foldingModel.runBatchFoldingOperation { block.isExpanded = true }
            assertFalse(myFixture.editor.foldingModel.isOffsetCollapsed(source.indexOf("select 123")))
        }
    }
}
