package dev.dbt.intellij.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

/** Literal resource path lists only; configuration Jinja/aliases are never evaluated. */
object DbtProjectConfiguration {
    val DEFAULT_PATHS = mapOf("model-paths" to "models", "seed-paths" to "seeds", "snapshot-paths" to "snapshots",
        "test-paths" to "tests", "analysis-paths" to "analyses", "macro-paths" to "macros")
    fun paths(project: Project, root: VirtualFile, key: String): List<String> {
        val config = root.findChild("dbt_project.yml")?.let { PsiManager.getInstance(project).findFile(it)?.text }.orEmpty()
        val lines = config.lines()
        val index = lines.indexOfFirst { it.startsWith("$key:") }
        if (index < 0) return listOfNotNull(DEFAULT_PATHS[key])
        val value = lines[index].substringAfter(':').trim().substringBefore(" #").trim()
        val raw = if (value.startsWith('[') && value.endsWith(']')) value.drop(1).dropLast(1).split(',')
            else if (value.isEmpty()) lines.drop(index + 1).takeWhile { it.isBlank() || it.startsWith(' ') || it.startsWith('\t') }
                .filter { it.trimStart().startsWith("- ") }.map { it.trimStart().removePrefix("- ").substringBefore(" #") }
            else return emptyList()
        return raw.map { it.trim().trim('\'', '"') }.filter { it.isNotEmpty() && !it.contains("{{") && !it.startsWith('*') && !it.startsWith('&') }
    }
}
