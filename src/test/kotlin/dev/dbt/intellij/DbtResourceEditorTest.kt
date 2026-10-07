package dev.dbt.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dbt.intellij.config.DbtYamlIconProvider
import dev.dbt.intellij.psi.DbtVariable
import dev.dbt.intellij.resources.DbtResourceNavigationHandler
import dev.dbt.intellij.resources.DbtValue

class DbtResourceEditorTest : BasePlatformTestCase() {
    private fun open(path: String, text: String) {
        val file = myFixture.addFileToProject(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
    }

    private fun calls() = PsiTreeUtil.findChildrenOfType(myFixture.file, DbtVariable::class.java)

    private fun targets(call: PsiElement) =
        DbtResourceNavigationHandler()
            .getGotoDeclarationTargets(call.firstChild, call.textOffset, myFixture.editor)
            .orEmpty()

    fun testLiteralCallsAndArgumentsNavigateToModelsSeedsAndSnapshotDeclarations() {
        myFixture.addFileToProject("warehouse/dbt_project.yml", "name: warehouse\n")
        val model = myFixture.addFileToProject("warehouse/models/orders.sql", "select 1")
        val python =
            myFixture.addFileToProject(
                "warehouse/models/python_orders.py",
                "def model(dbt, session): pass",
            )
        val seed = myFixture.addFileToProject("warehouse/seeds/countries.csv", "name\nSpain")
        val snapshot =
            myFixture.addFileToProject(
                "warehouse/snapshots/history.sql",
                "{# {% snapshot fake %} #}\n{% snapshot order_history %}select 1{% endsnapshot %}",
            )
        open(
            "warehouse/models/usage.sql",
            "{{ ref('orders') }} {{ ref('countries') }} {{ ref('order_history') }} {{ ref('python_orders') }}",
        )
        val expected = listOf(model, seed, snapshot, python)
        val refs = calls().filter { it.text == "ref" }
        refs.forEachIndexed { i, call ->
            assertEquals(expected[i].virtualFile, targets(call).single().containingFile.virtualFile)
        }
        assertEquals(snapshot.text.indexOf("order_history"), targets(refs[2]).single().textOffset)
        val literals =
            PsiTreeUtil.findChildrenOfType(myFixture.file, DbtValue::class.java).filter {
                it.reference != null
            }
        assertEquals(4, literals.size)
        literals.forEachIndexed { i, value ->
            assertEquals(
                expected[i].virtualFile,
                value.reference!!.resolve()!!.containingFile.virtualFile,
            )
        }
        assertSame(DbtYamlIconProvider.SQL_ICON, DbtYamlIconProvider().getIcon(myFixture.file, 0))
        open("other/ordinary.sql", "select 1")
        assertNull(DbtYamlIconProvider().getIcon(myFixture.file, 0))
    }

    fun testDependenciesCustomPathsAmbiguityAndUnsavedConfigurationEdits() {
        val config =
            myFixture.addFileToProject(
                "warehouse/dbt_project.yml",
                "name: warehouse\nmodel-paths:\n  - transformations\n",
            )
        val own = myFixture.addFileToProject("warehouse/transformations/orders.sql", "select 1")
        myFixture.addFileToProject("warehouse/models/ignored.sql", "select 1")
        myFixture.addFileToProject(
            "warehouse/vendor/unrelated/dbt_project.yml",
            "name: unrelated\n",
        )
        myFixture.addFileToProject("warehouse/vendor/unrelated/models/hidden.sql", "select 1")
        myFixture.addFileToProject("warehouse/dbt_packages/utils/dbt_project.yml", "name: utils\n")
        val dep =
            myFixture.addFileToProject("warehouse/dbt_packages/utils/models/orders.sql", "select 2")
        open(
            "warehouse/transformations/usage.sql",
            "{{ ref('orders') }} {{ ref('utils', 'orders') }} {{ ref('ignored') }} {{ ref('hidden') }}",
        )
        var refs = calls().filter { it.text == "ref" }
        assertEquals(own.virtualFile, targets(refs[0]).single().containingFile.virtualFile)
        assertEquals(dep.virtualFile, targets(refs[1]).single().containingFile.virtualFile)
        refs.drop(2).forEach { assertEmpty(targets(it).toList()) }
        myFixture.addFileToProject("warehouse/transformations/duplicate/orders.sql", "select 3")
        assertEquals(2, targets(refs[0]).size)
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project)
                .getDocument(config)!!
                .setText("name: warehouse\nmodel-paths: [models]\n")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        refs = calls().filter { it.text == "ref" }
        assertEquals(dep.virtualFile, targets(refs[0]).single().containingFile.virtualFile)
        assertEquals("ignored.sql", targets(refs[2]).single().containingFile.name)
    }

    fun testDynamicShadowedIncompleteAndOutOfProjectCallsRemainUnresolved() {
        myFixture.addFileToProject("warehouse/dbt_project.yml", "name: warehouse\n")
        myFixture.addFileToProject("warehouse/models/orders.sql", "select 1")
        open(
            "warehouse/models/usage.sql",
            "{% macro m(ref) %}{{ ref('orders') }}{% endmacro %} {{ ref(name) }} {{ ref('ord' ~ 'ers') }} {{ ref('orders', v=2) }} {{ obj.ref('orders') }} {{ ref('orders'",
        )
        calls().filter { it.text == "ref" }.forEach { assertEmpty(targets(it).toList()) }
        open("other/usage.jinja", "{{ ref('orders') }}")
        assertEmpty(targets(calls().single()).toList())
    }
}
