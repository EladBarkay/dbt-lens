package dev.dbt.intellij.config

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import dev.dbt.intellij.project.DbtProjectRoots

object DbtYamlFiles {
    fun schema(project: Project, file: VirtualFile): String? {
        if (file.isDirectory || file.extension?.lowercase() !in setOf("yml", "yaml")) return null
        val roots = project.getService(DbtProjectRoots::class.java)
        val root = roots.rootFor(file)
        // The standard user profiles file is allowed only while a dbt project is open.
        if (root == null) {
            if (file.path == System.getProperty("user.home") + "/.dbt/profiles.yml" &&
                ProjectRootManager.getInstance(project).contentRoots.any { it.findChild("dbt_project.yml") != null }) return "profiles"
            return null
        }
        val type = file.nameWithoutExtension
        if (file.parent == root && type in setOf("dbt_project", "profiles", "packages", "dependencies", "selectors")) return type
        // Resource/property YAML belongs only in configured resource folders. Leave unrelated YAML alone.
        val defaults = mapOf("model-paths" to "models", "seed-paths" to "seeds", "snapshot-paths" to "snapshots",
            "test-paths" to "tests", "analysis-paths" to "analyses", "macro-paths" to "macros")
        val config = root.findChild("dbt_project.yml")?.let { com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text }.orEmpty()
        val inResourcePath = defaults.any { (key, default) ->
            val value = Regex("(?m)^${Regex.escape(key)}:[ \\t]*\\[([^\\r\\n]*)]\\s*(?:#.*)?$").find(config)?.groupValues?.get(1)
            val paths = if (value == null) listOf(default) else value.split(',').map { it.trim().trim('\'', '"') }.filter { it.isNotEmpty() }
            paths.any { path -> root.findFileByRelativePath(path)?.let { VfsUtilCore.isAncestor(it, file, true) } == true }
        }
        return if (inResourcePath) "dbt_yml_files" else null
    }
}
