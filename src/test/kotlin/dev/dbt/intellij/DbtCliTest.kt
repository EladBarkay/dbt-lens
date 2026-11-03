package dev.dbt.intellij

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dbt.intellij.artifacts.*
import dev.dbt.intellij.cli.*
import dev.dbt.intellij.config.DbtSettings
import java.nio.file.Files
import java.nio.file.Path

class DbtCliTest : BasePlatformTestCase() {
    private var oldAsync: String? = null

    override fun setUp() {
        super.setUp()
        oldAsync = System.getProperty("intellij.progress.task.ignoreHeadless")
        System.setProperty("intellij.progress.task.ignoreHeadless", "true")
    }

    override fun tearDown() {
        try {
            cli.cancel()
            if (cli.state.running) done()
        } finally {
            if (oldAsync == null) System.clearProperty("intellij.progress.task.ignoreHeadless")
            else System.setProperty("intellij.progress.task.ignoreHeadless", oldAsync!!)
            super.tearDown()
        }
    }

    override fun createTempDirTestFixture() =
        com.intellij.testFramework.fixtures.IdeaTestFixtureFactory.getFixtureFactory()
            .createTempDirTestFixture()

    private val cli
        get() = project.getService(DbtCli::class.java)

    private fun setup(output: String = "target"): com.intellij.psi.PsiFile {
        myFixture.addFileToProject(
            "repo/warehouse/dbt_project.yml",
            "name: warehouse\ntarget-path: $output\n",
        )
        return myFixture.addFileToProject("repo/warehouse/models/orders.sql", "select 1")
    }

    private fun context(file: com.intellij.psi.PsiFile) =
        project.getService(DbtArtifacts::class.java).request(file)!!

    private fun fake(extra: String = "") {
        val text =
            """#!/usr/bin/python3
import json, os, pathlib, sys, time
args = sys.argv[1:]
root = pathlib.Path.cwd()
target = pathlib.Path(args[args.index('--target-path') + 1])
with (root / 'invocation.json').open('w') as f:
    json.dump({'args': args, 'cwd': str(root), 'token': os.getenv('TEST_TOKEN'), 'venv': os.getenv('VIRTUAL_ENV')}, f)
$extra
target.mkdir(parents=True, exist_ok=True)
manifest = {'metadata': {'dbt_schema_version': 'https://schemas.getdbt.com/dbt/manifest/v12.json', 'project_name': 'warehouse'},
 'nodes': {'model.warehouse.orders': {'resource_type': 'model', 'name': 'orders', 'package_name': 'warehouse',
 'original_file_path': 'models/orders.sql', 'fqn': ['warehouse', 'models', 'orders'], 'raw_code': (root/'models/orders.sql').read_text()}},
 'macros': {'macro.warehouse.build': {'resource_type': 'macro', 'name': 'build', 'package_name': 'warehouse',
 'original_file_path': 'macros/build.sql'}}}
if 'compile' in args:
    manifest['nodes']['model.warehouse.orders']['compiled_code'] = 'select 42 as id'
(target/'manifest.json').write_text(json.dumps(manifest))
print(json.dumps({'info': {'name': 'Done', 'msg': 'Fake dbt completed'}}), flush=True)
if '--inline' in args:
    print(json.dumps({'info': {'name': 'CompiledNode', 'msg': 'Compiled inline node is: select 7'}, 'data': {'compiled': 'select 7'}}), flush=True)
"""
        val binary = Path.of(myFixture.tempDirPath).resolve("repo/warehouse/.venv/bin/dbt")
        Files.createDirectories(binary.parent)
        Files.writeString(binary, text)
        assertTrue(binary.toFile().setExecutable(true))
        Files.writeString(binary.parent.parent.resolve("pyvenv.cfg"), "home = /usr/bin\n")
    }

