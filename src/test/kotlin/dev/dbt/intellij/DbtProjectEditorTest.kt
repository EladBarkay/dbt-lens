package dev.dbt.intellij

import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.ExtensionTestUtil
import dev.dbt.intellij.editor.DbtAnnotator
import dev.dbt.intellij.lang.DbtLanguage
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtVariable
import dev.dbt.intellij.psi.LocalMacros
import dev.dbt.intellij.project.DbtSqlFileTypeDetector

class DbtProjectEditorTest : BasePlatformTestCase() {
    fun testDependencyConfigurationMatchesRequestedEdition() {
        if (java.lang.Boolean.getBoolean("dbt.jinja.test.sql")) assertNotNull(Language.findLanguageByID("SQL"))
        else assertNull("Database Tools must actually be disabled", Language.findLanguageByID("SQL"))
        if (java.lang.Boolean.getBoolean("dbt.jinja.test.textmate")) assertNotNull(Language.findLanguageByID("textmate"))
        else assertNull("TextMate must actually be disabled", Language.findLanguageByID("textmate"))
    }
    private fun root(name: String = "elementary") = myFixture.addFileToProject("warehouse/dbt_project.yml", "name: \"$name\"\n")
    private fun open(path: String, source: String): com.intellij.psi.PsiFile {
        val file = myFixture.addFileToProject(path, source)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.file
    }
    private fun assertEditor() {
        assertEquals("Host: ${myFixture.file.virtualFile.fileType.name} (${myFixture.file.virtualFile.fileType.javaClass.name})", DbtLanguage, myFixture.file.language)
        val iterator = (myFixture.editor as EditorEx).highlighter.createIterator(0)
        val tokens = mutableSetOf<com.intellij.psi.tree.IElementType>()
        while (!iterator.atEnd()) { tokens += iterator.tokenType; iterator.advance() }
        assertTrue("Actual editor uses Jinja tokens", T.EXPR_OPEN in tokens)
        assertTrue("Actual editor also colors SQL", T.SQL_KEYWORD in tokens)
    }
    private fun withSqlType(type: com.intellij.openapi.fileTypes.FileType, action: () -> Unit) {
        val manager = FileTypeManager.getInstance()
        val previous = manager.getFileTypeByExtension("sql")
        ApplicationManager.getApplication().runWriteAction { manager.associateExtension(type, "sql") }
        try { action() } finally {
            ApplicationManager.getApplication().runWriteAction {
                manager.removeAssociatedExtension(type, "sql")
                manager.associateExtension(previous, "sql")
            }
        }
    }
    fun testPlainTextSqlWithoutDatabaseTools() = withSqlType(PlainTextFileType.INSTANCE) {
        ExtensionTestUtil.maskExtensions(FileTypeRegistry.FileTypeDetector.EP_NAME,
            FileTypeRegistry.FileTypeDetector.EP_NAME.extensionList.filter { it !is DbtSqlFileTypeDetector }, testRootDisposable)
        root()
        val model = open("warehouse/models/orders.sql", "select {{ ref('orders') }}")
        // TextMate may detect SQL even when its extension is associated with plain text.
        // The -PwithTextmateTests=false run verifies the real plain-text editor path.
        if (Language.findLanguageByID("textmate") == null) assertEquals(PlainTextFileType.INSTANCE, model.virtualFile.fileType)
        assertEditor()
        val outside = open("other/report.sql", "select {{ ref('orders') }}")
        assertFalse(outside.language == DbtLanguage)
        val compiled = open("warehouse/target/compiled/orders.sql", "select {{ ref('orders') }}")
        assertFalse(compiled.language == DbtLanguage)
    }
    fun testRealTextmateHostUsesJinjaEditor() {
        val type = Language.findLanguageByID("textmate")?.associatedFileType
        assertNotNull("Real bundled TextMate must be enabled for the free-edition regression", type)
        withSqlType(type!!) {
            root()
            open("warehouse/macros/cast_column.sql", "{% macro normalize(x) %}select {{ x }}{% endmacro %}{% macro normalizer(x) %}{% endmacro %} {{ norm }}")
            assertEquals("textmate", myFixture.file.virtualFile.fileType.name)
            assertEditor()
            myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("norm }}") + 4)
            assertContainsElements(myFixture.completeBasic().map { it.lookupString }, "normalize")
        }
    }
    fun testOrdinarySqlUsesAutomaticDetectionWithoutFileTypeOverrides() {
        root()
        open("warehouse/macros/automatic.sql", "select {{ ref('orders') }}")
        assertEditor()
        val outside = open("other/automatic.sql", "select 1")
        assertFalse(outside.language == DbtLanguage)
        assertEquals(FileTypeManager.getInstance().getFileTypeByFileName("automatic.sql"), outside.virtualFile.fileType)
        val compiled = open("warehouse/target/compiled/automatic.sql", "select 1")
        assertFalse(compiled.language == DbtLanguage)
    }
    fun testQualifiedAndUnqualifiedProjectMacroNavigationAndUsages() = withSqlType(PlainTextFileType.INSTANCE) {
        root()
        val declaration = myFixture.addFileToProject("warehouse/macros/types.sql", "{% macro edr_type_timestamp() %}timestamp{% endmacro %}")
        val file = open("warehouse/macros/cast_column.sql", "{% macro cast_column(column) %}cast({{ column }} as {{ elementary.edr_type_timestamp() }}) {{ edr_type_timestamp() }}{% endmacro %}")
        val calls = PsiTreeUtil.findChildrenOfType(file, DbtVariable::class.java).filter { it.text == "edr_type_timestamp" }
        assertEquals(2, calls.size)
        val macro = LocalMacros.declarations(declaration).single()
        calls.forEach { assertEquals(macro, it.reference?.resolve()) }
        assertEquals(2, ReferencesSearch.search(macro).findAll().size)
        val info = myFixture.doHighlighting()
        assertTrue(info.any { it.forcedTextAttributesKey == DbtAnnotator.CALL && it.startOffset == calls.first().textOffset })
    }
    fun testMacroNavigationInStatementExpressions() {
        root()
        val file = open("warehouse/macros/agate_to_dicts.sql", """
            {% macro agate_to_dicts(agate_table) %}
              {% set rows = namespace(data=none) %}
              {% if elementary.agate_val_serialize(agate_table) %}
                {% for col_value in elementary.agate_val_serialize(agate_table) %}
                  {% set serializable_col_value = elementary.agate_val_serialize(col_value) %}
                  {% do elementary.agate_val_serialize(col_value) %}
                {% endfor %}
              {% elif elementary.agate_val_serialize(agate_table) %}select 1{% endif %}
            {% endmacro %}
            {% macro agate_val_serialize(val) %}{{ return(val) }}{% endmacro %}
        """.trimIndent())
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, com.intellij.psi.PsiErrorElement::class.java))
        val macro = LocalMacros.declarations(file).single { it.name == "agate_val_serialize" }
        val calls = PsiTreeUtil.findChildrenOfType(file, DbtVariable::class.java).filter { it.text == macro.name }
        assertEquals(5, calls.size)
        calls.forEach { assertEquals(macro, it.reference?.resolve()) }
        assertEquals(5, ReferencesSearch.search(macro).findAll().size)
        myFixture.doHighlighting()
        assertNotNull("SQL file has an icon", file.fileType.icon)
        val closing = file.text.lastIndexOf("endmacro") + 2
        val targets = com.intellij.codeInsight.navigation.actions.GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, closing)
        assertEquals("Closing-tag navigation works in automatically activated SQL", macro, targets!!.single())
        assertNotNull("Project-tree file type has an icon", file.virtualFile.fileType.icon)
        com.intellij.codeInsight.folding.CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
        assertTrue("Automatically activated .sql editor folds nested blocks",
            myFixture.editor.foldingModel.allFoldRegions.size >= 3)
        // Explicit local bindings still shadow package names, including in statement calls.
        val shadowed = open("warehouse/macros/shadowed.sql",
            "{% macro other(elementary) %}{% do elementary.agate_val_serialize(1) %}{% endmacro %}")
        assertEmpty(PsiTreeUtil.findChildrenOfType(shadowed, DbtVariable::class.java).mapNotNull { it.reference?.resolve() })
    }
    fun testProjectCompletionAndNamespaceCompletion() = withSqlType(PlainTextFileType.INSTANCE) {
        root()
        myFixture.addFileToProject("warehouse/macros/types.sql", "{% macro edr_type_timestamp() %}timestamp{% endmacro %}")
        myFixture.addFileToProject("warehouse/macros/more.sql", "{% macro edr_type_text() %}text{% endmacro %}")
        for (prefix in listOf("edr_type", "elementary.edr_type")) {
            open("warehouse/models/${prefix.replace('.', '_')}.sql", "select {{ $prefix }}")
            myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf(" }}"))
            assertContainsElements(myFixture.completeBasic().map { it.lookupString }, "edr_type_timestamp", "edr_type_text")
        }
    }
    fun testNestedDbtProjectsAndShadowedNamespacesDoNotMisresolve() = withSqlType(PlainTextFileType.INSTANCE) {
        root()
        myFixture.addFileToProject("warehouse/integration/dbt_project.yml", "name: integration\n")
        myFixture.addFileToProject("warehouse/integration/macros/hidden.sql", "{% macro hidden() %}{% endmacro %}")
        myFixture.addFileToProject("warehouse/macros/types.sql", "{% macro edr_type_timestamp() %}{% endmacro %}")
        val file = open("warehouse/macros/cast_column.sql", "{% macro cast_column(elementary) %}{{ elementary.edr_type_timestamp() }}{% endmacro %}{{ hidden() }} {{ adapter.dispatch('edr_type_timestamp', 'elementary')() }}")
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, DbtVariable::class.java).mapNotNull { it.reference?.resolve() })
    }
    fun testDuplicateProjectMacrosStayAmbiguousAndRawTextIsNotIndexedAsAMacro() = withSqlType(PlainTextFileType.INSTANCE) {
        root()
        myFixture.addFileToProject("warehouse/macros/first.sql", "{% macro repeated() %}{% endmacro %}")
        myFixture.addFileToProject("warehouse/macros/second.sql", "{% macro repeated() %}{% endmacro %}")
        myFixture.addFileToProject("warehouse/macros/literals.sql", "{# {% macro commented() %} #}{% raw %}{% macro raw_name() %}{% endmacro %}{% endraw %}")
        val file = open("warehouse/models/orders.sql", "{{ elementary.repeated() }} {{ commented() }} {{ raw_name() }}")
        val calls = PsiTreeUtil.findChildrenOfType(file, DbtVariable::class.java).associateBy { it.text }
        val reference = calls["repeated"]!!.reference as com.intellij.psi.PsiPolyVariantReference
        assertNull(reference.resolve())
        assertEquals(2, reference.multiResolve(false).size)
        assertNull(calls["commented"]!!.reference!!.resolve())
        assertNull(calls["raw_name"]!!.reference!!.resolve())
    }
    fun testConfiguredNamespaceTracksUnsavedProjectNameEdits() = withSqlType(PlainTextFileType.INSTANCE) {
        val config = root()
        myFixture.addFileToProject("warehouse/macros/types.sql", "{% macro type_name() %}{% endmacro %}")
        val file = open("warehouse/models/orders.sql", "{{ elementary.type_name() }} {{ renamed.type_name() }}")
        fun resolved() = PsiTreeUtil.findChildrenOfType(file, DbtVariable::class.java).filter { it.text == "type_name" }
            .map { it.reference?.resolve() }
        assertNotNull(resolved()[0])
        assertNull(resolved()[1])
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(config)!!.setText("name: renamed\n")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertNull(resolved()[0])
        assertNotNull(resolved()[1])
    }
    fun testIndexTracksMacroEditsAndDeletion() = withSqlType(PlainTextFileType.INSTANCE) {
        root()
        val declaration = myFixture.addFileToProject("warehouse/macros/type.sql", "{% macro old_type() %}{% endmacro %}")
        val file = open("warehouse/models/orders.sql", "{{ old_type() }} {{ new_type() }}")
        fun calls() = PsiTreeUtil.findChildrenOfType(file, DbtVariable::class.java).associateBy { it.text }
        assertNotNull(calls()["old_type"]!!.reference!!.resolve())
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(declaration)!!.setText("{% macro new_type() %}{% endmacro %}")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertNull(calls()["old_type"]!!.reference!!.resolve())
        assertNotNull(calls()["new_type"]!!.reference!!.resolve())
        ApplicationManager.getApplication().runWriteAction { declaration.virtualFile.delete(this) }
        assertNull(calls()["new_type"]!!.reference!!.resolve())
    }
}
