package dev.dbt.intellij.artifacts

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import dev.dbt.intellij.psi.ProjectMacros
import dev.dbt.intellij.resources.ResourceCall
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** JSON parsing is off the UI thread; saved artifacts are optional enrichment only. */
@Service(Service.Level.PROJECT)
class DbtArtifacts(private val project: Project) : Disposable {
    private data class Stamp(
        val modified: java.nio.file.attribute.FileTime,
        val bytes: Long,
        val identity: String?,
    )

    private data class Cached(val stamps: List<Stamp?>, val snapshot: ArtifactSnapshot)

    private val cache = ConcurrentHashMap<ArtifactRequest, Cached>()
    private val loading = ConcurrentHashMap<ArtifactRequest, ProgressIndicatorBase>()

    fun request(element: PsiElement): ArtifactRequest? {
        val root = ProjectMacros.hostRoot(element) ?: return null
        val base = Path.of(root.path).normalize()
        val config =
            root
                .findChild("dbt_project.yml")
                ?.let { com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text }
                .orEmpty()
        val configured =
            dev.dbt.intellij.project.DbtProjectConfiguration.contains(config, "target-path")
        val raw =
            dev.dbt.intellij.project.DbtProjectConfiguration.scalar(config, "target-path")
                ?: if (configured) null else "target"
        val target =
            raw?.takeIf { !it.startsWith('*') && !it.startsWith('&') && "{{" !in it && "{%" !in it }
                ?.let { runCatching { base.resolve(it).normalize() }.getOrNull() }
                ?.takeIf { it.startsWith(base) && it != base }
        return ArtifactRequest(
            base,
            ProjectMacros.scalar(project, root, "name"),
            target,
            if (target == null)
                "Artifact directory requires a literal target-path inside the dbt project"
            else null,
        )
    }

    private fun stamps(request: ArtifactRequest): List<Stamp?> =
        listOf("manifest.json", "catalog.json").map {
            val file = request.target?.resolve(it)
            if (file == null || !Files.isRegularFile(file)) null
            else
                Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
                    .let { attributes ->
                        Stamp(
                            attributes.lastModifiedTime(),
                            attributes.size(),
                            attributes.fileKey()?.toString(),
                        )
                    }
        }

    fun load(request: ArtifactRequest, force: Boolean = false): ArtifactSnapshot {
        check(
            !ApplicationManager.getApplication().isDispatchThread ||
                ApplicationManager.getApplication().isUnitTestMode,
        ) {
            "Artifact parsing must run in background"
        }
        if (cache.size >= 64) cache.clear()
        val before = runCatching { stamps(request) }.getOrDefault(emptyList())
        if (!force)
            cache[request]
                ?.takeIf { it.stamps == before }
                ?.let {
                    return it.snapshot
                }
        val result = ArtifactReader.load(request)
        // An external dbt process may replace the files while they are being read. Never cache
        // a mixed generation as current; the next request retries after the writer settles.
        if (runCatching { stamps(request) }.getOrNull() != before)
            return ArtifactSnapshot(
                messages = listOf("Artifacts changed while being read; refresh after dbt finishes"),
            )
        cache[request] = Cached(before, result)
        return result
    }

    fun snapshot(element: PsiElement): ArtifactSnapshot? {
        val request = request(element) ?: return null
        val app = ApplicationManager.getApplication()
        if ((!app.isDispatchThread && !app.isReadAccessAllowed) || app.isUnitTestMode)
            return load(request)
        val indicator = ProgressIndicatorBase()
        if (loading.putIfAbsent(request, indicator) == null)
            app.executeOnPooledThread {
                try {
                    ProgressManager.getInstance()
                        .runProcess(
                            Runnable {
                                indicator.checkCanceled()
                                if (!project.isDisposed) load(request)
                            },
                            indicator,
                        )
                } catch (_: ProcessCanceledException) {
                    // Project disposal cancelled an optional documentation read.
                } finally {
                    loading.remove(request, indicator)
                }
            }
        return cache[request]?.snapshot
    }

    override fun dispose() {
        loading.values.forEach { it.cancel() }
        loading.clear()
        cache.clear()
    }

    fun file(element: PsiElement, node: ArtifactNode): VirtualFile? {
        if (!ArtifactReader.safeRelative(node.originalPath)) return null
        val matches =
            ProjectMacros.roots(element)
                .filter { ProjectMacros.scalar(project, it, "name") == node.packageName }
                .mapNotNull { root ->
                    root.findFileByRelativePath(node.originalPath)?.takeIf {
                        !it.isDirectory &&
                            project
                                .getService(dev.dbt.intellij.project.DbtProjectRoots::class.java)
                                .rootFor(it) == root
                    }
                }
        return matches.distinct().singleOrNull()
    }

    fun forFile(element: PsiElement, snapshot: ArtifactSnapshot): List<ArtifactNode> {
        val file = element.containingFile.originalFile.virtualFile ?: return emptyList()
        val root = ProjectMacros.root(element) ?: return emptyList()
        val relative =
            com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(file, root) ?: return emptyList()
        val namespace = ProjectMacros.scalar(project, root, "name") ?: return emptyList()
        return snapshot.nodes.values.filter {
            it.packageName == namespace &&
                it.originalPath == relative &&
                this.file(element, it) == file
        }
    }

    fun resources(
        element: PsiElement,
        call: ResourceCall,
        snapshot: ArtifactSnapshot,
    ): List<ArtifactNode> {
        val kinds =
            when (call.function) {
                "ref" -> setOf("model", "seed", "snapshot")
                "source" -> setOf("source")
                "metric" -> setOf("metric")
                else -> emptySet()
            }
        val allowed =
            ProjectMacros.roots(element)
                .mapNotNull { ProjectMacros.scalar(project, it, "name") }
                .toSet()
        var matches =
            snapshot.nodes.values.filter {
                it.kind in kinds &&
                    it.name == call.name &&
                    it.packageName in allowed &&
                    (call.packageName == null || call.packageName == it.packageName) &&
                    (call.source == null || call.source == it.sourceName) &&
                    (call.version?.let { v -> it.version == v }
                        ?: (it.version == null || it.version == it.latestVersion))
            }
        if (call.packageName == null) {
            val own = ProjectMacros.namespace(element)
            matches
                .filter { it.packageName == own }
                .takeIf { it.isNotEmpty() }
                ?.let { matches = it }
        }
        return matches
    }

    /** Source text is authoritative; a saved manifest is a snapshot, not an editor type system. */
    fun changed(element: PsiElement, node: ArtifactNode): Boolean {
        val file = file(element, node) ?: return false
        val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file) ?: return false
        val raw = node.rawCode ?: return false
        val current =
            if (node.kind == "macro")
                dev.dbt.intellij.psi.LocalMacros.candidates(psi, node.name).singleOrNull()?.text
                    ?: return true
            else psi.text
        return current.replace("\r\n", "\n") != raw.replace("\r\n", "\n")
    }
}