    private fun done() {
        PlatformTestUtil.waitWithEventsDispatching(
            "dbt finishes",
            { !cli.state.running && cli.state.id > 0 },
            15,
        )
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun button(container: java.awt.Container, name: String): javax.swing.JButton? =
        container.components.firstNotNullOfOrNull {
            if (it is javax.swing.JButton && it.text == name) it
            else (it as? java.awt.Container)?.let { child -> button(child, name) }
        }

    fun testPlannerUsesVectorsAndConfiguredEnvironmentWithoutShellEvaluation() {
        val file = setup("saved output")
        fake()
        myFixture.addFileToProject(
            "repo/warehouse/env.local",
            "export TEST_TOKEN='literal $(touch NEVER)' # comment\nOTHER=ok # ignored\n",
        )
        val profiles = myFixture.tempDirFixture.findOrCreateDir("profiles folder")
        val state =
            DbtSettings.State(
                profilesDirectory = profiles.path,
                profile = "analytics",
                target = "dev",
                environmentFile = "env.local",
            )
        val plan =
            DbtCommandPlanner.plan(
                DbtCommand(
                    DbtCommandKind.INLINE,
                    context(file),
                    invocation = "{{ warehouse.build('a b') }}",
                ),
                state,
                mapOf("PATH" to "/usr/bin:/bin"),
            )
        assertEquals(context(file).root, plan.directory)
        assertEquals("{{ warehouse.build('a b') }}", plan.arguments.last())
        assertTrue(plan.arguments.contains(profiles.path))
        assertTrue(plan.arguments.contains(context(file).target.toString()))
        assertEquals("literal $(touch NEVER)", plan.environment["TEST_TOKEN"])
        assertEquals("ok", plan.environment["OTHER"])
        assertEquals(
            context(file).root.resolve(".venv").toString(),
            plan.environment["VIRTUAL_ENV"],
        )
        assertFalse(Files.exists(context(file).root.resolve("NEVER")))
    }

    fun testExplicitExecutableAndPathFallback() {
        val file = setup()
        fake()
        val root = context(file).root
        val binary = root.resolve(".venv/bin/dbt")
        assertEquals(
            binary,
            DbtCommandPlanner.plan(
                    DbtCommand(DbtCommandKind.PARSE, context(file)),
                    DbtSettings.State(executable = ".venv/bin/dbt"),
                    emptyMap(),
                )
                .executable,
        )
        val external = myFixture.addFileToProject("tools/dbt", "#!/bin/sh\nexit 0\n")
        assertTrue(Path.of(external.virtualFile.path).toFile().setExecutable(true))
        Files.delete(binary)
        assertEquals(
            Path.of(external.virtualFile.path),
            DbtCommandPlanner.plan(
                    DbtCommand(DbtCommandKind.PARSE, context(file)),
                    DbtSettings.State(),
                    mapOf("PATH" to external.virtualFile.parent.path),
                )
                .executable,
        )
    }

    fun testSavedResourceSelectorHandlesDependencyAndVersionWithoutHostPath() {
        val file = setup()
        fake()
        assertTrue(cli.start(DbtCommand(DbtCommandKind.PARSE, context(file))))
        done()
        val node =
            project
                .getService(DbtArtifacts::class.java)
                .load(context(file))
                .nodes["model.warehouse.orders"]!!
        val dependency =
            node.copy(
                packageName = "dependency",
                fqn = listOf("dependency", "models", "orders", "v2"),
            )
        assertEquals(
            "package:dependency,resource_type:model,fqn:dependency.models.orders.v2",
            DbtCommandPlanner.selector(dependency),
        )
        for (fqn in
            listOf(
                emptyList(),
                listOf("dependency", "models", "orders.v2"),
                listOf("dependency", "orders\tother"),
                listOf("dependency", "orders\u0000"),
            )) {
            try {
                DbtCommandPlanner.selector(dependency.copy(fqn = fqn))
                fail("Incomplete or ambiguous FQNs must not select every matching basename")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("cannot identify this resource"))
            }
        }
    }

