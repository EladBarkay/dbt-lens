package dev.dbt.intellij

import com.intellij.lang.Language
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import dev.dbt.intellij.config.DbtSchemaProviderFactory
import dev.dbt.intellij.config.DbtYamlFiles
import dev.dbt.intellij.config.DbtYamlIconProvider
import dev.dbt.intellij.psi.DbtVariable
import dev.dbt.intellij.resources.DbtResourceNavigationHandler
import org.jetbrains.yaml.psi.YAMLScalar

class DbtYamlIntegrationTest : BasePlatformTestCase() {
    private fun open(path: String, text: String): com.intellij.psi.PsiFile {
        val file = myFixture.addFileToProject(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.file
    }

    fun testSchemasUseNativeYamlAndHaveDbtIconsOnlyInDbtProjects() {
        val examples =
            mapOf(
                "dbt_project.yml" to "name: warehouse\n",
                "profiles.yml" to "warehouse:\n  outputs:\n    dev:\n      type: snowflake\n",
                "packages.yml" to "packages: []\n",
                "dependencies.yml" to "packages: []\n",
                "selectors.yml" to "selectors: []\n",
                "models/schema.yaml" to "version: 2\nmodels: []\n",
            )
        val providers = DbtSchemaProviderFactory().getProviders(project)
        for ((path, text) in examples) {
            val file = open("warehouse/$path", text)
            assertEquals(Language.findLanguageByID("yaml"), file.language)
            val provider = providers.single { it.isAvailable(file.virtualFile) }
            assertNotNull(provider.schemaFile)
            assertNotNull(
                JsonSchemaService.Impl.get(project)
                    .getSchemaObjectForSchemaFile(provider.schemaFile!!),
            )
            assertTrue(
                JsonSchemaService.Impl.get(project)
                    .getSchemaFilesForFile(file.virtualFile)
                    .contains(provider.schemaFile),
            )
            assertSame(DbtYamlIconProvider.ICON, DbtYamlIconProvider().getIcon(file, 0))
        }
        for (path in
            listOf(
                "other/profiles.yml",
                "other/models/schema.yml",
                "warehouse/.github/workflows/check.yml",
                "warehouse/target/schema.yml",
            )) {
            val file = open(path, "version: 2\n")
            assertNull(DbtYamlFiles.schema(project, file.virtualFile))
            assertFalse(providers.any { it.isAvailable(file.virtualFile) })
            assertNull(DbtYamlIconProvider().getIcon(file, 0))
        }
    }

    fun testSchemaCompletionAndValidationAreProvidedByJetBrains() {
        @Suppress("UNCHECKED_CAST")
        val inspection =
            Class.forName("org.jetbrains.yaml.schema.YamlJsonSchemaHighlightingInspection")
                as Class<out com.intellij.codeInspection.LocalInspectionTool>
        myFixture.enableInspections(inspection)
        val file = open("warehouse/dbt_project.yml", "name: warehouse\nmodel-p\n")
        myFixture.editor.caretModel.moveToOffset(file.text.indexOf("model-p") + 7)
        val variants = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(
            "Native YAML completion: $variants; ${myFixture.editor.document.text}; schemas=${JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file.virtualFile)}",
            "model-paths" in variants || myFixture.editor.document.text.contains("model-paths:"),
        )
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText("name: warehouse\nmodel-paths: 42\n")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val diagnostics = myFixture.doHighlighting()
        assertTrue(
            "Bundled schema validation: ${diagnostics.map { it.description }}",
            diagnostics.any { it.description?.contains("array", ignoreCase = true) == true },
        )
        open(
            "warehouse/profiles.yml",
            "warehouse:\n  target: dev\n  outputs:\n    dev:\n      type: snowflake\n      threads: \"{{ env_var('DBT_THREADS') }}\"\n      account: example\n      private_key_path: /path/to/key\n",
        )
        assertFalse(
            "Adapter-specific settings and Jinja scalars remain allowed",
            myFixture.doHighlighting().any {
                it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR
            },
        )
    }

    fun testCustomResourcePathsAndMarkerDeletion() {
        val config =
            open("warehouse/dbt_project.yml", "name: warehouse\nmodel-paths: [transformations]\n")
        val custom = open("warehouse/transformations/resources.yml", "version: 2\n")
        val oldDefault =
            myFixture.addFileToProject("warehouse/models/resources.yml", "version: 2\n")
        assertEquals("dbt_yml_files", DbtYamlFiles.schema(project, custom.virtualFile))
        assertNull(DbtYamlFiles.schema(project, oldDefault.virtualFile))
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
            config.virtualFile.delete(this)
        }
        assertNull(DbtYamlFiles.schema(project, custom.virtualFile))
    }

