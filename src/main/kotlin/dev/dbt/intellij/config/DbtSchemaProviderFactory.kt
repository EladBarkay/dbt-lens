package dev.dbt.intellij.config

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/** Registered only when JetBrains' JSON support is installed; YAML keeps its native language. */
class DbtSchemaProviderFactory : JsonSchemaProviderFactory, DumbAware {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> =
        listOf("dbt_project", "profiles", "packages", "dependencies", "selectors", "dbt_yml_files")
            .map { schema ->
                object : JsonSchemaFileProvider {
                    override fun isAvailable(file: VirtualFile) =
                        DbtYamlFiles.schema(project, file) == schema

                    override fun getName() = "dbt Lens: $schema"

                    override fun getSchemaFile(): VirtualFile? =
                        JsonSchemaProviderFactory.getResourceFile(
                            DbtSchemaProviderFactory::class.java,
                            "/schemas/$schema.json",
                        )

                    override fun getSchemaType() = SchemaType.embeddedSchema
                }
            }
}
