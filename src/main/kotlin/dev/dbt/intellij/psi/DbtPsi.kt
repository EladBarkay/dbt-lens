package dev.dbt.intellij.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import dev.dbt.intellij.lang.*

class DbtFile(provider: FileViewProvider) : PsiFileBase(provider, DbtLanguage) {
    override fun getFileType() = DbtFileType.INSTANCE
    override fun toString() = "DbtJinjaFile"
}

class DbtMacro(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = firstChild?.node?.findChildByType(DbtTypes.DECLARATION_NAME)?.psi
    override fun getName(): String? = nameIdentifier?.text?.takeIf { it.isNotEmpty() }
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
    override fun setName(name: String): PsiElement = throw IncorrectOperationException(
        "Macro rename is deferred until project-wide semantic resolution is available.")
    val parameters: List<String> get() = firstChild?.children?.filter { it.node.elementType == DbtTypes.PARAMETER_NAME }
        ?.map { it.text } ?: emptyList()
}

class DbtVariable(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReference(): PsiReference? {
        // Only direct unqualified calls are eligible. Attributes, filters, imports and dynamic calls are excluded.
        if (parent?.node?.elementType != DbtTypes.CALL || parent.firstChild != this) return null
        var callee: PsiElement = parent
        while (callee.parent?.node?.elementType in setOf(DbtTypes.CALL, DbtTypes.ACCESS) && callee.parent.firstChild == callee) {
            callee = callee.parent
        }
        val binary = callee.parent
        if (binary?.node?.elementType == DbtTypes.BINARY && binary.firstChild != callee &&
            binary.node.getChildren(null).any { it.text in setOf("|", "is") }) return null
        return LocalMacroReference(this)
    }
}

class LocalMacroReference(element: DbtVariable) : PsiPolyVariantReferenceBase<DbtVariable>(element, TextRange(0, element.textLength), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = LocalMacros.candidatesAt(element)
        .map { PsiElementResolveResult(it) }.toTypedArray()
    override fun getVariants(): Array<Any> = emptyArray()
    override fun handleElementRename(newElementName: String): PsiElement = throw IncorrectOperationException("Macro rename is not available yet")
}

object LocalMacros {
    fun declarations(file: PsiFile): List<DbtMacro> = PsiTreeUtil.findChildrenOfType(file, DbtMacro::class.java)
        .filter { macro ->
            // Nested declarations have context-dependent availability; keep the first resolver conservative.
            PsiTreeUtil.getParentOfType(macro, DbtMacro::class.java) == null && macro.parent is DbtFile
        }
    fun candidates(file: PsiFile, name: String): List<DbtMacro> = declarations(file).filter { it.name == name }
    fun candidatesAt(element: DbtVariable): List<DbtMacro> {
        val name = element.text
        val owner = PsiTreeUtil.getParentOfType(element, DbtMacro::class.java)
        if (name in (owner?.parameters ?: emptyList())) return emptyList()
        // Until lexical bindings have their own PSI, conservatively exclude names bound by statement headers.
        // This can miss valid navigation after a scope ends, but cannot navigate a shadowed name to a macro.
        val statements = PsiTreeUtil.collectElements(element.containingFile) { it.node?.elementType == DbtTypes.STATEMENT }
        for (statement in statements) {
            val leaves = statement.node.getChildren(null).filter { it.elementType != com.intellij.psi.TokenType.WHITE_SPACE }
            val keyword = leaves.getOrNull(1)?.text
            if (keyword in setOf("set", "for", "import", "from", "with")) {
                if (leaves.drop(2).any { it.elementType in setOf(DbtTypes.IDENTIFIER, DbtTypes.KEYWORD) && it.text == name }) return emptyList()
            }
        }
        return candidates(element.containingFile, name)
    }
}
