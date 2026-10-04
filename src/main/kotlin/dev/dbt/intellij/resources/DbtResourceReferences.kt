package dev.dbt.intellij.resources

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtVariable
import dev.dbt.intellij.psi.ProjectMacros

class DbtValue(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReference(): PsiReference? {
        if (node.firstChildNode?.elementType != T.STRING || parent.node.elementType != T.ARGUMENT ||
            ProjectMacros.root(this) == null) return null
        dev.dbt.intellij.dispatch.DispatchReference.forValue(this)?.let { return it }
        val call = ResourceCall.from(parent.parent) ?: return null
        return DbtResourceReference(this, TextRange(1, textLength - 1), call)
    }
}

class DbtResourceReference(element: PsiElement, range: TextRange, private val call: ResourceCall) : PsiPolyVariantReferenceBase<PsiElement>(element, range, true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = element.project.getService(DbtResources::class.java)
        .targets(element, call).map { PsiElementResolveResult(it) }.toTypedArray()
    override fun getVariants(): Array<Any> = emptyArray()
    override fun handleElementRename(newElementName: String): PsiElement = throw com.intellij.util.IncorrectOperationException("Resource rename is not available yet")
}

class DbtResourceNavigationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val variable = com.intellij.psi.util.PsiTreeUtil.getParentOfType(sourceElement, DbtVariable::class.java, false) ?: return null
        if (variable.parent.node.elementType != T.CALL || variable.parent.firstChild != variable || ProjectMacros.root(variable) == null) return null
        val call = ResourceCall.from(variable.parent) ?: return null
        return variable.project.getService(DbtResources::class.java).targets(variable, call).toTypedArray().takeIf { it.isNotEmpty() }
    }
}
