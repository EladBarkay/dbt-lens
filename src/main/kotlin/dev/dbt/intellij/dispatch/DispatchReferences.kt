package dev.dbt.intellij.dispatch

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.IncorrectOperationException
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.psi.DbtVariable
import dev.dbt.intellij.psi.ProjectMacros
import dev.dbt.intellij.resources.DbtValue

class DispatchReference(element: PsiElement, private val call: DispatchCall) : PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(1, element.textLength - 1), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = DispatchTargets.implementations(element, call).map { PsiElementResolveResult(it) }.toTypedArray()
    override fun handleElementRename(newElementName: String): PsiElement = throw IncorrectOperationException("Dispatch rename is not available yet")
    companion object {
        fun forValue(value: DbtValue): DispatchReference? {
            val argument = value.parent.takeIf { it.node?.elementType == T.ARGUMENT } ?: return null
            val call = argument.parent
            val dispatch = DispatchCall.from(call) ?: return null
            val keyword = argument.node.findChildByType(T.ARGUMENT_NAME)?.text
            val first = call.children.firstOrNull { it.node?.elementType == T.ARGUMENT }
            if (keyword != "macro_name" && (keyword != null || first != argument)) return null
            if (ProjectMacros.root(value) == null) return null
            return DispatchReference(value, dispatch)
        }
    }
}

class DispatchNavigationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        if (sourceElement == null) return null
        val macro = PsiTreeUtil.getParentOfType(sourceElement, DbtMacro::class.java, false)
        if (macro?.nameIdentifier?.textRange?.containsOffset(offset) == true) {
            return DispatchTargets.defaults(macro).toTypedArray<PsiElement>().takeIf { it.isNotEmpty() }
        }
        val value = PsiTreeUtil.getParentOfType(sourceElement, DbtValue::class.java, false)
        if (value != null) {
            val reference = DispatchReference.forValue(value) ?: return null
            return reference.multiResolve(false).mapNotNull { it.element }.toTypedArray().takeIf { it.isNotEmpty() }
        }
        val variable = PsiTreeUtil.getParentOfType(sourceElement, DbtVariable::class.java, false) ?: return null
        if (variable.text != "dispatch" || variable.parent.node?.elementType != T.ACCESS || variable.parent.lastChild != variable) return null
        val call = DispatchCall.from(variable.parent.parent) ?: return null
        return DispatchTargets.implementations(variable, call).toTypedArray<PsiElement>().takeIf { it.isNotEmpty() }
    }
}

/** An implementation is named adapter__foo but its dispatch usages are indexed under the string foo. */
class DispatchUsageSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val macro = parameters.elementToSearch as? DbtMacro ?: return
        if (ProjectMacros.root(macro) == null) return
        val implementation = DispatchImplementation.from(macro.name) ?: return
        parameters.optimizer.searchWord(implementation.name, parameters.effectiveSearchScope, UsageSearchContext.IN_STRINGS, true, macro)
    }
}
