package dev.dbt.intellij.editor

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtCallables
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.psi.DbtVariable
import dev.dbt.intellij.psi.LocalVariables
import dev.dbt.intellij.semantic.DbtTypeInference

class DbtAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element is DbtMacro) {
            element.nameIdentifier?.let { color(it, DECLARATION, holder) }
        } else if (element.node.elementType == T.ARGUMENT_NAME) {
            color(element, ARGUMENT, holder)
        } else if (element.node.elementType in setOf(T.PARAMETER_NAME, T.BINDING_NAME)) {
            color(element, VARIABLE, holder)
        } else if (element is DbtVariable) {
            val access = DbtCallables.memberAccess(element)
            val use = DbtCallables.use(element)
            val binding = access == null && LocalVariables.candidates(element).isNotEmpty()
            val key =
                when {
                    use in setOf(DbtCallables.Use.FILTER, DbtCallables.Use.TEST) -> CALL
                    binding -> VARIABLE
                    DbtTypeInference.symbol(element)?.type?.name in
                        setOf("api.Relation", "api.Column") -> DECLARATION
                    use == DbtCallables.Use.CALL -> CALL
                    access == null &&
                        (element.text in DbtContext.NAMES ||
                            element.text in DbtTypeInference.contextual(element)) -> GLOBAL
                    else -> null // Unbound identifiers/attributes are not automatically variables.
                }
            key?.let { color(element, it, holder) }
        }
    }

    private fun color(element: PsiElement, key: TextAttributesKey, holder: AnnotationHolder) {
        if (element.textLength > 0)
            holder
                .newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(element)
                .textAttributes(key)
                .create()
    }

    companion object {
        val CALL = TextAttributesKey.createTextAttributesKey("DBT_FUNCTION_CALL", Colors.IDENTIFIER)
        val DECLARATION =
            TextAttributesKey.createTextAttributesKey(
                "DBT_FUNCTION_DECLARATION",
                Colors.FUNCTION_DECLARATION,
            )
        val VARIABLE = TextAttributesKey.createTextAttributesKey("DBT_VARIABLE", Colors.IDENTIFIER)
        val GLOBAL =
            TextAttributesKey.createTextAttributesKey(
                "DBT_CONTEXT_GLOBAL",
                Colors.PREDEFINED_SYMBOL,
            )
        val ARGUMENT =
            TextAttributesKey.createTextAttributesKey("DBT_KEYWORD_ARGUMENT", Colors.PARAMETER)
    }
}
