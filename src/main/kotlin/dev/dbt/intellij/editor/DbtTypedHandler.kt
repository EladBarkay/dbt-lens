package dev.dbt.intellij.editor

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.dbt.intellij.lang.DbtLanguage
import dev.dbt.intellij.lang.DbtLexer
import dev.dbt.intellij.lang.DbtTypes as T

class DbtTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.language == DbtLanguage && c in ".|") {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            return Result.STOP
        }
        return Result.CONTINUE
    }
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.language != DbtLanguage || !CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) return Result.CONTINUE
        val offset = editor.caretModel.offset
        val text = editor.document.charsSequence
        if (offset < 2) return Result.CONTINUE
        val pair = when (text.subSequence(offset - 2, offset).toString()) { "{%" -> "%}"; "{{" -> "}}"; "{#" -> "#}"; else -> return Result.CONTINUE }
        val lexer = DbtLexer(); lexer.start(text)
        while (lexer.tokenType != null && lexer.tokenStart < offset - 2) lexer.advance()
        if (lexer.tokenStart != offset - 2 || lexer.tokenType !in setOf(T.STMT_OPEN, T.EXPR_OPEN, T.COMMENT)) return Result.CONTINUE
        val following = text.subSequence(offset, minOf(text.length, offset + 2)).toString()
        if (following != pair) editor.document.insertString(offset, if (pair == "}}" && following.startsWith("}")) "}" else pair)
        editor.caretModel.moveToOffset(offset)
        return Result.CONTINUE
    }
    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result {
        if (file.language != DbtLanguage || !CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET || c !in "%#}") return Result.CONTINUE
        val offset = editor.caretModel.offset
        val text = editor.document.charsSequence
        if (text.getOrNull(offset) != c) return Result.CONTINUE
        val lexer = DbtLexer(); lexer.start(text)
        while (lexer.tokenType != null && lexer.tokenEnd <= offset) lexer.advance()
        if (lexer.tokenType in setOf(T.EXPR_CLOSE, T.STMT_CLOSE) ||
            (lexer.tokenType == T.COMMENT && text.subSequence(offset, lexer.tokenEnd).toString() in setOf("#}", "}"))) {
            editor.caretModel.moveToOffset(offset + 1)
            return Result.STOP
        }
        return Result.CONTINUE
    }
}
