package dev.dbt.intellij

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dbt.intellij.editor.DbtAnnotator
import dev.dbt.intellij.lang.DbtFileType
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.psi.DbtVariable

class DbtFeatureEditorTest : BasePlatformTestCase() {
    private fun open(path: String, source: String): com.intellij.psi.PsiFile {
        val file = myFixture.addFileToProject(path, source)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.file
    }
    private fun calls() = PsiTreeUtil.findChildrenOfType(myFixture.file, DbtVariable::class.java)
    private fun root(config: String = "name: warehouse\n") = myFixture.addFileToProject("warehouse/dbt_project.yml", config)

    fun testMultilineInlineConditionalsAndFilteredLoopsRetainExpressionStructure() {
        val source = """
            {% set metadata_hashes = (
                metadata_hashes
                if metadata_hashes is not none
                else elementary.get_artifacts_hashes_for_model(
                    table_relation.name
                )
            ) %}
            {{ first if enabled else second if ready else fallback }}
            {{ first if enabled }}
            {% for row in rows if row.active %}{{ row }}{% endfor %}
            {% for row in (rows if enabled else []) if row.active %}{{ row }}{% endfor %}
        """.trimIndent()
        val file = myFixture.configureByText(DbtFileType.INSTANCE, source)
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java))
        assertEquals(source, file.node.text)
        assertEquals(5, PsiTreeUtil.collectElements(file) { it.node.elementType == T.CONDITIONAL }.size)
        for (length in 0..source.indexOf("}}") + 2) {
            val prefix = source.take(length)
            assertEquals(prefix, myFixture.configureByText(DbtFileType.INSTANCE, prefix).node.text)
        }
    }

    fun testCollapsedMultilineMacroDisplaysClosingSignatureAndTag() {
        val source = "{%- macro upload_artifacts_to_table(\n  table_relation,\n  hashes=None\n) -%}\n  select 1\n{%- endmacro -%}"
        myFixture.configureByText(DbtFileType.INSTANCE, source)
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
        val region = myFixture.editor.foldingModel.allFoldRegions.single { it.startOffset == source.indexOf('\n') }
        myFixture.editor.foldingModel.runBatchFoldingOperation { region.isExpanded = false }
        val visible = source.take(region.startOffset) + region.placeholderText + source.substring(region.endOffset)
        assertEquals("{%- macro upload_artifacts_to_table(…) -%} …{%- endmacro -%}", visible)
        assertTrue(myFixture.editor.foldingModel.isOffsetCollapsed(source.indexOf("select 1")))
    }

    fun testDependencyNavigationCompletionAndDocumentationInExcludedCustomInstallDirectory() {
        root("name: warehouse\npackages-install-path: vendor\n")
        val config = myFixture.addFileToProject("warehouse/vendor/dbt_utils/dbt_project.yml", "name: dbt_utils\n")
        myFixture.addFileToProject("warehouse/vendor/dbt_utils/macros/helper.sql",
            "{% macro serialize(value) %}\n{# Converts <value> & preserves nulls. #}\n{{ return(value) }}\n{% endmacro %}")
        myFixture.addFileToProject("warehouse/unrelated/dbt_project.yml", "name: unrelated\n")
        myFixture.addFileToProject("warehouse/unrelated/hidden.sql", "{% macro hidden() %}{% endmacro %}")
        ModuleRootModificationUtil.updateModel(module) { model -> model.contentEntries.first().addExcludeFolder(config.virtualFile.parent.parent.url) }
        open("warehouse/models/usage.jinja", "{{ dbt_utils.serialize(1) }} {{ serialize(2) }} {{ unrelated.hidden() }} {{ 'x'|dbt_utils.serialize() }} {{ 'x' is dbt_utils.serialize() }}")
        val serialize = calls().filter { it.text == "serialize" }
        serialize.take(2).forEach { assertTrue(it.reference?.resolve() is DbtMacro) }
        serialize.drop(2).forEach { assertNull("Filters/tests use a separate namespace", it.reference) }
        assertEquals("helper.sql", serialize.first().reference!!.resolve()!!.containingFile.name)
        assertNull(calls().single { it.text == "hidden" }.reference)
        val docs = DocumentationManager.getProviderFromElement(serialize.first()).generateDoc(serialize.first(), serialize.first())!!
        assertTrue(docs.contains("macro serialize(value)"))
        assertTrue(docs.contains("Converts &lt;value&gt; &amp; preserves nulls."))
        open("warehouse/models/completion.jinja", "{{ dbt_utils.ser }}")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf(" }}"))
        val variants = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue("Dependency completion: $variants", "serialize" in variants || myFixture.editor.document.text.contains("dbt_utils.serialize"))
        // Opening an excluded dependency itself still supports sibling-package navigation.
        open("warehouse/vendor/dbt_utils/macros/usage.sql", "{{ dbt_utils.serialize(1) }}")
        assertTrue(calls().single { it.text == "serialize" }.reference?.resolve() is DbtMacro)
    }

    fun testOwnProjectWinsUnqualifiedCallsAndDependencyUpdatesAreVisible() {
        root()
        myFixture.addFileToProject("warehouse/dbt_packages/utils/dbt_project.yml", "name: utils\n")
        val dependency = myFixture.addFileToProject("warehouse/dbt_packages/utils/m.jinja", "{% macro value() %}{% endmacro %}")
        myFixture.addFileToProject("warehouse/macros/m.jinja", "{% macro value() %}{% endmacro %}")
        open("warehouse/models/usage.jinja", "{{ value() }} {{ utils.value() }} {{ utils.renamed() }}")
        val values = calls().filter { it.text == "value" }
        assertTrue(values[0].reference!!.resolve()!!.containingFile.virtualFile.path.contains("/macros/"))
        assertEquals(dependency, values[1].reference!!.resolve()!!.containingFile)
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project).getDocument(dependency)!!.setText("{% macro renamed() %}{% endmacro %}")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertNull(values[1].reference!!.resolve())
        assertNotNull(calls().single { it.text == "renamed" }.reference!!.resolve())
    }

    fun testParameterSetAndLoopNavigationRespectLexicalScope() {
        val source = """
            {% macro example(rows) %}
              {% set value = rows %}
              {% for row in rows %}{{ row }} {{ value }}{% endfor %}
              {{ row }} {{ rows }}
            {% endmacro %}
        """.trimIndent()
        myFixture.configureByText(DbtFileType.INSTANCE, source)
        val rows = calls().filter { it.text == "rows" }
        rows.forEach { assertEquals(source.indexOf("rows"), it.reference!!.resolve()!!.textOffset) }
        val row = calls().filter { it.text == "row" }
        assertEquals(source.indexOf("row in"), row[0].reference!!.resolve()!!.textOffset)
        assertNull(row[1].reference)
        assertEquals(source.indexOf("value ="), calls().single { it.text == "value" }.reference!!.resolve()!!.textOffset)
        val overrides = "{% macro example(rows, upper) %}{% set rows.data = [] %}{{ rows }}{{ 'x'|upper }}{% for row in rows %}{% set row = 1 %}{{ row }}{% endfor %}{% endmacro %}"
        myFixture.configureByText(DbtFileType.INSTANCE, overrides)
        calls().filter { it.text == "rows" }.forEach { assertEquals(overrides.indexOf("rows"), it.reference!!.resolve()!!.textOffset) }
        assertNull(calls().single { it.text == "upper" }.reference)
        assertEquals(overrides.indexOf("row ="), calls().single { it.text == "row" }.reference!!.resolve()!!.textOffset)
    }

    fun testHoverCommentsAndColorCategoriesRespectLocalGlobals() {
        root()
        open("warehouse/macros/docs.jinja", """
            {% macro example(value) %}
              /* Reads <value>.
                 Keeps SQL docs. */
              {{ value }} {{ graph.nodes }} {{ example(value) }}
            {% endmacro %}
            {% macro shadow(graph) %}{{ graph }}{% endmacro %}
            {{ example(1) }}
        """.trimIndent())
        val call = calls().last { it.text == "example" }
        val docs = DocumentationManager.getProviderFromElement(call).generateDoc(call, call)!!
        assertTrue(docs.contains("Reads &lt;value&gt;."))
        val colors = myFixture.doHighlighting()
        assertTrue(colors.any { it.forcedTextAttributesKey == DbtAnnotator.CALL && it.startOffset == call.textOffset })
        val graph = calls().filter { it.text == "graph" }
        assertTrue(colors.any { it.forcedTextAttributesKey == DbtAnnotator.GLOBAL && it.startOffset == graph[0].textOffset })
        assertTrue(colors.any { it.forcedTextAttributesKey == DbtAnnotator.VARIABLE && it.startOffset == graph[1].textOffset })
    }
}
