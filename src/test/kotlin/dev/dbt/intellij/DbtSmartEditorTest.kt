package dev.dbt.intellij

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dbt.intellij.editor.DbtAnnotator
import dev.dbt.intellij.lang.DbtFileType
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtBinding
import dev.dbt.intellij.psi.DbtVariable
import dev.dbt.intellij.resources.ResourceCall
import dev.dbt.intellij.semantic.DbtSymbols
import dev.dbt.intellij.semantic.DbtTypeInference

class DbtSmartEditorTest : BasePlatformTestCase() {
    private fun open(source: String) = myFixture.configureByText(DbtFileType.INSTANCE, source)

    private fun variables() =
        PsiTreeUtil.findChildrenOfType(myFixture.file, DbtVariable::class.java)

    private fun variants(source: String): Set<String> {
        open(source)
        return myFixture.completeBasic()?.map { it.lookupString }?.toSet().orEmpty()
    }

    private fun has(source: String, vararg expected: String) {
        val variants = variants(source)
        expected.forEach { assertTrue("Expected $it, got $variants", it in variants) }
    }

    private fun absent(source: String, vararg names: String) {
        val variants = variants(source)
        names.forEach { assertFalse("Unexpected $it in $variants", it in variants) }
    }

    fun testVariableFindUsagesAndDeclarationHighlightingRespectMacroScope() {
        open(
            "{% macro a(value) %}{% set data = value %}{{ data }}{{ data }}{{ value }}{% endmacro %}{% macro b(value) %}{{ value }}{% endmacro %}"
        )
        val bindings = PsiTreeUtil.findChildrenOfType(myFixture.file, DbtBinding::class.java)
        val data = bindings.single { it.text == "data" }
        val value = bindings.first { it.text == "value" }
        assertEquals(2, ReferencesSearch.search(data).findAll().size)
        assertEquals(2, ReferencesSearch.search(value).findAll().size)
        myFixture.editor.caretModel.moveToOffset(data.textOffset)
        HighlightUsagesHandler.invoke(project, myFixture.editor, myFixture.file)
        val highlighted =
            myFixture.editor.markupModel.allHighlighters.map { it.startOffset }.toSet()
        variables()
            .filter { it.text == "data" }
            .forEach {
                assertTrue(
                    "Usage at ${it.textOffset}, highlights $highlighted",
                    it.textOffset in highlighted,
                )
            }
    }

    fun testFindUsagesIncludesReassignmentsButExcludesNestedShadowing() {
        open(
            "{% macro a(value) %}{{ value }}{% set value = 'x' %}{{ value }}{% for value in [] %}{{ value }}{% endfor %}{{ value }}{% endmacro %}"
        )
        val bindings =
            PsiTreeUtil.findChildrenOfType(myFixture.file, DbtBinding::class.java).filter {
                it.text == "value"
            }
        val original =
            ReferencesSearch.search(bindings[0]).findAll().map { it.element.textOffset }.toSet()
        val reassigned =
            ReferencesSearch.search(bindings[1]).findAll().map { it.element.textOffset }.toSet()
        assertEquals(4, original.size)
        assertEquals(original, reassigned)
        assertEquals(1, ReferencesSearch.search(bindings[2]).findAll().size)
        assertEquals(bindings[1], variables().last { it.text == "value" }.reference!!.resolve())
    }

    fun testLoopWithAndConditionalScopes() {
        open(
            "{% set root = 1 %}{% for row in [] %}{{ row }}{% endfor %}{{ row }}{% with root = 'x', value = 1 %}{{ root }}{{ value }}{% endwith %}{{ root }}{{ value }}"
        )
        val row = variables().filter { it.text == "row" }
        assertNotNull(row[0].reference?.resolve())
        assertNull(row[1].reference)
        val root = variables().filter { it.text == "root" }
        assertTrue(
            root[0].reference!!.resolve()!!.textOffset != root[1].reference!!.resolve()!!.textOffset
        )
        val value = variables().filter { it.text == "value" }
        assertNotNull(value[0].reference?.resolve())
        assertNull(value[1].reference)
        open(
            "{% if ready %}{% set choice = 'x' %}{% else %}{% set choice = 1 %}{% endif %}{{ choice }}"
        )
        val reference =
            variables().single { it.text == "choice" }.reference
                as com.intellij.psi.PsiPolyVariantReference
        assertEquals(2, reference.multiResolve(false).size)
    }

