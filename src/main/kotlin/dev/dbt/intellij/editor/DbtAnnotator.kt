package dev.dbt.intellij.editor

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.psi.DbtVariable

class DbtAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element is DbtMacro) {
            element.nameIdentifier?.let { holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(it).textAttributes(DECLARATION).create() }
        } else if (element is DbtVariable) {
            val access = element.parent.takeIf { it.node.elementType == T.ACCESS && it.lastChild == element && it.node.findChildByType(T.DOT) != null }
            val target = access ?: element
            if (target.parent.node.elementType == T.CALL && target.parent.firstChild == target) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(element).textAttributes(CALL).create()
            }
        }
    }
    companion object {
        val CALL = TextAttributesKey.createTextAttributesKey("DBT_FUNCTION_CALL", DefaultLanguageHighlighterColors.FUNCTION_CALL)
        val DECLARATION = TextAttributesKey.createTextAttributesKey("DBT_FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
    }
}
