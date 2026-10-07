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
        val access = DbtCallables.memberAccess(this)
        val use = DbtCallables.use(this)
        if (use == DbtCallables.Use.TEST) return null
        val namespace = access?.firstChild?.takeIf { it is DbtVariable }?.text
        val filter = use == DbtCallables.Use.FILTER
        if (filter && namespace == null && text in DbtCallables.BUILTIN_FILTERS) return null
        if (access == null && !filter && LocalVariables.candidates(this).isNotEmpty()) return LocalVariableReference(this)
        if (access != null && (access.children.size != 2 || namespace == null || !ProjectMacros.hasNamespace(this, namespace))) return null
        // The base of a qualified access is a namespace/object, not the referenced member.
        if (access == null && parent.node.elementType == DbtTypes.ACCESS && parent.firstChild == this) return null
        if (use == DbtCallables.Use.VALUE &&
            LocalMacros.candidates(containingFile, text).isEmpty() && ProjectMacros.candidates(this, text, namespace).isEmpty()) return null
        return LocalMacroReference(this, namespace, filter)
    }
}

class LocalMacroReference(element: DbtVariable, private val namespace: String? = null, private val filter: Boolean = false) : PsiPolyVariantReferenceBase<DbtVariable>(element, TextRange(0, element.textLength), true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        if ((!filter || namespace != null) && LocalMacros.isShadowed(element, namespace ?: element.text)) return emptyArray()
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
        if (LocalVariables.candidates(element, name).isNotEmpty()) return true
        // Imports are retained as balanced tokens; until their grammar is modeled, suppress
        // imported names conservatively rather than guessing a project macro.
        val statements = PsiTreeUtil.collectElements(element.containingFile) { it.node?.elementType == DbtTypes.STATEMENT }
        for (statement in statements) {
            val leaves = statement.node.getChildren(null).filter { it.elementType != com.intellij.psi.TokenType.WHITE_SPACE }
            val keyword = leaves.getOrNull(1)?.text
            if (keyword in setOf("import", "from")) {
                if (leaves.drop(2).any { it.elementType in setOf(DbtTypes.IDENTIFIER, DbtTypes.KEYWORD) && it.text == name }) return true
            }
        }
        return false
    }
}