    fun testGenerateButtonCreatesMissingManifestAndRefreshesPanel() {
        val file = setup("output")
        fake()
        myFixture.addFileToProject(
            "repo/warehouse/output/compiled/warehouse/models/orders.sql",
            "select 'old output'",
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val panel = DbtArtifactPanel(project)
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "Generate available without manifest",
                { panel.stateText.startsWith("No manifest.json") },
                10,
            )
            button(panel, "Generate manifest")!!.doClick()
            done()
            assertEquals(DbtCliPhase.SUCCEEDED, cli.state.phase)
            PlatformTestUtil.waitWithEventsDispatching(
                "New artifact shown",
                { panel.resourceCount == 1 && panel.stateText.startsWith("Saved dbt output") },
                10,
            )
            PlatformTestUtil.waitWithEventsDispatching(
                "Parse does not reuse old compiled files",
                { panel.previewText.contains("No saved compiled SQL") },
                10,
            )
            assertTrue(Files.exists(context(file).target!!.resolve("manifest.json")))
            assertFalse(cli.state.output.contains("--project-dir"))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    fun testCompilationWritesSqlAndRefreshesCurrentFile() {
        val file = setup()
        fake()
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val panel = DbtArtifactPanel(project)
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "Context loaded",
                { panel.stateText.startsWith("No manifest.json") },
                10,
            )
            assertTrue(
                panel.executeCommand(
                    DbtCommand(
                        DbtCommandKind.COMPILE,
                        context(file),
                        DbtCommandPlanner.selector("warehouse", "models/orders.sql"),
                    )
                )
            )
            done()
            assertEquals(DbtCliPhase.SUCCEEDED, cli.state.phase)
            PlatformTestUtil.waitWithEventsDispatching(
                "Compiled SQL refreshed",
                { panel.previewText == "select 42 as id" },
                10,
            )
            val invocation = Files.readString(context(file).root.resolve("invocation.json"))
            assertTrue(invocation.contains("package:warehouse,path:models/orders.sql"))
            assertTrue(invocation.contains("--indirect-selection"))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    fun testCompileButtonSavesPendingEditsBeforeDbtReadsThem() {
        val file = setup()
        fake()
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val panel = DbtArtifactPanel(project)
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "Context loaded",
                { panel.stateText.startsWith("No manifest.json") },
                10,
            )
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.setText("select 99 as id")
            }
            button(panel, "Compile current")!!.doClick()
            done()
            assertEquals(DbtCliPhase.SUCCEEDED, cli.state.phase)
            assertEquals("select 99 as id", Files.readString(Path.of(file.virtualFile.path)))
            assertEquals(
                "select 99 as id",
                project
                    .getService(DbtArtifacts::class.java)
                    .load(context(file))
                    .nodes["model.warehouse.orders"]!!
                    .rawCode,
            )
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    fun testCompletedCommandRefreshesPanelOpenedAfterGeneration() {
        val file = setup()
        fake()
        assertTrue(
            cli.start(
                DbtCommand(
                    DbtCommandKind.COMPILE,
                    context(file),
                    DbtCommandPlanner.selector("warehouse", "models/orders.sql"),
                )
            )
        )
        done()
        assertEquals(DbtCliPhase.SUCCEEDED, cli.state.phase)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val panel = DbtArtifactPanel(project)
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "Completed command shown after context lookup",
                { panel.previewText == "select 42 as id" },
                10,
            )
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    fun testModelWithLocalMacroCompilesAsModelRatherThanOpeningCallDialog() {
        val file = setup()
        fake()
        assertTrue(cli.start(DbtCommand(DbtCommandKind.PARSE, context(file))))
        done()
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText(
                "{% macro helper() %}1{% endmacro %}\nselect {{ helper() }}"
            )
        }
        val panel = DbtArtifactPanel(project)
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "Model selected",
                { panel.resourceCount == 1 },
                10,
            )
            button(panel, "Compile current")!!.doClick()
            done()
            assertEquals(DbtCliPhase.SUCCEEDED, cli.state.phase)
            assertEquals(DbtCommandKind.COMPILE, cli.state.command!!.kind)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    fun testMacroInvocationUsesInlineAndDisplaysOnlyItsResult() {
        val file = setup()
        fake()
        val macro =
            myFixture.addFileToProject(
                "repo/warehouse/macros/build.sql",
                "{% macro build(value) %}select {{ value }}{% endmacro %}",
            )
        myFixture.configureFromExistingVirtualFile(macro.virtualFile)
        val panel = DbtArtifactPanel(project)
        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "Macro context loaded",
                { panel.stateText.startsWith("No manifest.json") },
                10,
            )
            assertTrue(
                panel.executeCommand(
                    DbtCommand(
                        DbtCommandKind.INLINE,
                        context(file),
                        invocation = "{{ warehouse.build(7) }}",
                        sourcePath = macro.virtualFile.path,
                        resourceId = "macro.warehouse.build",
                    )
                )
            )
            done()
            assertEquals(DbtCliPhase.SUCCEEDED, cli.state.phase)
            PlatformTestUtil.waitWithEventsDispatching(
                "Macro output displayed",
                { panel.previewText == "select 7" },
                10,
            )
            panel.load(file.virtualFile)
            PlatformTestUtil.waitWithEventsDispatching(
                "Macro output not reused for model",
                { panel.previewText == "select 42 as id" },
                10,
            )
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    fun testFailureReportsExitCodeAndDoesNotPretendArtifactsExist() {
        val file = setup()
        fake("print('Compilation failed', flush=True)\nsys.exit(2)")
        assertTrue(cli.start(DbtCommand(DbtCommandKind.PARSE, context(file))))
        done()
        assertEquals(DbtCliPhase.FAILED, cli.state.phase)
        assertTrue(cli.state.message.contains("code 2"))
        assertTrue(cli.state.output.contains("Compilation failed"))
        assertFalse(Files.exists(context(file).target!!.resolve("manifest.json")))
    }

    fun testCancelPreventsOverlapAndAllowsLaterCommands() {
        val file = setup()
        fake("print('Waiting for cancel', flush=True)\ntime.sleep(30)")
        val command = DbtCommand(DbtCommandKind.PARSE, context(file))
        assertTrue(cli.start(command))
        assertFalse(cli.start(command))
        PlatformTestUtil.waitWithEventsDispatching(
            "Process started",
            { cli.state.output.contains("Waiting for cancel") },
            10,
        )
        cli.cancel()
        done()
        assertEquals(DbtCliPhase.CANCELLED, cli.state.phase)
        fake()
        assertTrue(cli.start(command))
        done()
        assertEquals(DbtCliPhase.SUCCEEDED, cli.state.phase)
    }

    fun testZeroExitWithoutFreshManifestIsNotSuccess() {
        val file = setup()
        fake("sys.exit(0)")
        assertTrue(cli.start(DbtCommand(DbtCommandKind.PARSE, context(file))))
        done()
        assertEquals(DbtCliPhase.FAILED, cli.state.phase)
        assertTrue(cli.state.message.contains("without writing a new manifest"))
    }

    fun testMalformedEnvironmentAndUnsafeOutputProduceUsefulErrors() {
        val file = setup()
        fake()
        myFixture.addFileToProject("repo/warehouse/env.local", "SECRET='private-value\n")
        val settings = project.getService(DbtSettings::class.java)
        settings.loadState(DbtSettings.State(environmentFile = "env.local"))
        assertTrue(cli.start(DbtCommand(DbtCommandKind.PARSE, context(file))))
        done()
        assertTrue(cli.state.message.contains("line 1"))
        assertFalse(cli.state.message.contains("private-value"))
        assertFalse(Files.exists(context(file).root.resolve("invocation.json")))
        try {
            DbtCommandPlanner.plan(
                DbtCommand(
                    DbtCommandKind.PARSE,
                    context(file).copy(target = context(file).root.resolve("../outside")),
                ),
                DbtSettings.State(),
                emptyMap(),
            )
            fail("External output must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("inside"))
        }
    }

    fun testChunkedJsonLogsRetainCompiledOutputAndBoundLargeLogs() {
        val out = DbtCommandOutput()
        out.accept("{\"info\":{\"name\":\"Compiled")
        out.accept("Node\",\"msg\":\"Compiled!\"},\"data\":{\"compiled\":\"select 7\\n\"}}\n")
        assertEquals("select 7\n", out.compiled)
        assertEquals("Compiled!\n", out.text)
        out.accept("x".repeat(210_000) + "\n")
        assertTrue(out.text.length <= 200_000)
        out.accept("{\"info\":{\"name\":\"NoNodesForSelectionCriteria\",\"msg\":\"No match\"}}")
        out.flush()
        assertTrue(out.selectionMissing)
    }
}
