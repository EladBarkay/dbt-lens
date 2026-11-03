package dev.dbt.intellij.lang

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.TokenType

/** The same token rules drive indexed macros, excluded packages and snapshot declarations. */
internal object DbtDeclarations {
    data class Declaration(val name: String, val offset: Int)

    fun scan(source: CharSequence, keyword: String): List<Declaration> {
        val declarations = mutableListOf<Declaration>()
        val lexer = DbtLexer()
        lexer.start(source)
        var position = 0
        while (lexer.tokenType != null) {
            ProgressManager.checkCanceled()
            when (lexer.tokenType) {
                DbtTypes.STMT_OPEN -> position = 1
                TokenType.WHITE_SPACE -> Unit
                else -> {
                    if (position == 2 && lexer.tokenType == DbtTypes.IDENTIFIER)
                        declarations += Declaration(lexer.tokenText!!, lexer.tokenStart)
                    position = if (position == 1 && lexer.tokenText == keyword) 2 else 0
                }
            }
            lexer.advance()
        }
        return declarations
    }
}