    fun testSourceTableAndVersionedModelNavigationUsesLogicalNamesAndExactDefinitions() {
        myFixture.addFileToProject("warehouse/dbt_project.yml", "name: warehouse\n")
        val latest = myFixture.addFileToProject("warehouse/models/orders_v2.sql", "select 2")
        val first = myFixture.addFileToProject("warehouse/models/legacy_orders.sql", "select 1")
        val yaml =
            myFixture.addFileToProject(
                "warehouse/models/resources.yml",
                """
                version: 2
                sources:
                  - name: raw
                    schema: physical_schema
                    tables:
                      - name: orders
                        identifier: physical_orders
                        columns:
                          - name: misleading
                models:
                  - name: orders
                    latest_version: 2
                    versions:
                      - v: 1
                        defined_in: legacy_orders
                      - v: 2
                  - name: orders_v2
                    description: Properties for the implementation file.
                """
                    .trimIndent(),
            )
        open(
            "warehouse/models/usage.sql",
            "{{ source('raw', 'orders') }} {{ ref('orders') }} {{ ref('orders', v=1) }} {{ source('raw','misleading') }}",
        )
        val calls = PsiTreeUtil.findChildrenOfType(myFixture.file, DbtVariable::class.java)
        val targets = calls.map {
            DbtResourceNavigationHandler()
                .getGotoDeclarationTargets(it.firstChild, it.textOffset, myFixture.editor)
                .orEmpty()
        }
        assertEquals(yaml.virtualFile, targets[0].single().containingFile.virtualFile)
        assertEquals(yaml.text.indexOf("orders"), targets[0].single().textOffset)
        assertEquals(latest.virtualFile, targets[1].single().containingFile.virtualFile)
        assertEquals(first.virtualFile, targets[2].single().containingFile.virtualFile)
        assertEmpty(targets[3].toList())
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project)
                .getDocument(yaml)!!
                .setText(yaml.text.replace("name: orders", "name: renamed"))
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertNull(
            DbtResourceNavigationHandler()
                .getGotoDeclarationTargets(
                    calls.first().firstChild,
                    calls.first().textOffset,
                    myFixture.editor,
                ),
        )
    }

    fun testExposureDependenciesAndWrappedYamlCallsKeepNativeYamlAndMapQuotedOffsets() {
        myFixture.addFileToProject("warehouse/dbt_project.yml", "name: warehouse\n")
        val model = myFixture.addFileToProject("warehouse/models/orders.sql", "select 1")
        val source =
            myFixture.addFileToProject(
                "warehouse/models/sources.yml",
                "sources: [{name: raw, tables: [{name: orders}]}]\nmetrics: [{name: revenue}]\n",
            )
        val file =
            open(
                "warehouse/models/exposures.yml",
                """
                exposures:
                  - name: dashboard
                    depends_on:
                      - ref('orders')
                      - 'source(''raw'', ''orders'')'
                      - "{{ metric('revenue') }}"
                    description: "Ordinary prose ref('orders')"
                  - name: other
                    description: "{{ ref('orders') }}"
                  - name: literal
                    description: >-
                      {{ "ref('orders')" }}
                      {# ref('orders') #}
                """
                    .trimIndent(),
            )
        assertEquals(Language.findLanguageByID("yaml"), file.language)
        val scalars = PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java)
        val refs =
            scalars
                .flatMap { it.references.toList() }
                .filterIsInstance<dev.dbt.intellij.resources.DbtResourceReference>()
        assertEquals(4, refs.size)
        assertEquals(
            listOf(model.virtualFile, source.virtualFile, source.virtualFile, model.virtualFile),
            refs.map { it.resolve()!!.containingFile.virtualFile },
        )
        refs.forEach {
            assertTrue(
                it.element.text
                    .substring(it.rangeInElement.startOffset, it.rangeInElement.endOffset)
                    .contains('('),
            )
        }
        val quoted = refs[1]
        assertEquals(
            "source(''raw'', ''orders'')",
            quoted.element.text.substring(
                quoted.rangeInElement.startOffset,
                quoted.rangeInElement.endOffset,
            ),
        )
    }
}
