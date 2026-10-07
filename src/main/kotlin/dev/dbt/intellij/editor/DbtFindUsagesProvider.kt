package dev.dbt.intellij.editor

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import dev.dbt.intellij.lang.DbtLexer
import dev.dbt.intellij.lang.DbtTypes
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.psi.DbtBinding
import com.intellij.psi.PsiNamedElement

class DbtFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner() = DefaultWordsScanner(DbtLexer(), TokenSet.create(DbtTypes.IDENTIFIER, DbtTypes.KEYWORD), DbtTypes.COMMENTS, DbtTypes.STRINGS)
    override fun canFindUsagesFor(psiElement: PsiElement) = psiElement is DbtMacro || psiElement is DbtBinding
    override fun getHelpId(psiElement: PsiElement): String? = null
    override fun getType(element: PsiElement) = if (element is DbtBinding) "Jinja variable" else "Jinja macro"
    override fun getDescriptiveName(element: PsiElement) = (element as? PsiNamedElement)?.name ?: element.text
    override fun getNodeText(element: PsiElement, useFullName: Boolean) = getDescriptiveName(element)
}
