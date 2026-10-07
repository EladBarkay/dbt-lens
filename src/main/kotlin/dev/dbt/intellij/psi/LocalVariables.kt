package dev.dbt.intellij.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import dev.dbt.intellij.lang.DbtBlocks
import dev.dbt.intellij.lang.DbtTypes as T

class DbtBinding(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
    override fun getName() = text.takeIf { it.isNotEmpty() }
    override fun getNameIdentifier(): PsiElement? = firstChild
    override fun setName(name: String): PsiElement = throw IncorrectOperationException("Variable rename is not available yet")
    internal fun scopeOwner(): PsiElement = generateSequence(parent) { it.parent }.firstOrNull {
        it is DbtMacro || it is PsiFile || (it.node.elementType == T.BLOCK && LocalVariables.keyword(it.firstChild) in setOf("for", "with", "call", "test", "set", "filter", "block"))
    } ?: containingFile
    override fun getUseScope() = LocalSearchScope(scopeOwner())
    override fun getReference(): PsiReference? {
        if (node.elementType != T.BINDING_NAME || LocalVariables.keyword(parent) != "set") return null
        // Namespace writes reference their root. Reassignments in the same scope remain usages
        // of the existing symbol, while Go to Declaration on reads still chooses the latest write.
        if (parent.node.findChildByType(T.DOT) != null || LocalVariables.candidates(this).any { it.scopeOwner() == scopeOwner() })
            return LocalVariableReference(this)
        return null
    }
}

/** One visibility model shared by references, completion and static type inference. */
object LocalVariables {
    fun keyword(element: PsiElement) = DbtBlocks.keyword(element.node)?.text
    fun candidates(element: PsiElement, name: String = element.text): List<DbtBinding> = visible(element)[name].orEmpty()
    fun declaration(element: PsiElement, name: String = element.text): DbtBinding? = candidates(element, name).singleOrNull()
    fun visible(element: PsiElement): Map<String, List<DbtBinding>> {
        val result = linkedMapOf<String, MutableList<DbtBinding>>()
        val complete = hashSetOf<String>()
        fun add(binding: DbtBinding, definite: Boolean) {
            val name = binding.name ?: return
            if (name in complete) return
            result.getOrPut(name) { mutableListOf() }.add(binding)
            if (definite) complete += name
        }
        fun preceding(sibling: PsiElement, conditional: Boolean = false) {
            val header = when (sibling.node.elementType) {
                T.STATEMENT -> sibling
                T.BLOCK -> sibling.firstChild
                else -> null
            } ?: return
            when (keyword(header)) {
                "set" -> if (header.node.findChildByType(T.DOT) == null)
                    header.children.filterIsInstance<DbtBinding>().forEach { add(it, !conditional) }
                "if" -> if (sibling.node.elementType == T.BLOCK) {
                    // Branch-dependent definitions remain multiple candidates, never a guessed branch.
                    sibling.children.reversed().forEach { preceding(it, true) }
                }
            }
        }
        var scope: PsiElement? = element.parent
        while (scope != null) {
            val branchStart = if (scope.node.elementType == T.BLOCK && keyword(scope.firstChild) == "if")
                scope.children.lastOrNull { it.textOffset <= element.textOffset && keyword(it) in setOf("elif", "else") }?.textOffset ?: scope.textOffset
                else scope.textOffset
            scope.children.filter { it.textRange.endOffset <= element.textOffset && it.textOffset >= branchStart }.asReversed().forEach { preceding(it) }
            val header = scope.firstChild
            if (scope.node.elementType == T.BLOCK && header != null) {
                val kind = keyword(header)
                val filterStart = header.node.getChildren(null).firstOrNull { it.text == "if" }?.startOffset
                if (element.textOffset >= header.textRange.endOffset || (kind == "for" && filterStart != null && element.textOffset > filterStart)) {
                    val elseStart = if (kind == "for") scope.children.firstOrNull { keyword(it) == "else" }?.textOffset else null
                    if (kind in setOf("for", "with", "call", "test") && (elseStart == null || element.textOffset < elseStart))
                        header.children.filterIsInstance<DbtBinding>().forEach { add(it, true) }
                }
                if (kind == "test") break
            }
            if (scope is DbtMacro) {
                // Defaults are evaluated outside the macro's body scope.
                if (element.textOffset >= (header?.textRange?.endOffset ?: Int.MAX_VALUE))
                    header?.children?.filterIsInstance<DbtBinding>()?.forEach { add(it, true) }
            }
            if (scope is PsiFile) break
            scope = scope.parent
        }
        return result.mapValues { it.value.distinct() }
    }
    fun loop(element: PsiElement): PsiElement? {
        for (scope in generateSequence(element.parent) { it.parent }) {
            if (scope is DbtMacro || scope is PsiFile) return null
            if (scope.node.elementType == T.BLOCK && keyword(scope.firstChild) == "for") {
                val header = scope.firstChild
                val branch = DbtBlocks.headers(scope.node).firstOrNull { DbtBlocks.keyword(it)?.text == "else" }
                if (element.textOffset >= header.textRange.endOffset && (branch == null || element.textOffset < branch.startOffset)) return scope
            }
        }
        return null
    }
}

class LocalVariableReference(element: PsiElement) : PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(0, element.textLength), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = LocalVariables.candidates(element).map { PsiElementResolveResult(it) }.toTypedArray()
    override fun isReferenceTo(other: PsiElement): Boolean = other is DbtBinding && LocalVariables.candidates(element).any {
        it.name == other.name && it.scopeOwner() == other.scopeOwner()
    }
    override fun handleElementRename(newElementName: String): PsiElement = throw IncorrectOperationException("Variable rename is not available yet")
}
