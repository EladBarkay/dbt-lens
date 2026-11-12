package dev.dbt.intellij.dispatch

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.psi.ProjectMacros

/** null packages means configuration was present but cannot be safely interpreted. */
data class DispatchOrder(val packages: List<String>? = null, val configured: Boolean = false)

interface DispatchConfiguration {
    fun order(file: PsiFile, namespace: String): DispatchOrder

    companion object {
        val EP =
            ExtensionPointName.create<DispatchConfiguration>("dev.dbt.jinja.dispatchConfiguration")
    }
}

object DispatchTargets {
    fun implementations(element: PsiElement, call: DispatchCall): List<DbtMacro> {
        val roots = ProjectMacros.roots(element)
        val host = ProjectMacros.hostRoot(element) ?: return emptyList()
        val hostName = ProjectMacros.scalar(element.project, host, "name") ?: return emptyList()
        val namespaces =
            roots.mapNotNull { ProjectMacros.scalar(element.project, it, "name") }.distinct()
        val packages =
            if (call.namespace == null) namespaces.sortedBy { if (it == hostName) 0 else 1 }
            else {
                val file =
                    host.findChild("dbt_project.yml")?.let {
                        PsiManager.getInstance(element.project).findFile(it)
                    } ?: return emptyList()
                val bridge = DispatchConfiguration.EP.extensionList.firstOrNull()
                val order =
                    bridge?.order(file, call.namespace)
                        ?: if (
                            Regex("""(?m)^(?:dispatch|'dispatch'|"dispatch")[ \t]*:""")
                                .containsMatchIn(file.text)
                        )
                            DispatchOrder(configured = true)
                        else DispatchOrder()
                if (order.configured) order.packages ?: return emptyList()
                else if (call.namespace in namespaces || call.namespace == "dbt")
                    listOf(hostName, call.namespace).distinct()
                else return emptyList()
            }
        val names =
            ProjectMacros.names(element)
                .mapNotNull { name -> DispatchImplementation.from(name)?.let { name to it } }
                .filter { it.second.name == call.name }
                .sortedWith(
                    compareBy(
                        { it.second.adapter != "default" },
                        { it.second.adapter },
                        { it.first },
                    ),
                )
        return packages
            .flatMap { namespace ->
                names.flatMap { (name, _) ->
                    ProgressManager.checkCanceled()
                    ProjectMacros.candidates(element, name, namespace)
                }
            }
            .distinct()
    }

    fun defaults(macro: DbtMacro): List<DbtMacro> {
        val implementation = DispatchImplementation.from(macro.name) ?: return emptyList()
        if (implementation.adapter == "default") return emptyList()
        val name = "default__${implementation.name}"
        val own = ProjectMacros.candidates(macro, name, ProjectMacros.namespace(macro))
        // A root override may only supply an adapter-specific implementation. Offer visible
        // dependency defaults when its own package has none, without asserting runtime selection.
        return own.ifEmpty {
                ProjectMacros.names(macro)
                    .takeIf { name in it }
                    ?.let {
                        ProjectMacros.roots(macro)
                            .mapNotNull { ProjectMacros.scalar(macro.project, it, "name") }
                            .distinct()
                            .flatMap { ProjectMacros.candidates(macro, name, it) }
                    }
                    .orEmpty()
            }
            .filter { it != macro }
            .distinct()
    }
}
