package dev.dbt.intellij.resources

import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.dbt.intellij.project.DbtProjectConfiguration
import dev.dbt.intellij.psi.ProjectMacros

enum class ResourceKind {
    MODEL,
    SEED,
    SNAPSHOT,
    SOURCE,
    METRIC,
    EXPOSURE,
}

data class DbtResource(
    val kind: ResourceKind,
    val name: String,
    val file: VirtualFile,
    val offset: Int = 0,
    val source: String? = null,
    val version: String? = null,
    val latestVersion: String? = null,
    val definedIn: String? = null,
)

/**
 * YAML extraction is supplied by an optional plugin bridge. SQL/seed references work without YAML.
 */
interface DbtResourceMetadata {
    fun definitions(file: PsiFile): List<DbtResource>

    companion object {
        val EP = ExtensionPointName.create<DbtResourceMetadata>("dev.dbt.jinja.resourceMetadata")
    }
}

@Service(Service.Level.PROJECT)
class DbtResources(private val project: Project) {
    private val catalogs =
        java.util.concurrent.ConcurrentHashMap<VirtualFile, CachedValue<List<DbtResource>>>()

    fun targets(element: PsiElement, call: ResourceCall): List<PsiElement> {
        val roots =
            ProjectMacros.roots(element).filter {
                call.packageName == null ||
                    ProjectMacros.scalar(project, it, "name") == call.packageName
            }
        val own = ProjectMacros.root(element)
        fun inRoot(root: VirtualFile): List<DbtResource> {
            val all = catalog(root)
            val kinds =
                when (call.function) {
                    "ref" -> setOf(ResourceKind.MODEL, ResourceKind.SEED, ResourceKind.SNAPSHOT)
                    "source" -> setOf(ResourceKind.SOURCE)
                    "metric" -> setOf(ResourceKind.METRIC)
                    else -> emptySet()
                }
            val matching = all.filter {
                it.kind in kinds &&
                    it.name == call.name &&
                    (call.source == null || it.source == call.source)
            }
            if (call.function != "ref") return matching
            val versioned = matching.filter { it.version != null }
            if (versioned.isNotEmpty()) {
                return versioned
                    .filter { it.version == (call.version ?: it.latestVersion) }
                    .map { resource ->
                        val stem = resource.definedIn ?: "${resource.name}_v${resource.version}"
                        val files = all.filter {
                            it.kind == ResourceKind.MODEL &&
                                it.version == null &&
                                it.name == stem &&
                                it.file.extension?.lowercase() in setOf("sql", "py")
                        }
                        if (files.size == 1) files.single().copy(version = resource.version)
                        else resource
                    }
            }
            if (call.version != null) return emptyList()
            val definitions = matching.filter {
                it.file.extension?.lowercase() in setOf("sql", "py", "csv")
            }
            return definitions.ifEmpty { matching }
        }
        val local = if (call.packageName == null && own in roots) inRoot(own!!) else emptyList()
        val matches = if (local.isNotEmpty()) local else roots.flatMap(::inRoot)
        return matches
            .mapNotNull { resource ->
                PsiManager.getInstance(project).findFile(resource.file)?.let {
                    if (resource.offset == 0) it else it.findElementAt(resource.offset)
                }
            }
            .distinct()
    }

    private fun catalog(root: VirtualFile): List<DbtResource> {
        if (catalogs.size >= 128) catalogs.clear()
        return catalogs
            .computeIfAbsent(root) {
                CachedValuesManager.getManager(project)
                    .createCachedValue(
                        {
                            val result = mutableListOf<DbtResource>()
                            val seen = hashSetOf<Pair<String, VirtualFile>>()
                            val metadataFiles = hashSetOf<VirtualFile>()
                            for ((key, _) in DbtProjectConfiguration.DEFAULT_PATHS) {
                                for (path in DbtProjectConfiguration.paths(project, root, key)) {
                                    val folder = root.findFileByRelativePath(path) ?: continue
                                    if (!VfsUtilCore.isAncestor(root, folder, false)) continue
                                    VfsUtilCore.iterateChildrenRecursively(
                                        folder,
                                        { file ->
                                            !file.isDirectory ||
                                                (file.name !in
                                                    DbtProjectConfiguration.GENERATED_DIRECTORIES &&
                                                    (file == root ||
                                                        file.findChild("dbt_project.yml") == null))
                                        },
                                    ) { file ->
                                        ProgressManager.checkCanceled()
                                        if (!file.isDirectory && seen.add(key to file)) {
                                            val ext = file.extension?.lowercase()
                                            when {
                                                key == "model-paths" && ext in setOf("sql", "py") ->
                                                    result +=
                                                        DbtResource(
                                                            ResourceKind.MODEL,
                                                            file.nameWithoutExtension,
                                                            file,
                                                        )
                                                key == "seed-paths" && ext == "csv" ->
                                                    result +=
                                                        DbtResource(
                                                            ResourceKind.SEED,
                                                            file.nameWithoutExtension,
                                                            file,
                                                        )
                                                key == "snapshot-paths" &&
                                                    ext == "sql" &&
                                                    file.length <= 2_000_000 -> {
                                                    val text =
                                                        com.intellij.openapi.fileEditor
                                                            .FileDocumentManager
                                                            .getInstance()
                                                            .getCachedDocument(file)
                                                            ?.charsSequence
                                                            ?: VfsUtilCore.loadText(file)
                                                    dev.dbt.intellij.lang.DbtDeclarations.scan(
                                                            text,
                                                            "snapshot",
                                                        )
                                                        .forEach {
                                                            result +=
                                                                DbtResource(
                                                                    ResourceKind.SNAPSHOT,
                                                                    it.name,
                                                                    file,
                                                                    it.offset,
                                                                )
                                                        }
                                                }
                                                ext in setOf("yml", "yaml") &&
                                                    file.length <= 2_000_000 &&
                                                    metadataFiles.add(file) -> {
                                                    if (
                                                        DbtResourceMetadata.EP.extensionList
                                                            .isNotEmpty()
                                                    )
                                                        PsiManager.getInstance(project)
                                                            .findFile(file)
                                                            ?.let { psi ->
                                                                DbtResourceMetadata.EP.extensionList
                                                                    .forEach {
                                                                        result +=
                                                                            it.definitions(psi)
                                                                    }
                                                            }
                                                }
                                            }
                                        }
                                        true
                                    }
                                }
                            }
                            CachedValueProvider.Result.create(
                                result.distinct(),
                                PsiModificationTracker.MODIFICATION_COUNT,
                                VirtualFileManager.getInstance(),
                            )
                        },
                        false,
                    )
            }
            .value
    }
}
