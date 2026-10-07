package dev.dbt.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.resources.DbtValue

class DbtDispatchYamlTest : BasePlatformTestCase() {
    private fun macro(packagePath: String, name: String): DbtMacro {
        return PsiTreeUtil.findChildOfType(myFixture.addFileToProject("$packagePath/macros/$name.jinja", "{% macro $name() %}{% endmacro %}"), DbtMacro::class.java)!!
    }
    private fun setup(config: String): com.intellij.psi.PsiFile {
        val file = myFixture.addFileToProject("warehouse/dbt_project.yml", config)
        myFixture.addFileToProject("warehouse/dbt_packages/utils/dbt_project.yml", "name: utils\n")
        myFixture.addFileToProject("warehouse/dbt_packages/shim/dbt_project.yml", "name: shim\n")
        return file
    }
    private fun open(path: String = "warehouse/models/use.jinja") {
        val file = myFixture.addFileToProject(path, "{{ adapter.dispatch('format_value', 'utils') }}")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
    }
    private fun names(): List<String> {
        val value = PsiTreeUtil.findChildrenOfType(myFixture.file, DbtValue::class.java).single { it.text == "'format_value'" }
        return (value.reference as PsiPolyVariantReference).multiResolve(false).map { it.element!!.containingFile.virtualFile.path }
    }
    fun testLiteralSearchOrderOverridesDefaultPackagesAndPreservesAllAdapterOptions() {
        setup("name: warehouse\ndispatch:\n  - macro_namespace: utils\n    search_order: ['shim', 'utils']\n")
        macro("warehouse", "default__format_value")
        val shim = macro("warehouse/dbt_packages/shim", "default__format_value")
        val def = macro("warehouse/dbt_packages/utils", "default__format_value")
        val snow = macro("warehouse/dbt_packages/utils", "snowflake__format_value")
        open()
        assertEquals(listOf(shim, def, snow).map { it.containingFile.virtualFile.path }, names())
        assertEquals(1, com.intellij.psi.search.searches.ReferencesSearch.search(shim).findAll().size)
    }
    fun testBlockSearchOrderUpdatesFromCommittedUnsavedRootConfig() {
        val config = setup("name: warehouse\ndispatch:\n  - macro_namespace: utils\n    search_order:\n      - utils\n      - shim\n")
        val def = macro("warehouse/dbt_packages/utils", "default__format_value")
        val shim = macro("warehouse/dbt_packages/shim", "default__format_value")
        open()
        assertEquals(listOf(def, shim).map { it.containingFile.virtualFile.path }, names())
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project).getDocument(config)!!.setText("name: warehouse\ndispatch:\n  - macro_namespace: utils\n    search_order: [shim]\n")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(listOf(shim.containingFile.virtualFile.path), names())
    }
    fun testInstalledPackageDispatchConfigIsIgnoredWhenNavigatingInsideDependency() {
        setup("name: warehouse\n")
        val packageConfig = com.intellij.psi.PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("warehouse/dbt_packages/utils/dbt_project.yml"))!!
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project).getDocument(packageConfig)!!.setText("name: utils\ndispatch:\n  - macro_namespace: utils\n    search_order: [shim]\n")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val local = macro("warehouse", "default__format_value")
        val def = macro("warehouse/dbt_packages/utils", "default__format_value")
        macro("warehouse/dbt_packages/shim", "default__format_value")
        open("warehouse/dbt_packages/utils/macros/use.jinja")
        assertEquals(listOf(local, def).map { it.containingFile.virtualFile.path }, names())
    }
    fun testDynamicAliasDuplicateAndEmptySearchOrdersAreNotGuessed() {
        val config = setup("name: warehouse\n")
        macro("warehouse/dbt_packages/utils", "default__format_value")
        open()
        for (order in listOf("'{{ var(\"order\") }}'", "*order", "[]", "[utils, '{{ var(\"pkg\") }}']")) {
            WriteCommandAction.runWriteCommandAction(project) {
                PsiDocumentManager.getInstance(project).getDocument(config)!!.setText("name: warehouse\ndispatch:\n  - macro_namespace: utils\n    search_order: $order\n")
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertEmpty(names())
        }
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project).getDocument(config)!!.setText("name: warehouse\ndispatch:\n  - macro_namespace: utils\n    search_order: [utils]\n  - macro_namespace: utils\n    search_order: [utils]\n")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEmpty(names())
    }
}
