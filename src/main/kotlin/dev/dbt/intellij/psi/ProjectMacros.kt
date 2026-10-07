package dev.dbt.intellij.psi

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.indexing.FileBasedIndex
import dev.dbt.intellij.project.DbtMacroIndex
import dev.dbt.intellij.project.DbtProjectRoots

object ProjectMacros {
    private fun root(element: PsiElement): VirtualFile? = element.containingFile.originalFile.virtualFile?.let {
        element.project.getService(DbtProjectRoots::class.java).rootFor(it)
    }

    fun namespace(element: PsiElement): String? {
        val config = root(element)?.findChild("dbt_project.yml") ?: return null
        val psi = PsiManager.getInstance(element.project).findFile(config) ?: return null
        return CachedValuesManager.getCachedValue(psi) {
            // dbt package names are identifiers. Accept literal plain/quoted YAML scalars only;
            // complex YAML values and Jinja expressions are intentionally not evaluated.
            val name = NAME.find(psi.text)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            CachedValueProvider.Result.create(name, psi)
        }
    }

    fun candidates(element: PsiElement, name: String): List<DbtMacro> {
        val root = root(element) ?: return emptyList()
        if (DumbService.isDumb(element.project)) return emptyList()
        val roots = element.project.getService(DbtProjectRoots::class.java)
        return FileBasedIndex.getInstance().getContainingFiles(DbtMacroIndex.NAME, name,
            GlobalSearchScopesCore.directoryScope(element.project, root, true))
            .filter { roots.rootFor(it) == root }
            .flatMap { file -> PsiManager.getInstance(element.project).findFile(file)?.let { LocalMacros.candidates(it, name) } ?: emptyList() }
    }

    fun completion(element: PsiElement, prefix: String): List<DbtMacro> {
        if (root(element) == null || DumbService.isDumb(element.project)) return emptyList()
        return FileBasedIndex.getInstance().getAllKeys(DbtMacroIndex.NAME, element.project)
            .asSequence().filter { it.startsWith(prefix, ignoreCase = true) }
            .flatMap { candidates(element, it).asSequence() }.toList()
    }

    private val NAME = Regex("(?m)^name:[ \\t]*(?:\"([A-Za-z_][A-Za-z_0-9]*)\"|'([A-Za-z_][A-Za-z_0-9]*)'|([A-Za-z_][A-Za-z_0-9]*))[ \\t]*(?:#.*)?\\r?$")
}
