package dev.dbt.intellij

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.psi.PsiDirectory
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Physical files have a directory parent with no AST, unlike configureByText's light files. */
class DbtStartupRegressionTest : BasePlatformTestCase() {
    private var id = 0

    private fun open(text: String) {
        val folder = "warehouse${id++}"
        myFixture.addFileToProject("$folder/dbt_project.yml", "name: warehouse\n")
        val offset = text.indexOf("<caret>")
        val file =
            myFixture.addFileToProject("$folder/models/startup.jinja", text.replace("<caret>", ""))
        assertTrue(file.parent is PsiDirectory)
        assertNull(file.parent!!.node)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(offset)
    }

    fun testEnterOutsideStatementsInPhysicalTemplateNeverWalksDirectoryAst() {
        for (source in
            listOf(
                "select 1<caret>",
                "{# comment #}<caret>",
                "{% raw %}text<caret>{% endraw %}",
                "<caret>",
            )) {
            open(source)
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
            assertTrue(myFixture.editor.document.text.contains('\n'))
        }
    }

    fun testPhysicalTemplateNameCompletionDoesNotWalkDirectoryAst() {
        open("{{ <caret> }}")
        val names = myFixture.completeBasic().map { it.lookupString }
        assertContainsElements(names, "ref", "graph", "api")
    }

    fun testCompletePhysicalMacroHeaderStillGeneratesItsCloser() {
        open("{% macro run() %}<caret>")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        assertEquals("{% macro run() %}\n    \n{% endmacro %}", myFixture.editor.document.text)
    }
}