    fun testTypedTagPairsAndOvertyping() {
        for ((opening, closing) in listOf("{%" to "%}", "{{" to "}}", "{#" to "#}")) {
            open("")
            myFixture.type(opening)
            assertEquals(opening + closing, myFixture.editor.document.text)
            assertEquals(2, myFixture.editor.caretModel.offset)
            myFixture.type(closing)
            assertEquals(opening + closing, myFixture.editor.document.text)
            assertEquals(4, myFixture.editor.caretModel.offset)
        }
    }

    fun testPairsAvoidStringsRawAndOtherFileTypes() {
        open("{{ 'a<caret>' }}")
        myFixture.type("{%")
        assertEquals("{{ 'a{%' }}", myFixture.editor.document.text)
        open("{% raw %}<caret>{% endraw %}")
        myFixture.type("{{")
        assertEquals("{% raw %}{{{% endraw %}", myFixture.editor.document.text)
        myFixture.configureByText("normal.txt", "<caret>")
        myFixture.type("{%")
        assertEquals("{%", myFixture.editor.document.text)
    }

    fun testAcceptedMacroCompletionCreatesEditableBlockTemplate() {
        com.intellij.codeInsight.template.impl.TemplateManagerImpl.setTemplateTesting(
            testRootDisposable
        )
        open("{% mac<caret>%}")
        myFixture.completeBasic()
        myFixture.lookup?.let { myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR) }
        assertEquals(
            "{% macro macro_name() %}\n    \n{% endmacro %}",
            myFixture.editor.document.text,
        )
        assertNotNull(TemplateManager.getInstance(project).getActiveTemplate(myFixture.editor))
        assertEquals("macro_name", myFixture.editor.selectionModel.selectedText)
        TemplateManager.getInstance(project).finishTemplate(myFixture.editor)
    }

    fun testOtherAcceptedBlockTemplatesProduceBalancedEditableSyntax() {
        com.intellij.codeInsight.template.impl.TemplateManagerImpl.setTemplateTesting(
            testRootDisposable
        )
        for (keyword in listOf("if", "for", "call", "with", "raw", "docs", "materialization")) {
            open("{% $keyword<caret>%}")
            myFixture.completeBasic()
            myFixture.lookup?.let { lookup ->
                lookup.currentItem = lookup.items.single { it.lookupString == keyword }
                myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            }
            assertTrue(
                "$keyword: ${myFixture.editor.document.text}",
                myFixture.editor.document.text.contains("{% end$keyword %}"),
            )
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java))
            TemplateManager.getInstance(project).finishTemplate(myFixture.editor)
        }
    }

    fun testEnterAddsOnlyMissingStructuralCloser() {
        open("{% macro a(\n  arg\n) %}<caret>")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        assertEquals(
            "{% macro a(\n  arg\n) %}\n    \n{% endmacro %}",
            myFixture.editor.document.text,
        )
        assertEquals(
            myFixture.editor.document.text.indexOf("\n    ") + 5,
            myFixture.editor.caretModel.offset,
        )
        open("{% macro a() %}<caret>\n{% endmacro %}")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        assertEquals(1, Regex("endmacro").findAll(myFixture.editor.document.text).count())
        open("{% set x = 1 %}<caret>")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        assertFalse(myFixture.editor.document.text.contains("endset"))
        open("{% set text %}<caret>")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        assertTrue(myFixture.editor.document.text.contains("endset"))
    }

    fun testSetCompletionDoesNotCreateCaptureBlockOrSkipCaret() {
        open("{% se<caret>%}")
        myFixture.completeBasic()
        val lookup = myFixture.lookup!!
        lookup.currentItem = lookup.items.single { it.lookupString == "set" }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("{% set %}", myFixture.editor.document.text)
        assertEquals(7, myFixture.editor.caretModel.offset)
    }

    fun testShadowedBuiltinsAndOtherMacroScopesRemainIndependent() {
        open("{% macro ref(name) %}{{ return('custom') }}{% endmacro %}{{ ref('model') }}")
        val ref = variables().single { it.text == "ref" }
        assertNull(DbtTypeInference.symbol(ref))
        assertNull(ResourceCall.from(ref.parent))
        open(
            "{% macro helper() %}{% endmacro %}{% macro a(helper) %}{{ helper }}{% endmacro %}{% macro b() %}{{ helper() }}{% endmacro %}"
        )
        assertTrue(
            variables().last { it.text == "helper" }.reference?.resolve()
                is dev.dbt.intellij.psi.DbtMacro
        )
    }

    fun testLocalCompletionIncludesSetsLoopsWithAndParams() {
        has(
            "{% macro a(param) %}{% set text = 'x' %}{% for row in [] %}{{ <caret> }}{% endfor %}{% endmacro %}",
            "param",
            "text",
            "row",
            "loop",
        )
        absent("{% for row in [] %}{% endfor %}{{ <caret> }}", "row", "loop")
        absent("{% for row in [] %}{% else %}{{ <caret> }}{% endfor %}", "row", "loop")
        has("{% set outer = 'x' %}{% macro a() %}{{ <caret> }}{% endmacro %}", "outer")
        has("{% with local = 'x' %}{{ <caret> }}{% endwith %}", "local")
        absent("{% with local = 'x' %}{% endwith %}{{ <caret> }}", "local")
    }

    fun testDictionaryStringListAndLiteralMembers() {
        has("{% set data = {'name': 'Ada'} %}{{ data.<caret> }}", "get", "items", "name")
        has("{% set name = 'Ada' %}{{ name.<caret> }}", "split", "strip", "upper")
        has("{% set values = [1, 2] %}{{ values.<caret> }}", "append", "sort", "pop")
        has("{{ 'Ada'.<caret> }}", "split", "lower")
        absent("{{ unknown.<caret> }}", "ref", "get", "split", "macro")
        has("{% set data = {'name': 'Ada'} %}{{ data.get('name').<caret> }}", "split")
        has("{% set data = {'name': 'Ada'} %}{{ data['name'].<caret> }}", "split")
    }

    fun testDefaultArgumentAliasesLoopAndCaptureInference() {
        has("{% macro a(name='Ada') %}{{ name.<caret> }}{% endmacro %}", "split")
        has(
            "{% set names = ['Ada'] %}{% set alias = names %}{% for name in alias %}{{ name.<caret> }}{% endfor %}",
            "split",
        )
        has("{% set text %}SQL{% endset %}{{ text.<caret> }}", "split")
        has(
            "{% set data = {'name': 'Ada'} %}{% for key, value in data.items() %}{{ value.<caret> }}{% endfor %}",
            "split",
        )
        has("{% set name, data = ('Ada', {}) %}{{ name.<caret> }}", "split")
        has("{% set name, data = ('Ada', {}) %}{{ data.<caret> }}", "get")
        has("{% for row in [] %}{{ loop.<caret> }}{% endfor %}", "index", "cycle", "changed")
    }

    fun testDbtClassesAndChainedContextMembers() {
        has("{{ api.<caret> }}", "Relation", "Column")
        // The sole create suggestion may be inserted automatically.
        val create = variants("{{ api.Relation.<caret> }}")
        assertTrue(
            "$create / ${myFixture.editor.document.text}",
            "create" in create || myFixture.editor.document.text.contains("api.Relation.create("),
        )
        has("{{ api.Relation.create(identifier='x').<caret> }}", "identifier", "include", "quote")
        has("{{ graph.nodes.<caret> }}", "get", "items")
        has("{{ adapter.<caret> }}", "get_relation", "get_columns_in_relation", "dispatch")
        has("{% set relation = ref('orders') %}{{ relation.<caret> }}", "database", "include")
    }

    fun testFiltersTestsAndKeywordArguments() {
        has("{{ rows | <caret> }}", "map", "sort", "selectattr", "is_list")
        absent("{{ rows | <caret> }}", "ref", "graph", "macro")
        has("{{ rows is not <caret> }}", "defined", "mapping", "sequence")
        absent("{{ rows is <caret> }}", "ref", "sort", "graph")
        has(
            "{% macro a(value, options=None) %}{% endmacro %}{{ a(<caret>) }}",
            "value=",
            "options=",
        )
        absent(
            "{% macro a(value, options=None) %}{% endmacro %}{{ a(value=1, <caret>) }}",
            "value=",
        )
        absent("{% macro a(value, options=None) %}{% endmacro %}{{ a(1, <caret>) }}", "value=")
        has("{% macro a(value, options=None) %}{% endmacro %}{{ a(1, <caret>) }}", "options=")
        has("{{ rows | map(<caret>) }}", "attribute=", "default=")
        has("{{ api.Relation.create(<caret>) }}", "identifier=", "schema=")
        assertEmpty(
            DbtSymbols.members(dev.dbt.intellij.semantic.DbtType.DICT)
                .single { it.name == "get" }
                .parameters
        )
        has("{{ ref(<caret>) }}", "version=", "v=")
        absent("{{ source(<caret>) }}", "source_name=", "table_name=")
        has("{{ env_var(<caret>) }}", "var=", "default=")
    }

    fun testCompletionExcludesStringsCommentsRawAndSql() {
        absent("{{ '<caret>' }}", "ref", "graph")
        absent("{# <caret> #}", "ref", "graph")
        absent("{% raw %}{{ <caret> }}{% endraw %}", "ref", "graph")
        absent("select <caret>", "ref", "graph")
    }

    fun testBuiltinHoverAndApiClassColors() {
        open("{{ api.Relation.create(identifier='x') }} {{ data | map(attribute='name') }}")
        val create = variables().single { it.text == "create" }
        val docs = DocumentationManager.getProviderFromElement(create).generateDoc(create, create)!!
        assertTrue(docs.contains("create(database=None, schema=None, identifier=None, type=None)"))
        assertTrue(docs.contains("reference/dbt-classes"))
        val map = variables().single { it.text == "map" }
        assertTrue(
            DocumentationManager.getProviderFromElement(map)
                .generateDoc(map, map)!!
                .contains("attribute=None")
        )
        val colors = myFixture.doHighlighting()
        val relation = variables().single { it.text == "Relation" }
        assertTrue(
            colors.any {
                it.startOffset == relation.textOffset &&
                    it.forcedTextAttributesKey == DbtAnnotator.DECLARATION
            }
        )
        val api = variables().single { it.text == "api" }
        assertTrue(
            colors.any {
                it.startOffset == api.textOffset &&
                    it.forcedTextAttributesKey == DbtAnnotator.GLOBAL
            }
        )
    }

    fun testDocumentedSyntaxParsesWithoutLosingText() {
        val source =
            """
            {%+ macro a(arg=1_000) +%}
            {{ arg[1:3:2] }}{{ arg[:3] }}{{ arg[::2] }}
            {{ fn(*args, **kwargs) }}{{ arg is divisibleby 3 }}
            {% with name = 'x', other = {} %}{{ name }}{% endwith %}
            {% call(user) render() %}{{ user }}{% endcall %}
            {% set captured | replace(old='x', new='y') %}x{% endset %}
            {% for row in rows %}{% if row %}{% break %}{% endif %}{% continue %}{% endfor %}
            {% endmacro %}
            {% test positive(model, column) %}{{ model }}{% endtest %}
            {% docs overview %}text{% enddocs %}
            """
                .trimIndent()
        open(source)
        assertEquals(source, myFixture.file.text)
        assertEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java))
        assertEquals(
            "string",
            DbtTypeInference.of(
                    PsiTreeUtil.findChildrenOfType(myFixture.file, DbtBinding::class.java).single {
                        it.text == "captured"
                    }
                )
                .name,
        )
        open("{{ ref(*'orders') }} {{ source(**'tables') }}")
        PsiTreeUtil.collectElements(myFixture.file) { it.node.elementType == T.CALL }
            .forEach { assertNull(ResourceCall.from(it)) }
    }
}
