package dev.dbt.intellij.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import dev.dbt.intellij.psi.DbtFile
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.psi.DbtVariable
import com.intellij.extapi.psi.ASTWrapperPsiElement

class DbtParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = DbtLexer()
    override fun createParser(project: Project?) = DbtParser()
    override fun getFileNodeType() = DbtTypes.FILE
    override fun getWhitespaceTokens() = TokenSet.create(TokenType.WHITE_SPACE)
    override fun getCommentTokens() = DbtTypes.COMMENTS
    override fun getStringLiteralElements() = DbtTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        DbtTypes.MACRO -> DbtMacro(node)
        DbtTypes.VARIABLE -> DbtVariable(node)
        else -> ASTWrapperPsiElement(node)
    }
    override fun createFile(viewProvider: FileViewProvider): PsiFile = DbtFile(viewProvider)
}
