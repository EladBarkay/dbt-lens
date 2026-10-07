package dev.dbt.intellij

import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dbt.intellij.lang.DbtFileType
import dev.dbt.intellij.lang.DbtLanguage
import dev.dbt.intellij.lang.DbtTypes
import dev.dbt.intellij.project.DbtSqlLanguageSubstitutor
import dev.dbt.intellij.sql.DbtSqlViewProvider

/**
 * Run with -PwithSqlTests=true. SQL assertions use the real bundled parser, not a fake SQL
 * language.
 */
class DbtSqlIntegrationTest : BasePlatformTestCase() {
    override fun tearDown() {
        System.clearProperty("dbt.jinja.experimental.sql")
        super.tearDown()
    }

    private fun sql(): Language {
        assertTrue(
            "SQL integration suite is opt-in",
            java.lang.Boolean.getBoolean("dbt.jinja.test.sql"),
        )
        val language = Language.findLanguageByID("PostgreSQL")
        assertNotNull("The database plugin must register PostgreSQL", language)
        return language!!
    }

    fun testNativeEditorSettingPreservesJetBrainsSqlLanguageAndDialect() {
        sql()
        myFixture.addFileToProject("warehouse/dbt_project.yml", "name: warehouse\n")
        val file = myFixture.addFileToProject("warehouse/models/native.sql", "select 1")
        val settings = project.getService(dev.dbt.intellij.config.DbtSettings::class.java)
        settings.preserveNativeSql = true
        assertNull(DbtSqlLanguageSubstitutor().getLanguage(file.virtualFile, project))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertFalse(myFixture.file.language == DbtLanguage)
        assertTrue(myFixture.file.language.isKindOf(Language.findLanguageByID("SQL")!!))
        assertFalse(myFixture.file.viewProvider is DbtSqlViewProvider)
        settings.preserveNativeSql = false
        assertEquals(
            DbtLanguage,
            DbtSqlLanguageSubstitutor().getLanguage(file.virtualFile, project),
        )
    }

    fun testNativeSqlRootIsDisabledByDefault() {
        val sql = sql()
        val file = myFixture.configureByText(DbtFileType.INSTANCE, "select {{ ref('orders') }}")
        assertEquals(setOf(DbtLanguage), file.viewProvider.languages)
        assertNull(file.viewProvider.getPsi(sql))
    }

    fun testBothRootsRetainOriginalSourceAndOffsets() {
        val sql = sql()
        System.setProperty("dbt.jinja.experimental.sql", "true")
        val fixtures =
            listOf(
                "select customer_id, {{ normalize('status') }} as normalized from customers",
                "select {{ normalize('status') }} as customer_id from customers",
                "select customer_id from {{ ref('orders') }}",
                "select customer_id from t {% if active %} where active = true {% endif %}",
                "select prefix_{{ suffix }} from customers",
                "select customer_id, {{ unfinished(",
            )
        for (source in fixtures) {
            val file = myFixture.configureByText(DbtFileType.INSTANCE, source)
            assertTrue(file.viewProvider is DbtSqlViewProvider)
            assertEquals(source, file.node.text)
            val sqlFile = file.viewProvider.getPsi(sql)!!
            assertEquals(source, sqlFile.node.text)
            for (name in listOf("customer_id", "customers")) {
                val offset = source.indexOf(name)
                if (offset >= 0)
                    assertEquals(offset, sqlFile.findElementAt(offset)!!.textRange.startOffset)
            }
        }
    }

    fun testRemovingGeneratedRelationDoesNotPreserveSqlValidity() {
        val sql = sql()
        System.setProperty("dbt.jinja.experimental.sql", "true")
        val file =
            myFixture.configureByText(
                DbtFileType.INSTANCE,
                "select customer_id from {{ ref('orders') }}",
            )
        val sqlFile = file.viewProvider.getPsi(sql)!!
        // This is an expected feasibility failure, so the prototype must remain disabled by
        // default.
        assertNotEmpty(PsiTreeUtil.findChildrenOfType(sqlFile, PsiErrorElement::class.java))
    }

    fun testOrdinarySqlModelAutomaticallyGetsJinjaHighlightingAndCompletion() {
        sql()
        myFixture.addFileToProject("warehouse/dbt_project.yml", "name: warehouse\nversion: '1.0'\n")
        val file =
            myFixture.addFileToProject(
                "warehouse/models/orders.sql",
                "{% macro normalize(value) %}{% endmacro %}{% macro normalizer(value) %}{% endmacro %}select {{ norm }}",
            )
        assertEquals("SQL", file.virtualFile.fileType.name)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertEquals(DbtLanguage, myFixture.file.language)
        assertEquals(DbtLanguage, myFixture.file.viewProvider.baseLanguage)
        val iterator = (myFixture.editor as EditorEx).highlighter.createIterator(0)
        val tokens = mutableSetOf<com.intellij.psi.tree.IElementType>()
        while (!iterator.atEnd()) {
            tokens += iterator.tokenType
            iterator.advance()
        }
        assertTrue(
            "The actual SQL editor must use the Jinja highlighter",
            DbtTypes.EXPR_OPEN in tokens,
        )
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("norm }}") + 4)
        assertContainsElements(
            myFixture.completeBasic().map { it.lookupString },
            "normalize",
            "normalizer",
        )
    }

    fun testSqlOutsideDbtAndGeneratedSqlKeepNativeSqlLanguage() {
        sql()
        val outside = myFixture.addFileToProject("queries/report.sql", "select 1")
        myFixture.addFileToProject("warehouse/dbt_project.yml", "name: warehouse\n")
        val compiled =
            myFixture.addFileToProject("warehouse/target/compiled/orders.sql", "select 1")
        val substitutor = DbtSqlLanguageSubstitutor()
        assertNull(substitutor.getLanguage(outside.virtualFile, project))
        assertNull(substitutor.getLanguage(compiled.virtualFile, project))
        myFixture.configureFromExistingVirtualFile(outside.virtualFile)
        assertFalse(myFixture.file.language == DbtLanguage)
        myFixture.configureFromExistingVirtualFile(compiled.virtualFile)
        assertFalse(myFixture.file.language == DbtLanguage)
    }

    fun testProjectDetectionUpdatesWhenConfigIsCreatedAndRemoved() {
        sql()
        val file =
            myFixture.addFileToProject("warehouse/models/orders.sql", "select {{ ref('orders') }}")
        val substitutor = DbtSqlLanguageSubstitutor()
        assertNull(substitutor.getLanguage(file.virtualFile, project))
        val config = myFixture.addFileToProject("warehouse/dbt_project.yml", "name: warehouse\n")
        assertEquals(DbtLanguage, substitutor.getLanguage(file.virtualFile, project))
        ApplicationManager.getApplication().runWriteAction { config.virtualFile.delete(this) }
        assertNull(substitutor.getLanguage(file.virtualFile, project))
    }
}
