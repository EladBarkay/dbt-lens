package dev.dbt.intellij.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

/** A conservative literal subset available even when native YAML support is disabled. */
object DbtProjectConfiguration {
    val DEFAULT_PATHS =
        mapOf(
            "model-paths" to "models",
            "seed-paths" to "seeds",
            "snapshot-paths" to "snapshots",
            "test-paths" to "tests",
            "analysis-paths" to "analyses",
            "macro-paths" to "macros",
        )
    val GENERATED_DIRECTORIES = setOf("target", "logs", ".git", ".venv", "venv", "node_modules")

    private data class Entry(val value: String, val following: List<String>)

    private fun keyPattern(key: String) =
        Regex(
            """^(?:${Regex.escape(key)}|'${Regex.escape(key)}'|"${Regex.escape(key)}")[ \t]*:[ \t]*(.*)$"""
        )

    fun contains(text: String, key: String): Boolean {
        val pattern = keyPattern(key)
        return text.lineSequence().any { pattern.matches(it) }
    }

    private fun entry(text: String, key: String): Entry? {
        val lines = text.lines()
        val pattern = keyPattern(key)
        val indexes = lines.indices.filter { pattern.matches(lines[it]) }
        val index = indexes.singleOrNull() ?: return null // Duplicate keys are ambiguous.
        return Entry(
            uncomment(pattern.matchEntire(lines[index])!!.groupValues[1]).trim(),
            lines.drop(index + 1).takeWhile {
                it.isBlank() || it.startsWith(' ') || it.startsWith('\t') || it.startsWith('#')
            },
        )
    }

    private fun uncomment(value: String): String {
        var quote: Char? = null
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (quote == null) {
                if (char in "'\"") quote = char
                else if (char == '#' && (index == 0 || value[index - 1].isWhitespace()))
                    return value.take(index)
            } else if (char == quote) {
                if (quote == '\'' && value.getOrNull(index + 1) == '\'') index++ else quote = null
            } else if (char == '\\' && quote == '"') index++
            index++
        }
        return value
    }

    private fun literal(raw: String): String? {
        val value = raw.trim()
        if (
            value.isEmpty() ||
                value.contains("{{") ||
                value.contains("{%") ||
                value.contains("{#") ||
                '\u0000' in value
        )
            return null
        if (value.first() in "'\"") {
            val quote = value.first()
            if (value.length < 2 || value.last() != quote) return null
            val body = value.drop(1).dropLast(1)
            return if (quote == '\'')
                body.replace("''", "'").takeIf { !body.replace("''", "").contains('\'') }
            else body.takeUnless { '\\' in it || '"' in it }
        }
        return value.takeUnless {
            it in setOf("null", "Null", "NULL", "~") ||
                it.first() in "*&!|>[{" ||
                it.any { char -> char in "[]{}'\"" }
        }
    }

    fun scalar(text: String, key: String): String? =
        entry(text, key)?.value?.let(::literal)?.takeIf(String::isNotBlank)

    fun paths(project: Project, root: VirtualFile, key: String): List<String> {
        val text =
            root
                .findChild("dbt_project.yml")
                ?.let { PsiManager.getInstance(project).findFile(it)?.text }
                .orEmpty()
        if (!contains(text, key)) return listOfNotNull(DEFAULT_PATHS[key])
        val entry = entry(text, key) ?: return emptyList()
        val values =
            when {
                entry.value.startsWith('[') && entry.value.endsWith(']') -> {
                    val body = entry.value.drop(1).dropLast(1)
                    if (body.isBlank()) return emptyList()
                    splitPaths(body)
                }
                entry.value.isEmpty() ->
                    entry.following
                        .map { uncomment(it).trim() }
                        .filter(String::isNotBlank)
                        .map {
                            if (!it.startsWith("- ")) return emptyList()
                            it.removePrefix("- ")
                        }
                else -> return emptyList()
            }
        return values.map { literal(it)?.takeIf(String::isNotBlank) ?: return emptyList() }
    }

    private fun splitPaths(body: String): List<String> {
        val parts = mutableListOf<String>()
        var quote: Char? = null
        var start = 0
        var index = 0
        while (index < body.length) {
            val char = body[index]
            if (quote == null) {
                if (char in "'\"") quote = char
                else if (char == ',') {
                    parts += body.substring(start, index)
                    start = index + 1
                }
            } else if (char == quote) {
                if (quote == '\'' && body.getOrNull(index + 1) == '\'') index++ else quote = null
            } else if (char == '\\' && quote == '"') index++
            index++
        }
        if (quote != null) return emptyList()
        body.substring(start).takeIf(String::isNotBlank)?.let(parts::add)
        return parts
    }
}
