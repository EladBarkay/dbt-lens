package dev.dbt.intellij.editor

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import dev.dbt.intellij.lang.DbtLexer
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.psi.LocalMacros
import dev.dbt.intellij.psi.ProjectMacros

class DbtCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val lexer = DbtLexer()
                val source = parameters.originalFile.viewProvider.contents
                lexer.start(source)
                var opener: com.intellij.psi.tree.IElementType? = null
                var last: com.intellij.psi.tree.IElementType? = null
                while (lexer.tokenType != null && lexer.tokenStart < parameters.offset) {
                    if (lexer.tokenType == T.IDENTIFIER && parameters.offset <= lexer.tokenEnd) break
                    when (lexer.tokenType) {
                        T.EXPR_OPEN, T.STMT_OPEN -> { opener = lexer.tokenType; last = lexer.tokenType }
                        T.EXPR_CLOSE, T.STMT_CLOSE -> { opener = null; last = null }
                        T.STRING, T.COMMENT, T.TEXT -> if (parameters.offset <= lexer.tokenEnd) return
                        else -> if (lexer.tokenType != com.intellij.psi.TokenType.WHITE_SPACE) last = lexer.tokenType
                    }
                    lexer.advance()
                }
                if (opener == null) return
                val member = Regex("([A-Za-z_][A-Za-z_0-9]*)\\.[A-Za-z_0-9]*$")
                    .find(source.subSequence(0, parameters.offset).toString())?.groupValues?.get(1)
                if (last == T.DOT || member != null || parameters.position.prevSibling?.text == ".") {
                    if (member != null && member == ProjectMacros.namespace(parameters.originalFile) &&
                        !LocalMacros.isShadowed(parameters.position, member)) {
                        addMacros(ProjectMacros.completion(parameters.originalFile, result.prefixMatcher.prefix), result)
                    }
                    return
                }
                if (opener == T.STMT_OPEN) DbtLexer.KEYWORDS.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("Jinja")) }
                BUILTINS.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("dbt context", true)) }
                val local = LocalMacros.declarations(parameters.originalFile)
                addMacros(local, result)
                addMacros(ProjectMacros.completion(parameters.originalFile, result.prefixMatcher.prefix)
                    .filter { macro -> local.none { it.name == macro.name } }, result)
                PsiTreeUtil.getParentOfType(parameters.position, DbtMacro::class.java)?.parameters?.forEach {
                    result.addElement(LookupElementBuilder.create(it).withTypeText("parameter", true))
                }
            }
        })
    }
    private fun addMacros(macros: List<DbtMacro>, result: CompletionResultSet) {
        macros.forEach { macro ->
            macro.name?.let { result.addElement(LookupElementBuilder.create(macro, it)
                .withTailText("(${macro.parameters.joinToString()})", true).withTypeText(macro.containingFile.name, true)) }
        }
    }
    companion object {
        private val BUILTINS = listOf("ref", "source", "var", "config", "target", "this", "adapter", "env_var", "is_incremental", "execute", "return", "log")
    }
}
