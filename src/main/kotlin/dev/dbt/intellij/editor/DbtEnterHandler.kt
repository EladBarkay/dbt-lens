package dev.dbt.intellij.editor

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import dev.dbt.intellij.lang.DbtBlocks
import dev.dbt.intellij.lang.DbtLanguage
import dev.dbt.intellij.lang.DbtTypes as T

class DbtEnterHandler : EnterHandlerDelegate {
    override fun preprocessEnter(file: PsiFile, editor: Editor, caretOffset: Ref<Int>, caretAdvance: Ref<Int>, dataContext: DataContext, originalHandler: EditorActionHandler?): EnterHandlerDelegate.Result {
        if (file.language != DbtLanguage || !CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) return EnterHandlerDelegate.Result.Continue
        PsiDocumentManager.getInstance(file.project).commitDocument(editor.document)
        val offset = caretOffset.get()
        if (offset <= 0 || offset > editor.document.textLength) return EnterHandlerDelegate.Result.Continue
        val leaf = file.findElementAt(offset - 1) ?: return EnterHandlerDelegate.Result.Continue
        val header = generateSequence(leaf) { if (it is com.intellij.psi.PsiFile) null else it.parent }.firstOrNull { it.node?.elementType == T.STATEMENT } ?: return EnterHandlerDelegate.Result.Continue
        if (header.textRange.endOffset != offset || header.node.findChildByType(T.STMT_CLOSE) == null || !DbtBlocks.isStructuralHeader(header.node)) return EnterHandlerDelegate.Result.Continue
        val close = DbtBlocks.CLOSING[DbtBlocks.keyword(header.node)?.text] ?: return EnterHandlerDelegate.Result.Continue
        if (DbtBlocks.closingFor(header.node) != null) return EnterHandlerDelegate.Result.Continue
        val lineStart = editor.document.getLineStartOffset(editor.document.getLineNumber(header.textOffset))
        val indent = editor.document.charsSequence.subSequence(lineStart, header.textOffset).takeWhile { it == ' ' || it == '\t' }.toString()
        // Keep manually written bodies intact; completion templates handle new complete blocks.
        if (editor.document.charsSequence.substring(offset).isNotBlank()) return EnterHandlerDelegate.Result.Continue
        val insertion = "\n$indent    \n$indent{% $close %}"
        editor.document.insertString(offset, insertion)
        editor.caretModel.moveToOffset(offset + 1 + indent.length + 4)
        return EnterHandlerDelegate.Result.Stop
    }
}
