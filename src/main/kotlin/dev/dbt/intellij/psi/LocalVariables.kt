package dev.dbt.intellij.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.util.TextRange
import com.intellij.util.IncorrectOperationException
import dev.dbt.intellij.lang.DbtBlocks
import dev.dbt.intellij.lang.DbtTypes as T

/** Static lexical bindings: macro parameters, enclosing loop names and preceding set statements. */
object LocalVariables {
    fun declaration(element: DbtVariable): PsiElement? {
        val name = element.text
        val owner = PsiTreeUtil.getParentOfType(element, DbtMacro::class.java)
        var scope: PsiElement? = element.parent
        while (scope != null && scope != owner?.parent) {
            val preceding = scope.children.filter { it.textRange.endOffset <= element.textOffset }.asReversed()
            for (sibling in preceding) {
                val header = when (sibling.node.elementType) {
                    T.STATEMENT -> sibling.node
                    T.BLOCK -> DbtBlocks.headers(sibling.node).firstOrNull()?.takeIf { DbtBlocks.keyword(it)?.text == "set" }
                    else -> null
                } ?: continue
                if (DbtBlocks.keyword(header)?.text == "set" && header.findChildByType(T.DOT) == null)
                    header.getChildren(null).firstOrNull { it.elementType == T.BINDING_NAME && it.text == name }?.let { return it.psi }
            }
            // A loop binding is visible in its body, never in its own iterable/header.
            if (scope.node.elementType == T.BLOCK) {
                val header = DbtBlocks.headers(scope.node).firstOrNull()
                if (DbtBlocks.keyword(header ?: scope.node)?.text == "for" && element.textOffset >= header!!.textRange.endOffset)
                    header.getChildren(null).firstOrNull { it.elementType == T.BINDING_NAME && it.text == name }?.let { return it.psi }
            }
            if (scope == owner) {
                owner.firstChild?.children?.firstOrNull { it.node.elementType == T.PARAMETER_NAME && it.text == name }?.let { return it }
                return null
            }
            if (scope == element.containingFile) break
            scope = scope.parent
        }
        return null
    }
}

class LocalVariableReference(element: DbtVariable) : PsiReferenceBase<DbtVariable>(element, TextRange(0, element.textLength), true) {
    override fun resolve() = LocalVariables.declaration(element)
    override fun handleElementRename(newElementName: String): PsiElement = throw IncorrectOperationException("Variable rename is not available yet")
}
