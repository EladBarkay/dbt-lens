package dev.dbt.intellij.project

import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.LanguageSubstitutor
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import dev.dbt.intellij.lang.DbtLanguage
import java.util.concurrent.ConcurrentHashMap

/** Project-scoped language selection. The global SQL file association remains owned by the IDE. */
class DbtSqlLanguageSubstitutor : LanguageSubstitutor() {
    override fun getLanguage(file: VirtualFile, project: Project): Language? {
        if (file.isDirectory || !file.extension.equals("sql", ignoreCase = true) || project.isDisposed) return null
        return if (project.getService(DbtProjectRoots::class.java).rootFor(file) != null) DbtLanguage else null
    }
}

/** Cheap directory decisions, bounded in memory and invalidated by VFS structure/project-root changes. */
@Service(Service.Level.PROJECT)
class DbtProjectRoots(private val project: Project) : Disposable {
    private val directories = ConcurrentHashMap<VirtualFile, CachedValue<VirtualFile?>>()

    fun rootFor(file: VirtualFile): VirtualFile? {
        val directory = file.parent ?: return null
        if (directories.size >= 512) directories.clear()
        return directories.computeIfAbsent(directory) { dir ->
            CachedValuesManager.getManager(project).createCachedValue({
                CachedValueProvider.Result.create(
                    discover(dir), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    ProjectRootModificationTracker.getInstance(project)
                )
            }, false)
        }.value
    }

    private fun discover(directory: VirtualFile): VirtualFile? {
        val boundary = ProjectFileIndex.getInstance(project).getContentRootForFile(directory) ?: return null
        var current: VirtualFile? = directory
        while (current != null) {
            ProgressManager.checkCanceled()
            if (current.name in GENERATED_DIRECTORIES) return null
            val config = current.findChild("dbt_project.yml")
            if (config != null && !config.isDirectory) return current
            if (current == boundary) return null
            current = current.parent
        }
        return null
    }

    override fun dispose() = directories.clear()

    companion object {
        private val GENERATED_DIRECTORIES = setOf("target", "logs", ".git")
    }
}
