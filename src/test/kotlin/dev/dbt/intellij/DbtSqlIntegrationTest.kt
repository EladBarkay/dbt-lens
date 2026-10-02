package dev.dbt.intellij

import com.intellij.lang.Language
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dbt.intellij.lang.DbtFileType
import dev.dbt.intellij.lang.DbtLanguage
import dev.dbt.intellij.sql.DbtSqlViewProvider

/** Run with -PwithSqlTests=true. SQL assertions use the real bundled parser, not a fake SQL language. */
class DbtSqlIntegrationTest : BasePlatformTestCase() {
    override fun tearDown() {
        System.clearProperty("dbt.jinja.experimental.sql")
        super.tearDown()
    }

    private fun sql(): Language {
        assertTrue("SQL integration suite is opt-in", java.lang.Boolean.getBoolean("dbt.jinja.test.sql"))
        val language = Language.findLanguageByID("PostgreSQL")
        assertNotNull("The database plugin must register PostgreSQL", language)
        return language!!
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
        val fixtures = listOf(
            "select customer_id, {{ normalize('status') }} as normalized from customers",
            "select {{ normalize('status') }} as customer_id from customers",
            "select customer_id from {{ ref('orders') }}",
            "select customer_id from t {% if active %} where active = true {% endif %}",
            "select prefix_{{ suffix }} from customers",
            "select customer_id, {{ unfinished("
        )
        for (source in fixtures) {
            val file = myFixture.configureByText(DbtFileType.INSTANCE, source)
            assertTrue(file.viewProvider is DbtSqlViewProvider)
            assertEquals(source, file.node.text)
            val sqlFile = file.viewProvider.getPsi(sql)!!
            assertEquals(source, sqlFile.node.text)
            for (name in listOf("customer_id", "customers")) {
                val offset = source.indexOf(name)
                if (offset >= 0) assertEquals(offset, sqlFile.findElementAt(offset)!!.textRange.startOffset)
            }
        }
    }

    fun testRemovingGeneratedRelationDoesNotPreserveSqlValidity() {
        val sql = sql()
        System.setProperty("dbt.jinja.experimental.sql", "true")
        val file = myFixture.configureByText(DbtFileType.INSTANCE, "select customer_id from {{ ref('orders') }}")
        val sqlFile = file.viewProvider.getPsi(sql)!!
        // This is an expected feasibility failure, so the prototype must remain disabled by default.
        assertNotEmpty(PsiTreeUtil.findChildrenOfType(sqlFile, PsiErrorElement::class.java))
    }
}
