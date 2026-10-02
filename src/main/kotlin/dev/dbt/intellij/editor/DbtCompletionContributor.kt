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
                // Member completion needs a namespace/type resolver; avoid suggesting unqualified symbols after '.'.
                if (last == T.DOT || parameters.position.prevSibling?.text == ".") return
                if (opener == T.STMT_OPEN) DbtLexer.KEYWORDS.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("Jinja")) }
                BUILTINS.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("dbt context", true)) }
                LocalMacros.declarations(parameters.originalFile).forEach { macro ->
                    macro.name?.let { result.addElement(LookupElementBuilder.create(macro, it)
                        .withTailText("(${macro.parameters.joinToString()})", true).withTypeText("local macro")) }
                }
                PsiTreeUtil.getParentOfType(parameters.position, DbtMacro::class.java)?.parameters?.forEach {
                    result.addElement(LookupElementBuilder.create(it).withTypeText("parameter", true))
                }
            }
        })
    }
    companion object {
        private val BUILTINS = listOf("ref", "source", "var", "config", "target", "this", "adapter", "env_var", "is_incremental", "execute", "return", "log")
    }
}
