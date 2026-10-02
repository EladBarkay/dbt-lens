package dev.dbt.intellij.config

import com.intellij.ide.IconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon

class DbtYamlIconProvider : IconProvider(), DumbAware {
    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        val file = (element as? PsiFile)?.virtualFile ?: return null
        return ICON.takeIf { DbtYamlFiles.schema(element.project, file) != null }
    }
    companion object { val ICON = IconLoader.getIcon("/icons/dbt.svg", DbtYamlIconProvider::class.java) }
}
