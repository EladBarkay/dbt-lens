package dev.dbt.intellij.editor

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import dev.dbt.intellij.lang.DbtTypes as T

class DbtBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = arrayOf(BracePair(T.EXPR_OPEN, T.EXPR_CLOSE, true), BracePair(T.STMT_OPEN, T.STMT_CLOSE, true),
        BracePair(T.LPAREN, T.RPAREN, false), BracePair(T.LBRACKET, T.RBRACKET, false), BracePair(T.LBRACE, T.RBRACE, false))
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int) = openingBraceOffset
}
