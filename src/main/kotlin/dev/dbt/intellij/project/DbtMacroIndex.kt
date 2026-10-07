package dev.dbt.intellij.project

import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import dev.dbt.intellij.lang.DbtLexer
import dev.dbt.intellij.lang.DbtTypes as T
import com.intellij.psi.TokenType

/** Index template tokens independently of the host file type. Never runs dbt or project code. */
class DbtMacroIndex : ScalarIndexExtension<String>() {
    override fun getName() = NAME
    override fun getVersion() = 1
    override fun dependsOnFileContent() = true
    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE
    override fun getInputFilter() = FileBasedIndex.InputFilter {
        it.extension?.lowercase() in setOf("sql", "jinja", "j2")
    }
    override fun getIndexer() = DataIndexer<String, Void, FileContent> { content ->
        val names = linkedMapOf<String, Void?>()
        val lexer = DbtLexer()
        lexer.start(content.contentAsText)
        var headerPosition = 0
        while (lexer.tokenType != null) {
            when (lexer.tokenType) {
                T.STMT_OPEN -> headerPosition = 1
                TokenType.WHITE_SPACE -> Unit
                else -> {
                    if (headerPosition == 2 && lexer.tokenType == T.IDENTIFIER) names[lexer.tokenText!!] = null
                    headerPosition = if (headerPosition == 1 && lexer.tokenText == "macro") 2 else 0
                }
            }
            lexer.advance()
        }
        names
    }
    companion object { val NAME: ID<String, Void> = ID.create("dev.dbt.jinja.macros") }
}
