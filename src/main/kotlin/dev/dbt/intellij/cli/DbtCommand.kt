package dev.dbt.intellij.cli

import dev.dbt.intellij.artifacts.ArtifactRequest
import dev.dbt.intellij.config.DbtSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

enum class DbtCommandKind(val title: String) {
    PARSE("Generate manifest"),
    COMPILE("Compile current"),
    PROJECT("Compile project"),
    INLINE("Compile macro call"),
}

data class DbtCommand(
    val kind: DbtCommandKind,
    val context: ArtifactRequest,
    val selector: String? = null,
    val invocation: String? = null,
    val sourcePath: String? = null,
    val resourceId: String? = null,
)

data class DbtCommandPlan(
    val executable: Path,
    val directory: Path,
    val arguments: List<String>,
    val environment: Map<String, String>,
)

/**
 * Builds argument vectors, never shell commands. Resolution and environment-file reads run on a
 * worker.
 */
object DbtCommandPlanner {
    private fun path(root: Path, text: String): Path {
        val expanded =
            if (text == "~") System.getProperty("user.home")
            else if (text.startsWith("~/")) System.getProperty("user.home") + text.drop(1) else text
        return root.resolve(expanded).normalize().toAbsolutePath()
    }

    fun environment(file: Path): Map<String, String> {
        require(Files.isRegularFile(file) && Files.size(file) <= 65_536) {
            "Environment file must be a readable file smaller than 64 KB"
        }
        val result = linkedMapOf<String, String>()
        dev.dbt.intellij.io.BoundedFiles.text(file, 65_536).lineSequence().forEachIndexed {
            index,
            raw ->
            val line = raw.trim().removePrefix("export ").trim()
            if (line.isBlank() || line.startsWith('#')) return@forEachIndexed
            val match =
                Regex("([A-Za-z_][A-Za-z_0-9]*)\\s*=\\s*(.*)").matchEntire(line)
                    ?: throw IllegalArgumentException(
                        "Invalid environment entry on line ${index + 1}; use literal NAME=value"
                    )
            var value = match.groupValues[2]
            if (value.startsWith('"') || value.startsWith('\'')) {
                val quote = value.first()
                val quoted =
                    Regex(
                            "^${Regex.escape(quote.toString())}(.*)${Regex.escape(quote.toString())}(?:\\s+#.*)?$"
                        )
                        .matchEntire(value)
                require(quoted != null) { "Unclosed environment value on line ${index + 1}" }
                value = quoted.groupValues[1]
            } else value = value.replace(Regex("\\s+#.*$"), "").trimEnd()
            require('\u0000' !in value) { "Invalid environment value on line ${index + 1}" }
            result[match.groupValues[1]] = value
        }
        return result
    }

