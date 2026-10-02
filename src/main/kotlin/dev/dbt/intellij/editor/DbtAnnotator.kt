package dev.dbt.intellij.editor

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.psi.DbtVariable
import dev.dbt.intellij.psi.LocalVariables

class DbtAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element is DbtMacro) {
            element.nameIdentifier?.let { color(it, DECLARATION, holder) }
        } else if (element.node.elementType in setOf(T.PARAMETER_NAME, T.BINDING_NAME)) {
            color(element, VARIABLE, holder)
        } else if (element is DbtVariable) {
            val access = element.parent.takeIf { it.node.elementType == T.ACCESS && it.lastChild == element && it.node.findChildByType(T.DOT) != null }
            val target = access ?: element
            val call = target.parent.node.elementType == T.CALL && target.parent.firstChild == target
            val global = access == null && element.text in DbtContext.NAMES && LocalVariables.declaration(element) == null
            color(element, when { global -> GLOBAL; call -> CALL; else -> VARIABLE }, holder)
        }
    }
    private fun color(element: PsiElement, key: TextAttributesKey, holder: AnnotationHolder) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(element).textAttributes(key).create()
    }
    companion object {
        val CALL = TextAttributesKey.createTextAttributesKey("DBT_FUNCTION_CALL", Colors.FUNCTION_DECLARATION)
        val DECLARATION = TextAttributesKey.createTextAttributesKey("DBT_FUNCTION_DECLARATION", Colors.FUNCTION_DECLARATION)
        val VARIABLE = TextAttributesKey.createTextAttributesKey("DBT_VARIABLE", Colors.INSTANCE_FIELD)
        val GLOBAL = TextAttributesKey.createTextAttributesKey("DBT_CONTEXT_GLOBAL", Colors.METADATA)
    }
}
