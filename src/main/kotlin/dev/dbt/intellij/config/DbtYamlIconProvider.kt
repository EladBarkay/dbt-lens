package dev.dbt.intellij.config

import com.intellij.ide.IconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.dbt.intellij.project.DbtProjectRoots
import javax.swing.Icon

class DbtYamlIconProvider : IconProvider(), DumbAware {
    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        val file = (element as? PsiFile)?.virtualFile ?: return null
        if (
            file.extension.equals("sql", ignoreCase = true) &&
                element.project.getService(DbtProjectRoots::class.java).rootFor(file) != null
        )
            return SQL_ICON
        return ICON.takeIf { DbtYamlFiles.schema(element.project, file) != null }
    }

    companion object {
        val ICON = IconLoader.getIcon("/icons/dbt.svg", DbtYamlIconProvider::class.java)
        val SQL_ICON = IconLoader.getIcon("/icons/dbt-sql.svg", DbtYamlIconProvider::class.java)
    }
}
