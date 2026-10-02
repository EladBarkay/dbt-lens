package dev.dbt.intellij

import com.intellij.lang.Language
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import dev.dbt.intellij.config.DbtSchemaProviderFactory
import dev.dbt.intellij.config.DbtYamlFiles
import dev.dbt.intellij.config.DbtYamlIconProvider

class DbtYamlIntegrationTest : BasePlatformTestCase() {
    private fun open(path: String, text: String): com.intellij.psi.PsiFile {
        val file = myFixture.addFileToProject(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.file
    }

    fun testSchemasUseNativeYamlAndHaveDbtIconsOnlyInDbtProjects() {
        val examples = mapOf("dbt_project.yml" to "name: warehouse\n", "profiles.yml" to "warehouse:\n  outputs:\n    dev:\n      type: snowflake\n",
            "packages.yml" to "packages: []\n", "dependencies.yml" to "packages: []\n", "selectors.yml" to "selectors: []\n",
            "models/schema.yaml" to "version: 2\nmodels: []\n")
        val providers = DbtSchemaProviderFactory().getProviders(project)
        for ((path, text) in examples) {
            val file = open("warehouse/$path", text)
            assertEquals(Language.findLanguageByID("yaml"), file.language)
            val provider = providers.single { it.isAvailable(file.virtualFile) }
            assertNotNull(provider.schemaFile)
            assertNotNull(JsonSchemaService.Impl.get(project).getSchemaObjectForSchemaFile(provider.schemaFile!!))
            assertTrue(JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file.virtualFile).contains(provider.schemaFile))
            assertSame(DbtYamlIconProvider.ICON, DbtYamlIconProvider().getIcon(file, 0))
        }
        for (path in listOf("other/profiles.yml", "other/models/schema.yml", "warehouse/.github/workflows/check.yml", "warehouse/target/schema.yml")) {
            val file = open(path, "version: 2\n")
            assertNull(DbtYamlFiles.schema(project, file.virtualFile))
            assertFalse(providers.any { it.isAvailable(file.virtualFile) })
            assertNull(DbtYamlIconProvider().getIcon(file, 0))
        }
    }

    fun testSchemaCompletionAndValidationAreProvidedByJetBrains() {
        @Suppress("UNCHECKED_CAST")
        val inspection = Class.forName("org.jetbrains.yaml.schema.YamlJsonSchemaHighlightingInspection") as Class<out com.intellij.codeInspection.LocalInspectionTool>
        myFixture.enableInspections(inspection)
        val file = open("warehouse/dbt_project.yml", "name: warehouse\nmodel-p\n")
        myFixture.editor.caretModel.moveToOffset(file.text.indexOf("model-p") + 7)
        val variants = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue("Native YAML completion: $variants; ${myFixture.editor.document.text}; schemas=${JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file.virtualFile)}", "model-paths" in variants || myFixture.editor.document.text.contains("model-paths:"))
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("name: warehouse\nmodel-paths: 42\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val diagnostics = myFixture.doHighlighting()
        assertTrue("Bundled schema validation: ${diagnostics.map { it.description }}", diagnostics.any {
            it.description?.contains("array", ignoreCase = true) == true
        })
        open("warehouse/profiles.yml", "warehouse:\n  target: dev\n  outputs:\n    dev:\n      type: snowflake\n      threads: \"{{ env_var('DBT_THREADS') }}\"\n      account: example\n      private_key_path: /path/to/key\n")
        assertFalse("Adapter-specific settings and Jinja scalars remain allowed", myFixture.doHighlighting().any {
            it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR
        })
    }

    fun testCustomResourcePathsAndMarkerDeletion() {
        val config = open("warehouse/dbt_project.yml", "name: warehouse\nmodel-paths: [transformations]\n")
        val custom = open("warehouse/transformations/resources.yml", "version: 2\n")
        val oldDefault = myFixture.addFileToProject("warehouse/models/resources.yml", "version: 2\n")
        assertEquals("dbt_yml_files", DbtYamlFiles.schema(project, custom.virtualFile))
        assertNull(DbtYamlFiles.schema(project, oldDefault.virtualFile))
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction { config.virtualFile.delete(this) }
        assertNull(DbtYamlFiles.schema(project, custom.virtualFile))
    }
}
