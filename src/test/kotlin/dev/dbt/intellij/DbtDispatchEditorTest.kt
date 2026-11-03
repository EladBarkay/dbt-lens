package dev.dbt.intellij

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dbt.intellij.dispatch.DispatchNavigationHandler
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.psi.DbtVariable
import dev.dbt.intellij.resources.DbtValue

class DbtDispatchEditorTest : BasePlatformTestCase() {
    private fun root() =
        myFixture.addFileToProject("warehouse/dbt_project.yml", "name: warehouse\n")

    private fun dependency() =
        myFixture.addFileToProject("warehouse/dbt_packages/utils/dbt_project.yml", "name: utils\n")

    private fun macro(path: String, name: String): DbtMacro =
        PsiTreeUtil.findChildOfType(
            myFixture.addFileToProject(path, "{% macro $name(value) %}{{ value }}{% endmacro %}"),
            DbtMacro::class.java,
        )!!

    private fun open(source: String, path: String = "warehouse/models/usage.jinja") {
        val file = myFixture.addFileToProject(path, source)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
    }

    private fun literals() =
        PsiTreeUtil.findChildrenOfType(myFixture.file, DbtValue::class.java).filter {
            it.reference != null
        }

    private fun resolved(value: DbtValue) =
        (value.reference as PsiPolyVariantReference).multiResolve(false).map { it.element }

    private fun dispatches() =
        PsiTreeUtil.findChildrenOfType(myFixture.file, DbtVariable::class.java).filter {
            it.text == "dispatch"
        }

    private fun nav(variable: DbtVariable) =
        DispatchNavigationHandler()
            .getGotoDeclarationTargets(variable.firstChild, variable.textOffset, myFixture.editor)
            .orEmpty()
            .toList()

    fun testLiteralAndKeywordDispatchCallsOfferPackageOrderedImplementationChoices() {
        root()
        dependency()
        val local = macro("warehouse/macros/override.jinja", "default__format_value")
        val default =
            macro("warehouse/dbt_packages/utils/macros/default.jinja", "default__format_value")
        val snowflake =
            macro("warehouse/dbt_packages/utils/macros/snowflake.jinja", "snowflake__format_value")
        val postgres =
            macro("warehouse/dbt_packages/utils/macros/postgres.jinja", "postgres__format_value")
        open(
            "{{ adapter.dispatch('format_value', 'utils')(1) }} {{ adapter.dispatch(macro_name='format_value', macro_namespace='utils') }}"
        )
        val expected = listOf(local, default, postgres, snowflake)
        literals().forEach { assertEquals(expected, resolved(it)) }
        assertEquals(2, literals().size) // Namespace strings are not duplicate macro usages.
        dispatches().forEach { assertEquals(expected, nav(it)) }
        val value = literals().first()
        assertEquals(
            expected,
            GotoDeclarationAction.findAllTargetElements(
                    project,
                    myFixture.editor,
                    value.textOffset + 2,
                )!!
                .toList(),
        )
    }

    fun testFindUsagesIncludesDispatchStringsAndDirectImplementationCalls() {
        root()
        dependency()
        val default =
            macro("warehouse/dbt_packages/utils/macros/default.jinja", "default__format_value")
        val snowflake =
            macro("warehouse/dbt_packages/utils/macros/snowflake.jinja", "snowflake__format_value")
        open(
            "{{ adapter.dispatch('format_value', 'utils')(1) }} {{ utils.snowflake__format_value(2) }} {{ utils.default__format_value(3) }} {{ 'format_value' }} {# {{ adapter.dispatch('format_value', 'utils') }} #}"
        )
        for (target in listOf(default, snowflake)) {
            val refs = ReferencesSearch.search(target).findAll()
            assertEquals("${target.name}: ${refs.map { it.element.text }}", 2, refs.size)
            assertTrue(refs.any { it.element is DbtValue })
            assertTrue(refs.any { it.element is DbtVariable })
        }
    }

    fun testDispatchUsagesDoNotCrossNamedPackageFamilies() {
        root()
        dependency()
        myFixture.addFileToProject("warehouse/dbt_packages/other/dbt_project.yml", "name: other\n")
        val utils =
            macro("warehouse/dbt_packages/utils/macros/default.jinja", "default__format_value")
        val other =
            macro("warehouse/dbt_packages/other/macros/default.jinja", "default__format_value")
        open(
            "{{ adapter.dispatch('format_value', 'utils') }} {{ adapter.dispatch('format_value', 'other') }}"
        )
        assertEquals(listOf(utils), resolved(literals()[0]))
        assertEquals(listOf(other), resolved(literals()[1]))
        assertEquals(1, ReferencesSearch.search(utils).findAll().size)
        assertEquals(1, ReferencesSearch.search(other).findAll().size)
    }

