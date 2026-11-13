package dev.dbt.intellij.psi

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FileBasedIndex
import dev.dbt.intellij.io.SourceLimits
import dev.dbt.intellij.project.DbtMacroIndex
import dev.dbt.intellij.project.DbtProjectRoots

object ProjectMacros {
    internal fun root(element: PsiElement): VirtualFile? =
        element.containingFile.originalFile.virtualFile?.let {
            element.project.getService(DbtProjectRoots::class.java).rootFor(it)
        }

    internal fun scalar(project: Project, root: VirtualFile, key: String): String? {
        val config = root.findChild("dbt_project.yml") ?: return null
        val psi = PsiManager.getInstance(project).findFile(config) ?: return null
        return dev.dbt.intellij.project.DbtProjectConfiguration.scalar(psi.text, key)
    }

    fun namespace(element: PsiElement): String? =
        root(element)?.let { packageName(element.project, it) }

    private fun packageName(project: Project, root: VirtualFile) =
        scalar(project, root, "name")?.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) }

    private fun installDirectory(project: Project, root: VirtualFile): VirtualFile? {
        val path = scalar(project, root, "packages-install-path") ?: "dbt_packages"
        return if (path.startsWith('/'))
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path)
        else root.findFileByRelativePath(path)
    }

    internal fun hostRoot(element: PsiElement): VirtualFile? {
        val own = root(element) ?: return null
        var host = own
        var ancestor = own.parent
        while (ancestor != null) {
            if (ancestor.findChild("dbt_project.yml") != null) {
                val install = installDirectory(element.project, ancestor)
                if (install != null && VfsUtilCore.isAncestor(install, own, true)) {
                    host = ancestor
                    break
                }
            }
            ancestor = ancestor.parent
        }
        return host
    }

    internal fun roots(element: PsiElement): List<VirtualFile> {
        val own = root(element) ?: return emptyList()
        val host = hostRoot(element) ?: return emptyList()
        val dependencies =
            installDirectory(element.project, host)?.children?.filter {
                it.isDirectory && it.findChild("dbt_project.yml")?.isDirectory == false
            } ?: emptyList()
        return (listOf(own, host) + dependencies).distinct()
    }

    fun hasNamespace(element: PsiElement, name: String): Boolean =
        roots(element).any { packageName(element.project, it) == name }

    fun candidates(element: PsiElement, name: String, namespace: String? = null): List<DbtMacro> {
        if (DumbService.isDumb(element.project)) return emptyList()
        val packages =
            roots(element).filter {
                namespace == null || packageName(element.project, it) == namespace
            }
        val current = root(element)
        val service = element.project.getService(DbtProjectRoots::class.java)
        val manager = PsiManager.getInstance(element.project)
        val catalog = element.project.getService(DependencyMacros::class.java)
        fun inPackage(root: VirtualFile): List<DbtMacro> {
            val indexed =
                FileBasedIndex.getInstance()
                    .getContainingFiles(
                        DbtMacroIndex.NAME,
                        name,
                        GlobalSearchScopesCore.directoryScope(element.project, root, true),
                    )
                    .filter { service.rootFor(it) == root }
            // Installed packages are often excluded from IDE indexes. Tokenize only those package
            // directories as a fallback, caching the catalog; do not scan arbitrary nested
            // projects.
            val files =
                if (
                    root == current &&
                        com.intellij.openapi.roots.ProjectFileIndex.getInstance(element.project)
                            .isInContent(root)
                )
                    indexed
                else (indexed + catalog.files(root, name)).distinct()
            return files.flatMap {
                manager.findFile(it)?.let { file -> LocalMacros.candidates(file, name) }
                    ?: emptyList()
            }
        }
        if (namespace == null && current in packages) {
            val own = inPackage(current!!)
            if (own.isNotEmpty()) return own
        }
        return packages.filter { namespace != null || it != current }.flatMap(::inPackage)
    }

    internal fun names(element: PsiElement): Set<String> {
        if (root(element) == null || DumbService.isDumb(element.project)) return emptySet()
        val catalog = element.project.getService(DependencyMacros::class.java)
        val keys =
            FileBasedIndex.getInstance()
                .getAllKeys(DbtMacroIndex.NAME, element.project)
                .toMutableSet()
        roots(element)
            .filter {
                it != root(element) ||
                    !com.intellij.openapi.roots.ProjectFileIndex.getInstance(element.project)
                        .isInContent(it)
            }
            .forEach { keys.addAll(catalog.names(it)) }
        return keys
    }

    fun completion(element: PsiElement, prefix: String, namespace: String? = null): List<DbtMacro> =
        names(element)
            .asSequence()
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .flatMap { candidates(element, it, namespace).asSequence() }
            .toList()
}

@Service(Service.Level.PROJECT)
class DependencyMacros(private val project: Project) {
    fun files(root: VirtualFile, name: String) = catalog(root)[name].orEmpty()

    fun names(root: VirtualFile) = catalog(root).keys

    private val catalogs =
        java.util.concurrent.ConcurrentHashMap<
            VirtualFile,
            com.intellij.psi.util.CachedValue<Map<String, List<VirtualFile>>>,
        >()

    private fun catalog(root: VirtualFile): Map<String, List<VirtualFile>> {
        if (catalogs.size >= SourceLimits.MAX_CACHED_CATALOGS) catalogs.clear()
        return catalogs
            .computeIfAbsent(root) {
                CachedValuesManager.getManager(project)
                    .createCachedValue(
                        {
                            val result = linkedMapOf<String, MutableList<VirtualFile>>()
                            VfsUtilCore.iterateChildrenRecursively(
                                root,
                                { file ->
                                    !file.isDirectory ||
                                        (file.name !in
                                            dev.dbt.intellij.project.DbtProjectConfiguration
                                                .GENERATED_DIRECTORIES &&
                                            (file == root ||
                                                file.findChild("dbt_project.yml") == null))
                                },
                            ) { file ->
                                ProgressManager.checkCanceled()
                                if (
                                    !file.isDirectory &&
                                        file.extension?.lowercase() in
                                            setOf("sql", "jinja", "j2") &&
                                        file.length <= SourceLimits.MAX_FILE_BYTES
                                ) {
                                    val text =
                                        com.intellij.openapi.fileEditor.FileDocumentManager
                                            .getInstance()
                                            .getCachedDocument(file)
                                            ?.charsSequence ?: VfsUtilCore.loadText(file)
                                    dev.dbt.intellij.lang.DbtDeclarations.scan(text, "macro")
                                        .forEach {
                                            result.getOrPut(it.name) { mutableListOf() }.add(file)
                                        }
                                }
                                true
                            }
                            CachedValueProvider.Result.create(
                                result.mapValues { it.value.distinct() },
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
