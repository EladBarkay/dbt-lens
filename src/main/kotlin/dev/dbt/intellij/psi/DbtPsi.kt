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
        val member = parent.node.elementType == DbtTypes.ACCESS && parent.lastChild == this && parent.node.findChildByType(DbtTypes.DOT) != null
        val access = parent.takeIf { member }
        var callee: PsiElement = access ?: this
        while (callee.parent?.node?.elementType in setOf(DbtTypes.CALL, DbtTypes.ACCESS) && callee.parent.firstChild == callee) {
            callee = callee.parent
        }
        val binary = callee.parent
        if (binary?.node?.elementType == DbtTypes.BINARY && binary.firstChild != callee &&
            binary.node.getChildren(null).any { it.text in setOf("|", "is") }) return null
        // Attribute member names are not local variables; the namespace/base expression is.
        if (!member && LocalVariables.declaration(this) != null) return LocalVariableReference(this)
        val namespace = access?.firstChild?.takeIf { it is DbtVariable }?.text
        if (access != null && (access.children.size != 2 || access.node.findChildByType(DbtTypes.DOT) == null ||
            namespace == null || !ProjectMacros.hasNamespace(this, namespace))) return null
        val target: PsiElement = access ?: this
        if (target.parent?.node?.elementType != DbtTypes.CALL || target.parent.firstChild != target) return null
        return LocalMacroReference(this, namespace)
    }
}

class LocalMacroReference(element: DbtVariable, private val namespace: String? = null) : PsiPolyVariantReferenceBase<DbtVariable>(element, TextRange(0, element.textLength), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        if (LocalMacros.isShadowed(element, namespace ?: element.text)) return emptyArray()
        val local = if (namespace == null) LocalMacros.candidates(element.containingFile, element.text) else emptyList()
        val candidates = if (local.isNotEmpty()) local else ProjectMacros.candidates(element, element.text, namespace)
        return candidates
        .map { PsiElementResolveResult(it) }.toTypedArray()
    }
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
    fun isShadowed(element: PsiElement, name: String): Boolean {
        val owner = PsiTreeUtil.getParentOfType(element, DbtMacro::class.java)
        if (name in (owner?.parameters ?: emptyList())) return true
        // Conservatively exclude bindings throughout the file, but never treat an assignment's RHS
        // or a loop's iterable as a binding. Those expressions can call project macros.
        val statements = PsiTreeUtil.collectElements(element.containingFile) { it.node?.elementType == DbtTypes.STATEMENT }
        for (statement in statements) {
            val leaves = statement.node.getChildren(null).filter { it.elementType != com.intellij.psi.TokenType.WHITE_SPACE }
            val keyword = leaves.getOrNull(1)?.text
            if (keyword in setOf("set", "for")) {
                if (leaves.any { it.elementType == DbtTypes.BINDING_NAME && it.text == name }) return true
            } else if (keyword in setOf("import", "from", "with")) {
                if (leaves.drop(2).any { it.elementType in setOf(DbtTypes.IDENTIFIER, DbtTypes.KEYWORD) && it.text == name }) return true
            }
        }
        return false
    }
}