    fun plan(
        command: DbtCommand,
        settings: DbtSettings.State,
        inherited: Map<String, String>,
    ): DbtCommandPlan {
        val root = command.context.root.toAbsolutePath().normalize()
        require(Files.isRegularFile(root.resolve("dbt_project.yml"))) {
            "The selected dbt project is unavailable"
        }
        require(command.context.problem == null && command.context.target != null) {
            command.context.problem ?: "Artifact directory is unavailable"
        }
        val target = command.context.target.toAbsolutePath().normalize()
        require(target.startsWith(root) && target != root) {
            "Output must remain inside the dbt project"
        }
        var existing = target
        while (!Files.exists(existing)) existing =
            existing.parent ?: throw IllegalArgumentException("Output directory is unavailable")
        require(existing.toRealPath().startsWith(root.toRealPath())) {
            "Output directory points outside the dbt project"
        }
        val env = inherited.toMutableMap()
        if (settings.environmentFile.isNotBlank())
            env.putAll(environment(path(root, settings.environmentFile)))
        val windows = System.getProperty("os.name").startsWith("Windows", true)
        val names = if (windows) listOf("dbt.exe", "dbt") else listOf("dbt")
        fun usable(p: Path) = Files.isRegularFile(p) && (windows || Files.isExecutable(p))
        val configured = settings.executable.trim()
        val executable =
            if (
                configured.isNotEmpty() &&
                    (configured.contains('/') ||
                        configured.contains('\\') ||
                        configured.startsWith('~'))
            ) {
                path(root, configured).takeIf(::usable)
            } else {
                val local =
                    if (configured.isEmpty())
                        listOf(".venv", "venv").flatMap { dir ->
                            names.map {
                                root.resolve("$dir/${if (windows) "Scripts" else "bin"}/$it")
                            }
                        }
                    else emptyList()
                local.firstOrNull(::usable)
                    ?: env.entries
                        .firstOrNull { it.key.equals("PATH", windows) }
                        ?.value
                        .orEmpty()
                        .split(File.pathSeparator)
                        .filter(String::isNotBlank)
                        .flatMap { folder ->
                            (if (configured.isEmpty()) names else listOf(configured)).map {
                                path(root, folder).resolve(it)
                            }
                        }
                        .firstOrNull(::usable)
            }
                ?: throw IllegalArgumentException(
                    "dbt executable not found. Configure dbt Lens settings or install dbt in the project's virtualenv."
                )
        val bin = executable.parent
        val pathKey = env.keys.firstOrNull { it.equals("PATH", windows) } ?: "PATH"
        env[pathKey] = bin.toString() + File.pathSeparator + env[pathKey].orEmpty()
        if (bin.parent?.resolve("pyvenv.cfg")?.let(Files::isRegularFile) == true)
            env["VIRTUAL_ENV"] = bin.parent.toString()
        env["PYTHONUNBUFFERED"] = "1"
        val args =
            mutableListOf(
                "--write-json",
                "--log-format",
                "json",
                "--no-use-colors",
                if (command.kind == DbtCommandKind.PARSE) "parse" else "compile",
                "--project-dir",
                root.toString(),
                "--target-path",
                target.toString(),
            )
        if (settings.profilesDirectory.isNotBlank()) {
            val directory = path(root, settings.profilesDirectory)
            require(Files.isDirectory(directory)) { "Profiles directory does not exist" }
            args += listOf("--profiles-dir", directory.toString())
        }
        if (settings.profile.isNotBlank()) args += listOf("--profile", settings.profile.trim())
        if (settings.target.isNotBlank()) args += listOf("--target", settings.target.trim())
        when (command.kind) {
            DbtCommandKind.COMPILE -> {
                require(!command.selector.isNullOrBlank()) { "No SQL resource selected" }
                args += listOf("--select", command.selector, "--indirect-selection", "empty")
            }
            DbtCommandKind.INLINE -> {
                require(
                    !command.invocation.isNullOrBlank() &&
                        command.invocation.length <= 65_536 &&
                        '\u0000' !in command.invocation
                ) {
                    "Enter a macro call smaller than 64 KB"
                }
                args += listOf("--inline", command.invocation)
            }
            else -> Unit
        }
        return DbtCommandPlan(executable, root, args, env)
    }

    fun selector(packageName: String, originalPath: String): String {
        require(
            packageName.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) &&
                dev.dbt.intellij.artifacts.ArtifactReader.safeRelative(originalPath) &&
                originalPath.none { it.isWhitespace() || it.isISOControl() || it in ",+*?:[]{}\\" }
        ) {
            "This resource path cannot be selected unambiguously; use Compile project"
        }
        return "package:$packageName,path:$originalPath"
    }

    fun selector(node: dev.dbt.intellij.artifacts.ArtifactNode): String {
        require(node.kind in setOf("model", "analysis", "snapshot", "test", "operation")) {
            "This resource cannot be compiled individually; use Compile project"
        }
        selector(node.packageName, node.originalPath)
        val fqn = node.fqn
        if (
            fqn.size >= 2 &&
                fqn.first() == node.packageName &&
                fqn.all { part ->
                    part.isNotBlank() &&
                        part.none { it.isWhitespace() || it.isISOControl() || it in ",.+*?:[]{}\\" }
                }
        )
            return "package:${node.packageName},resource_type:${node.kind},fqn:${fqn.joinToString(".")}"
        // A basename can match several resources in different directories. Never broaden
        // Compile current when saved metadata cannot identify a single resource.
        throw IllegalArgumentException(
            "Saved metadata cannot identify this resource; generate a manifest or use Compile project"
        )
    }
}
