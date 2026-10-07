package dev.dbt.intellij.editor

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import dev.dbt.intellij.lang.DbtBlocks
import dev.dbt.intellij.lang.DbtTypes as T

/** Closing tags are structural navigation targets, not semantic macro usages. */
class DbtEnclosureNavigationHandler : GotoDeclarationHandler, DumbAware {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val keyword = sourceElement?.node?.takeIf { it.elementType == T.KEYWORD } ?: return null
        val statement = keyword.treeParent?.takeIf { it.elementType == T.STATEMENT } ?: return null
        if (DbtBlocks.keyword(statement) !== keyword) return null
        val opening = DbtBlocks.openingFor(statement) ?: return null
        val target = if (opening.treeParent.elementType == T.MACRO) opening.treeParent.psi
            else DbtBlocks.keyword(opening)?.psi ?: return null
        return arrayOf(target)
    }
}
