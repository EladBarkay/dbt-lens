package dev.dbt.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dbt.intellij.io.BoundedFiles
import dev.dbt.intellij.project.DbtProjectConfiguration
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class DbtIoTest : BasePlatformTestCase() {
    fun testByteBoundsIncludeMultibyteTextAndGrowthAfterOpening() {
        val file = Files.createTempFile("dbt-bounded-read", ".txt")
        try {
            Files.writeString(file, "é")
            assertEquals("é", BoundedFiles.text(file, 2))
            try {
                BoundedFiles.text(file, 1)
                fail("The limit counts bytes, not characters")
            } catch (_: IllegalArgumentException) {}
            Files.writeString(file, "abc")
            BoundedFiles.reader(file, 5).use { reader ->
                Files.writeString(file, "def", StandardOpenOption.APPEND)
                try {
                    reader.readText()
                    fail("Growth after the initial stat must remain bounded")
                } catch (_: java.io.IOException) {}
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    fun testLiteralConfigurationRejectsAmbiguousAndDynamicValues() {
        assertEquals("test_project", DbtProjectConfiguration.scalar("name: test_project", "name"))
        assertEquals("test_project", DbtProjectConfiguration.scalar("name:\ttest_project", "name"))
        assertNull(DbtProjectConfiguration.scalar("name: first\n'name': second\n", "name"))
        for (value in
            listOf(
                "'{{ env_var(\"NAME\") }}'",
                "*alias",
                "&anchor value",
                "|",
                "\"escaped\\nname\"",
            )) {
            assertNull(DbtProjectConfiguration.scalar("name: $value\n", "name"))
        }
        assertEquals(
            "my'project",
            DbtProjectConfiguration.scalar("'name': 'my''project' # literal\n", "name"),
        )
    }

    fun testPathListsPreserveQuotedCommasCommentsAndRejectPartialInterpretation() {
        myFixture.addFileToProject(
            "warehouse/dbt_project.yml",
            "'model-paths': ['models, curated', 'model''s',] # output\n",
        )
        val model = myFixture.addFileToProject("warehouse/models/orders.sql", "select 1")
        val root = model.virtualFile.parent.parent
        assertEquals(
            listOf("models, curated", "model's"),
            DbtProjectConfiguration.paths(project, root, "model-paths"),
        )
        val config =
            com.intellij.psi.PsiManager.getInstance(project)
                .findFile(root.findChild("dbt_project.yml")!!)!!
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.psi.PsiDocumentManager.getInstance(project)
                .getDocument(config)!!
                .setText("model-paths:\n  - models # comment\n  - *runtime_alias\n")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEmpty(DbtProjectConfiguration.paths(project, root, "model-paths"))
    }
}