    fun testImplementationDeclarationNavigatesToDefaultInItsOwnPackage() {
        root()
        dependency()
        val local = macro("warehouse/macros/default.jinja", "default__format_value")
        val default =
            macro("warehouse/dbt_packages/utils/macros/default.jinja", "default__format_value")
        val snowflake =
            macro("warehouse/dbt_packages/utils/macros/snowflake.jinja", "snowflake__format_value")
        myFixture.configureFromExistingVirtualFile(snowflake.containingFile.virtualFile)
        val targets =
            GotoDeclarationAction.findAllTargetElements(
                project,
                myFixture.editor,
                snowflake.textOffset + 2,
            )
        assertEquals(listOf(default), targets!!.toList())
        assertFalse(targets.contains(local))
        assertNull(
            DispatchNavigationHandler()
                .getGotoDeclarationTargets(
                    default.nameIdentifier!!.firstChild,
                    default.textOffset + 2,
                    myFixture.editor,
                )
        )
    }

    fun testRootAdapterOverrideWithoutOwnDefaultOffersDependencyDefault() {
        root()
        dependency()
        val default =
            macro("warehouse/dbt_packages/utils/macros/default.jinja", "default__format_value")
        val snowflake = macro("warehouse/macros/snowflake.jinja", "snowflake__format_value")
        myFixture.configureFromExistingVirtualFile(snowflake.containingFile.virtualFile)
        assertEquals(
            listOf(default),
            GotoDeclarationAction.findAllTargetElements(
                    project,
                    myFixture.editor,
                    snowflake.textOffset + 2,
                )!!
                .toList(),
        )
    }

    fun testUnqualifiedCallsAndExcludedCustomPackagesAndOpeningDependencyFiles() {
        val config =
            myFixture.addFileToProject(
                "warehouse/dbt_project.yml",
                "name: warehouse\npackages-install-path: vendor\n",
            )
        val dep =
            myFixture.addFileToProject("warehouse/vendor/utils/dbt_project.yml", "name: utils\n")
        val default = macro("warehouse/vendor/utils/macros/default.jinja", "default__format_value")
        val snowflake =
            macro("warehouse/vendor/utils/macros/snowflake.jinja", "snowflake__format_value")
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.contentEntries.first().addExcludeFolder(dep.virtualFile.parent.parent.url)
        }
        open("{{ adapter.dispatch('format_value') }} {{ adapter.dispatch('format_value', none) }}")
        literals().forEach { assertEquals(listOf(default, snowflake), resolved(it)) }
        open(
            "{{ adapter.dispatch('format_value', 'utils') }}",
            "warehouse/vendor/utils/macros/use.jinja",
        )
        assertEquals(listOf(default, snowflake), resolved(literals().single()))
        assertNotNull(config)
    }

    fun testDynamicShadowedMalformedAndNonDbtDispatchRemainUnresolved() {
        root()
        dependency()
        macro("warehouse/dbt_packages/utils/macros/default.jinja", "default__format_value")
        val source =
            """
            {{ adapter.dispatch(name, 'utils') }} {{ adapter.dispatch('format_value', namespace) }}
            {{ adapter.dispatch('utils.format_value') }} {{ adapter.dispatch(*args) }}
            {{ adapter.dispatch('format_value', 'utils', packages=['utils']) }}
            {{ adapter.dispatch('format_value', macro_name='format_value') }}
            {{ other.dispatch('format_value', 'utils') }}
            {% macro shadow(adapter) %}{{ adapter.dispatch('format_value', 'utils') }}{% endmacro %}
            {% set adapter = {} %}{{ adapter.dispatch('format_value', 'utils') }}
            """
                .trimIndent()
        open(source)
        assertEmpty(literals())
        dispatches().forEach { assertEmpty(nav(it)) }
        open("{{ adapter.dispatch('format_value', 'utils') }}", "other/use.jinja")
        assertEmpty(literals())
        assertEmpty(nav(dispatches().single()))
        open("{{ adapter.dispatch('format_value', 'utils'", "warehouse/models/broken.jinja")
        assertEmpty(literals())
    }

    fun testCustomOrderWithoutYamlSupportIsNotGuessed() {
        myFixture.addFileToProject(
            "warehouse/dbt_project.yml",
            "name: warehouse\n'dispatch':\n  - macro_namespace: utils\n    search_order: [utils]\n",
        )
        dependency()
        val default =
            macro("warehouse/dbt_packages/utils/macros/default.jinja", "default__format_value")
        open("{{ adapter.dispatch('format_value', 'utils') }}")
        if (dev.dbt.intellij.dispatch.DispatchConfiguration.EP.extensionList.isEmpty())
            assertEmpty(resolved(literals().single()))
        else assertEquals(listOf(default), resolved(literals().single()))
    }

    fun testCommittedUnsavedImplementationEditsAndPackageIsolation() {
        root()
        dependency()
        val default =
            macro("warehouse/dbt_packages/utils/macros/default.jinja", "default__format_value")
        macro("warehouse/macros/unrelated.jinja", "default__other")
        myFixture.addFileToProject("warehouse/unrelated/dbt_project.yml", "name: unrelated\n")
        macro("warehouse/unrelated/default.jinja", "default__format_value")
        open("{{ adapter.dispatch('format_value', 'utils') }}")
        assertEquals(listOf(default), resolved(literals().single()))
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project)
                .getDocument(default.containingFile)!!
                .setText("{% macro snowflake__format_value() %}{% endmacro %}")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(
            "snowflake__format_value",
            (resolved(literals().single()).single() as DbtMacro).name,
        )
    }
}
