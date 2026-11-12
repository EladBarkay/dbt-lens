package dev.dbt.intellij

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.dbt.intellij.artifacts.*
import dev.dbt.intellij.editor.DbtDocumentationProvider
import dev.dbt.intellij.psi.DbtMacro
import dev.dbt.intellij.resources.DbtValue
import java.nio.file.Files

class DbtArtifactsTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture() =
        com.intellij.testFramework.fixtures.IdeaTestFixtureFactory.getFixtureFactory()
            .createTempDirTestFixture()

    private fun obj(vararg values: Pair<String, String>) =
        JsonObject().apply { values.forEach { addProperty(it.first, it.second) } }

    private fun array(vararg values: String) = JsonArray().apply { values.forEach(::add) }

    private fun node(
        name: String,
        kind: String = "model",
        pkg: String = "warehouse",
        path: String = "models/$name.sql",
        raw: String? = null,
    ): JsonObject =
        obj(
                "unique_id" to "$kind.$pkg.$name",
                "resource_type" to kind,
                "name" to name,
                "package_name" to pkg,
                "original_file_path" to path,
                "description" to "Description for $name",
            )
            .apply {
                raw?.let { addProperty("raw_code", it) }
                add("config", obj("materialized" to "view"))
            }

    private fun manifest(vararg records: JsonObject): JsonObject =
        JsonObject().apply {
            add(
                "metadata",
                obj(
                    "dbt_schema_version" to "https://schemas.getdbt.com/dbt/manifest/v12.json",
                    "project_name" to "warehouse",
                    "generated_at" to "2026-10-07T09:00:00Z",
                ),
            )
            for (kind in listOf("nodes", "sources", "macros")) {
                add(
                    kind,
                    JsonObject().apply {
                        records
                            .filter {
                                when (it.get("resource_type").asString) {
                                    "source" -> kind == "sources"
                                    "macro" -> kind == "macros"
                                    else -> kind == "nodes"
                                }
                            }
                            .forEach { add(it.get("unique_id").asString, it) }
                    },
                )
            }
        }

    private fun setup(json: JsonObject, target: String = "target"): com.intellij.psi.PsiFile {
        myFixture.addFileToProject(
            "repo/warehouse/dbt_project.yml",
            "name: warehouse\ntarget-path: $target\n",
        )
        myFixture.addFileToProject("repo/warehouse/$target/manifest.json", json.toString())
        return myFixture.addFileToProject("repo/warehouse/models/usage.sql", "{{ ref('orders') }}")
    }

    private val service
        get() = project.getService(DbtArtifacts::class.java)

    private fun saved(file: com.intellij.psi.PsiFile) = service.load(service.request(file)!!, true)

    private fun change(file: com.intellij.psi.PsiFile, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            PsiDocumentManager.getInstance(project).getDocument(file)!!.setText(text)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    fun testModelsSourcesMacrosAndCatalogEnrichWithoutYamlOrDatabasePlugins() {
        val order =
            node("orders", raw = "select 1").apply {
                addProperty("compiled_code", "select 1 as id")
                add(
                    "columns",
                    JsonObject().apply {
                        add("id", obj("name" to "id", "description" to "Primary <key>"))
                    },
                )
            }
        val source =
            node("customers", "source", path = "models/schema.yml").apply {
                addProperty("source_name", "raw")
            }
        val macro =
            node("format_value", "macro", path = "macros/format.sql").apply {
                add(
                    "arguments",
                    JsonArray().apply {
                        add(obj("name" to "value", "type" to "string", "description" to "Input"))
                    },
                )
            }
        val test =
            node("unique_orders", "test", path = "models/schema.yml").apply {
                add(
                    "depends_on",
                    JsonObject().apply { add("nodes", array("model.warehouse.orders")) },
                )
            }
        val file = setup(manifest(order, source, macro, test))
        myFixture.addFileToProject("repo/warehouse/models/orders.sql", "select 1")
        myFixture.addFileToProject(
            "repo/warehouse/target/catalog.json",
            JsonObject()
                .apply {
                    add(
                        "metadata",
                        obj(
                            "dbt_schema_version" to
                                "https://schemas.getdbt.com/dbt/catalog/v1.json",
                            "generated_at" to "2026-10-07T08:00:00Z",
                        ),
                    )
                    add(
                        "nodes",
                        JsonObject().apply {
                            add(
                                "model.warehouse.orders",
                                JsonObject().apply {
                                    add(
                                        "metadata",
                                        obj(
                                            "database" to "analytics",
                                            "schema" to "public",
                                            "name" to "orders",
                                        ),
                                    )
                                    add(
                                        "columns",
                                        JsonObject().apply {
                                            add(
                                                "ID",
                                                obj(
                                                    "name" to "ID",
                                                    "type" to "INTEGER",
                                                    "comment" to "Catalog comment",
                                                ),
                                            )
                                            add("status", obj("name" to "status", "type" to "TEXT"))
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
                .toString(),
        )
        val snapshot = saved(file)
        assertTrue(snapshot.valid)
        assertEquals(4, snapshot.nodes.size)
        val resource = snapshot.nodes.getValue("model.warehouse.orders")
        assertEquals("analytics.public.orders", resource.relation)
        assertEquals(ArtifactColumn("id", "Primary <key>", "INTEGER"), resource.columns.first())
        assertEquals(2, resource.columns.size)
        val literal =
            PsiTreeUtil.findChildrenOfType(file, DbtValue::class.java).single {
                it.text == "'orders'"
            }
        val html = DbtDocumentationProvider().generateDoc(literal, literal)!!
        assertTrue(html.contains("Primary &lt;key&gt;"))
        assertTrue(html.contains("unique_orders"))
        assertTrue(html.contains("INTEGER"))
        assertEquals("select 1 as id", ArtifactReader.compiled(service.request(file)!!, resource))
    }

    fun testCustomOutputAndDependenciesAreScopedToHostProject() {
        val file = setup(manifest(node("orders", pkg = "utils")), "output")
        myFixture.addFileToProject(
            "repo/warehouse/dbt_packages/utils/dbt_project.yml",
            "name: utils\n",
        )
        val definition =
            myFixture.addFileToProject(
                "repo/warehouse/dbt_packages/utils/models/orders.sql",
                "select 2",
            )
        assertEquals("output", service.request(file)!!.target!!.fileName.toString())
        assertEquals(service.request(file)!!.root, service.request(definition)!!.root)
        val resource = saved(file).nodes.values.single()
        assertEquals(definition.virtualFile, service.file(file, resource))
        val unrelated =
            myFixture.addFileToProject(
                "repo/warehouse/unrelated/dbt_project.yml",
                "name: unrelated\n",
            )
        assertNull(service.file(unrelated, resource))
        val ordinary = myFixture.addFileToProject("other/ordinary.sql", "select 1")
        assertNull(service.request(ordinary))
    }

    fun testInvalidOrUnsupportedManifestNeverReplacesSourceNavigation() {
        val json = manifest(node("orders"))
        val file = setup(json)
        val definition = myFixture.addFileToProject("repo/warehouse/models/orders.sql", "select 1")
        val artifact = myFixture.findFileInTempDir("repo/warehouse/target/manifest.json")
        val artifactPath = java.nio.file.Path.of(artifact.path)
        for (content in
            listOf(
                "{",
                json
                    .deepCopy()
                    .apply { getAsJsonObject("metadata").addProperty("project_name", "other") }
                    .toString(),
                json
                    .deepCopy()
                    .apply {
                        getAsJsonObject("metadata")
                            .addProperty(
                                "dbt_schema_version",
                                "https://schemas.getdbt.com/dbt/manifest/v99.json",
                            )
                    }
                    .toString(),
                json.toString() + "{}",
            )) {
            Files.writeString(artifactPath, content)
            val snapshot = saved(file)
            assertFalse(snapshot.valid)
            assertFalse(snapshot.messages.isEmpty())
            val literal =
                PsiTreeUtil.findChildrenOfType(file, DbtValue::class.java).single {
                    it.text == "'orders'"
                }
            assertEquals(
                definition.virtualFile,
                literal.reference!!.resolve()!!.containingFile.virtualFile,
            )
        }
    }

    fun testMissingOrInvalidCatalogRetainsManifestMetadata() {
        val file = setup(manifest(node("orders")))
        assertTrue(saved(file).valid)
        myFixture.addFileToProject("repo/warehouse/target/catalog.json", "{")
        val snapshot = saved(file)
        assertTrue(snapshot.valid)
        assertEquals(1, snapshot.nodes.size)
        assertTrue(snapshot.messages.single().startsWith("Catalog unavailable"))
    }

    fun testOlderCompiledSqlAndSavedCompiledFileFallback() {
        val json =
            manifest(
                node("orders").apply { addProperty("compiled_sql", "select 8") },
                node("other").apply { addProperty("compiled", true) },
            )
        json
            .getAsJsonObject("metadata")
            .addProperty("dbt_schema_version", "https://schemas.getdbt.com/dbt/manifest/v8.json")
        val file = setup(json)
        myFixture.addFileToProject(
            "repo/warehouse/target/compiled/warehouse/models/other.sql",
            "select 9",
        )
        val snapshot = saved(file)
        val request = service.request(file)!!
        assertEquals(
            "select 8",
            ArtifactReader.compiled(request, snapshot.nodes.getValue("model.warehouse.orders")),
        )
        assertEquals(
            "select 9",
            ArtifactReader.compiled(request, snapshot.nodes.getValue("model.warehouse.other")),
        )
        json
            .getAsJsonObject("nodes")
            .getAsJsonObject("model.warehouse.other")
            .addProperty("compiled", false)
        Files.writeString(request.target!!.resolve("manifest.json"), json.toString())
        assertNull(
            ArtifactReader.compiled(request, saved(file).nodes.getValue("model.warehouse.other")),
        )
    }

    fun testUnsavedSourceChangesAreReportedAndMacroCommentsRemainPrimary() {
        val macroText = "{% macro format(value) %}{{ value }}{% endmacro %}"
        val file =
            setup(
                manifest(
                    node("orders", raw = "select 1"),
                    node("format", "macro", path = "macros/format.sql").apply {
                        addProperty("macro_sql", macroText)
                        addProperty("description", "YAML description")
                        add(
                            "arguments",
                            JsonArray().apply { add(obj("name" to "value", "type" to "string")) },
                        )
                    },
                ),
            )
        val source = myFixture.addFileToProject("repo/warehouse/models/orders.sql", "select 1")
        val macros =
            myFixture.addFileToProject(
                "repo/warehouse/macros/format.sql",
                "{# Source comment #}\n$macroText",
            )
        val snapshot = saved(file)
        assertFalse(service.changed(file, snapshot.nodes.getValue("model.warehouse.orders")))
        change(source, "select 2")
        assertTrue(service.changed(file, snapshot.nodes.getValue("model.warehouse.orders")))
        val macro = PsiTreeUtil.findChildOfType(macros, DbtMacro::class.java)!!
        val html = DbtDocumentationProvider().generateDoc(macro, macro)!!
        assertTrue(html.indexOf("Source comment") < html.indexOf("YAML description"))
        assertTrue(html.contains("Documented arguments"))
        assertNull(
            ArtifactReader.compiled(
                service.request(file)!!,
                snapshot.nodes.getValue("macro.warehouse.format"),
            ),
        )
    }

    fun testCacheRefreshesAfterExternalReplacementDeletionAndRecreation() {
        val file = setup(manifest(node("orders")))
        val request = service.request(file)!!
        assertEquals(1, service.load(request).nodes.size)
        val path = request.target!!.resolve("manifest.json")
        Files.writeString(path, manifest(node("orders"), node("new_model")).toString())
        assertEquals(2, service.load(request).nodes.size)
        Files.delete(path)
        assertFalse(service.load(request).valid)
        Files.writeString(path, manifest(node("recreated")).toString())
        assertEquals("recreated", service.load(request).nodes.values.single().name)
    }

    fun testVersionedResourcesAndPackageAmbiguityDoNotChooseWrongDocs() {
        val first =
            node("orders").apply {
                addProperty("unique_id", "model.warehouse.orders.v1")
                addProperty("version", 1)
                addProperty("latest_version", 2)
            }
        val second =
            node("orders").apply {
                addProperty("unique_id", "model.warehouse.orders.v2")
                addProperty("version", 2)
                addProperty("latest_version", 2)
            }
        val file = setup(manifest(first, second, node("orders", pkg = "utils")))
        myFixture.addFileToProject(
            "repo/warehouse/dbt_packages/utils/dbt_project.yml",
            "name: utils\n",
        )
        val snapshot = saved(file)
        assertEquals(
            "2",
            service
                .resources(file, dev.dbt.intellij.resources.ResourceCall("ref", "orders"), snapshot)
                .single()
                .version,
        )
        assertEquals(
            "1",
            service
                .resources(
                    file,
                    dev.dbt.intellij.resources.ResourceCall("ref", "orders", version = "1"),
                    snapshot,
                )
                .single()
                .version,
        )
        assertEquals(
            "utils",
            service
                .resources(
                    file,
                    dev.dbt.intellij.resources.ResourceCall("ref", "orders", "utils"),
                    snapshot,
                )
                .single()
                .packageName,
        )
    }

    fun testPathTraversalAndDynamicOutputConfigurationAreRejected() {
        val file = setup(manifest(node("outside", path = "../outside.sql")))
        assertEmpty(saved(file).nodes.values)
        val config =
            com.intellij.psi.PsiManager.getInstance(project)
                .findFile(myFixture.findFileInTempDir("repo/warehouse/dbt_project.yml"))!!
        for (target in
            listOf("'{{ env_var(\"OUTPUT\") }}'", "../outside", "/tmp/other-artifacts")) {
            change(config, "name: warehouse\ntarget-path: $target\n")
            assertNull(service.request(file)!!.target)
            assertFalse(saved(file).valid)
        }
    }

    fun testLineageDepthDirectionCyclesAndNodeLimit() {
        fun n(name: String, parents: Set<String>) =
            ArtifactNode(
                name,
                "model",
                name,
                "warehouse",
                "models/$name.sql",
                "",
                emptyList(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                parents,
            )
        val snapshot =
            ArtifactSnapshot(
                mapOf(
                    "a" to n("a", setOf("c")),
                    "b" to n("b", setOf("a")),
                    "c" to n("c", setOf("b")),
                    "d" to n("d", setOf("b")),
                ),
                valid = true,
            )
        assertEquals(
            setOf("a", "b"),
            LineageSlice.build(snapshot, "a", 1, LineageDirection.DOWNSTREAM).layers.keys,
        )
        assertEquals(
            setOf("a", "c"),
            LineageSlice.build(snapshot, "a", 1, LineageDirection.UPSTREAM).layers.keys,
        )
        assertEquals(4, LineageSlice.build(snapshot, "a", 3, LineageDirection.BOTH).layers.size)
        val limited = LineageSlice.build(snapshot, "a", 3, LineageDirection.BOTH, 2)
        assertEquals(2, limited.layers.size)
        assertTrue(limited.truncated)
        val missingParents =
            ArtifactSnapshot(
                mapOf("a" to n("a", (1..10_000).map { "missing.$it" }.toSet())),
                valid = true,
            )
        val bounded = LineageSlice.build(missingParents, "a", 1, LineageDirection.UPSTREAM)
        assertEquals(setOf("a"), bounded.layers.keys)
        assertTrue("Missing IDs must not bypass the traversal work limit", bounded.truncated)
    }

    fun testToolWindowLoadsSavedSqlAndDoesNotModifySourceOrOutput() {
        val file =
            setup(
                manifest(
                    node("orders", raw = "select 1").apply {
                        addProperty("compiled_code", "select 1 as order_id")
                    },
                    node("raw_orders", "source", path = "models/schema.yml").apply {
                        addProperty("source_name", "raw")
                    },
                ),
            )
        val orders = myFixture.addFileToProject("repo/warehouse/models/orders.sql", "select 1")
        myFixture.configureFromExistingVirtualFile(orders.virtualFile)
        val panel = DbtArtifactPanel(project)
        try {
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
                "Compiled preview loads",
                { panel.previewText == "select 1 as order_id" },
                10,
            )
            assertEquals(1, panel.resourceCount)
            assertEquals("select 1", orders.text)
            val viewer =
                com.intellij.openapi.editor.EditorFactory.getInstance().allEditors.first {
                    it.document.text == "select 1 as order_id"
                }
            assertTrue(viewer.isViewer)
            change(orders, "select 2")
            panel.load(orders.virtualFile, true)
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
                "Stale source labeled",
                { panel.stateText.startsWith("Source changed") },
                10,
            )
            assertEquals("select 1 as order_id", panel.previewText)
            assertEquals("select 2", orders.text)
            val request = service.request(file)!!
            assertTrue(
                Files.readString(request.target!!.resolve("manifest.json"))
                    .contains("select 1 as order_id"),
            )
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    fun testToolWindowSwitchesBetweenMacrosAndRejectsUnrelatedFiles() {
        setup(
            manifest(
                node("first", "macro", path = "macros/shared.sql"),
                node("second", "macro", path = "macros/shared.sql"),
            ),
        )
        val macros =
            myFixture.addFileToProject(
                "repo/warehouse/macros/shared.sql",
                "{% macro first() %}{% endmacro %}\n{% macro second() %}{% endmacro %}",
            )
        myFixture.configureFromExistingVirtualFile(macros.virtualFile)
        val panel = DbtArtifactPanel(project)
        try {
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
                "Both macros are selectable",
                {
                    panel.resourceCount == 2 &&
                        panel.previewText.contains("A macro definition is not compiled SQL")
                },
                10,
            )
            panel.selectResource(1)
            val ordinary = myFixture.addFileToProject("ordinary.sql", "select 1")
            panel.load(ordinary.virtualFile)
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
                "Non-dbt files excluded",
                { panel.stateText == "This file is outside a dbt project" },
                10,
            )
            assertEquals("", panel.previewText)
            assertEquals(0, panel.resourceCount)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    fun testManifestSizeAndDuplicateSectionLimitsFailWithReadableStatus() {
        val file = setup(manifest(node("orders")))
        val request = service.request(file)!!
        val path = request.target!!.resolve("manifest.json")
        java.io.RandomAccessFile(path.toFile(), "rw").use {
            it.setLength(ArtifactReader.MAX_BYTES + 1)
        }
        assertTrue(saved(file).messages.single().contains("64 MB"))
        Files.writeString(path, "{\"metadata\":{},\"metadata\":{}}")
        assertTrue(saved(file).messages.single().contains("Duplicate artifact section"))
    }

    fun testQuotedConfigurationKeysAndLiteralHashInPaths() {
        val file = setup(manifest(node("orders")))
        val config =
            com.intellij.psi.PsiManager.getInstance(project)
                .findFile(myFixture.findFileInTempDir("repo/warehouse/dbt_project.yml"))!!
        myFixture.addFileToProject(
            "repo/warehouse/saved # output/manifest.json",
            manifest(node("orders")).toString(),
        )
        change(
            config,
            "'name': 'warehouse'\n\"target-path\": 'saved # output' # keep the literal hash\n'model-paths': ['custom # models']\n",
        )
        assertEquals("saved # output", service.request(file)!!.target!!.fileName.toString())
        assertTrue(saved(file).valid)
        assertEquals(
            listOf("custom # models"),
            dev.dbt.intellij.project.DbtProjectConfiguration.paths(
                project,
                file.virtualFile.parent.parent,
                "model-paths",
            ),
        )
    }

    fun testLenientJsonAndDuplicateLineageEntriesAreRejected() {
        val file = setup(manifest(node("orders")))
        val path = service.request(file)!!.target!!.resolve("manifest.json")
        val json = manifest(node("orders")).toString()
        Files.writeString(path, json.replace("\"name\":\"orders\"", "name:'orders'"))
        assertFalse("Non-JSON syntax must not be accepted", saved(file).valid)
        Files.writeString(
            path,
            json.dropLast(1) +
                ",\"parent_map\":{\"model.warehouse.orders\":[],\"model.warehouse.orders\":[]}}",
        )
        assertFalse("Duplicate lineage entries must not silently overwrite", saved(file).valid)
    }

    fun testMalformedRelativePathsAreRejectedWithoutThrowing() {
        for (path in listOf("models/\u0000.sql", "models//orders.sql", "models/orders.sql/")) {
            assertFalse(ArtifactReader.safeRelative(path))
        }
    }

    fun testMalformedSavedFqnCannotBecomeASelector() {
        val record =
            node("orders").apply {
                add(
                    "fqn",
                    array("warehouse", "orders").apply { add(com.google.gson.JsonNull.INSTANCE) },
                )
            }
        val file = setup(manifest(record))
        val savedNode = saved(file).nodes.getValue("model.warehouse.orders")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            dev.dbt.intellij.cli.DbtCommandPlanner.selector(savedNode)
        }
    }

    fun testSourceHoverAndPartialCatalogErrors() {
        val file =
            setup(
                manifest(
                    node("customers", "source", path = "models/schema.yml").apply {
                        addProperty("source_name", "raw")
                        add(
                            "columns",
                            JsonObject().apply {
                                add(
                                    "id",
                                    obj("name" to "id", "description" to "Customer identifier"),
                                )
                            },
                        )
                    },
                ),
            )
        change(file, "{{ source('raw', 'customers') }}")
        myFixture.addFileToProject(
            "repo/warehouse/target/catalog.json",
            JsonObject()
                .apply {
                    add(
                        "metadata",
                        obj(
                            "dbt_schema_version" to
                                "https://schemas.getdbt.com/dbt/catalog/v1.json",
                        ),
                    )
                    add("errors", array("Warehouse metadata lookup failed"))
                }
                .toString(),
        )
        val literal =
            PsiTreeUtil.findChildrenOfType(file, DbtValue::class.java).single {
                it.text == "'customers'"
            }
        val html = DbtDocumentationProvider().generateDoc(literal, literal)!!
        assertTrue(html.contains("Customer identifier"))
        assertTrue(html.contains("Catalog generation reported errors"))
    }

    fun testPythonOutputAndSymlinkEscapeAreNotDisplayedAsSql() {
        val file =
            setup(
                manifest(
                    node("orders", path = "models/orders.py").apply {
                        addProperty("language", "python")
                        addProperty("compiled_code", "def model(dbt, session): pass")
                    },
                ),
            )
        val request = service.request(file)!!
        assertNull(ArtifactReader.compiled(request, saved(file).nodes.values.single()))
        val outside = Files.createTempDirectory("dbt-artifacts-external")
        try {
            Files.writeString(
                outside.resolve("manifest.json"),
                manifest(node("outside")).toString(),
            )
            Files.delete(request.target!!.resolve("manifest.json"))
            Files.createSymbolicLink(
                request.target.resolve("manifest.json"),
                outside.resolve("manifest.json"),
            )
            assertFalse(saved(file).valid)
            assertTrue(
                saved(file).messages.single().contains("inside the dbt project's output directory"),
            )
        } finally {
            Files.deleteIfExists(request.target!!.resolve("manifest.json"))
            Files.deleteIfExists(outside.resolve("manifest.json"))
            Files.deleteIfExists(outside)
        }
    }

    fun testDocumentationRenderingUsesIdeFontAndWrapsLongTestNames() {
        val testName = "relationships_stg_orders__customer_id__customer_id__ref_customers".repeat(3)
        val file =
            setup(
                manifest(
                    node("orders", raw = "select 1").apply {
                        addProperty("description", "One record per order.")
                        addProperty(
                            "relation_name",
                            "`elementary_ci`.`elad_test_catalog`.`stg_orders`",
                        )
                        add(
                            "columns",
                            JsonObject().apply {
                                add(
                                    "order_id",
                                    obj("name" to "order_id", "description" to "Primary <key>."),
                                )
                                add("status", obj("name" to "status"))
                                add("customer_id", obj("name" to "customer_id"))
                            },
                        )
                    },
                    node(testName, "test", path = "models/schema.yml").apply {
                        add(
                            "depends_on",
                            JsonObject().apply { add("nodes", array("model.warehouse.orders")) },
                        )
                    },
                ),
            )
        myFixture.addFileToProject("repo/warehouse/models/orders.sql", "select 2")
        val snapshot = saved(file)
        val resource = snapshot.nodes.getValue("model.warehouse.orders")
        val pane = ArtifactDocumentationPane()
        try {
            pane.text = ArtifactDocumentation.body(file, resource, snapshot, showStaleness = false)
            assertEquals(com.intellij.util.ui.UIUtil.getLabelFont().family, pane.font.family)
            assertFalse("Body text must not inherit a bold label font", pane.font.isBold)
            assertFalse(pane.isEditable)
            assertFalse(pane.text.contains("Source has changed"))
            assertTrue(pane.text.contains("Description"))
            assertFalse(pane.text.contains(">Type<"))
            assertTrue(
                ArtifactDocumentation.body(file, resource, snapshot).contains("Source has changed"),
            )
            for (width in listOf(360, 800)) {
                pane.setSize(width, 1000)
                pane.doLayout()
                assertTrue(
                    "Documentation must wrap at $width px, minimum=${pane.minimumSize.width}",
                    pane.minimumSize.width <= width,
                )
                System.getenv("DBT_DOCS_RENDER_DIR")?.let { directory ->
                    val image =
                        java.awt.image.BufferedImage(
                            width,
                            1000,
                            java.awt.image.BufferedImage.TYPE_INT_RGB,
                        )
                    image.createGraphics().also { graphics ->
                        try {
                            pane.paint(graphics)
                        } finally {
                            graphics.dispose()
                        }
                    }
                    Files.createDirectories(java.nio.file.Path.of(directory))
                    javax.imageio.ImageIO.write(
                        image,
                        "png",
                        java.nio.file.Path.of(directory, "docs-$width.png").toFile(),
                    )
                }
            }
        } finally {
            com.intellij.openapi.util.Disposer.dispose(pane)
        }
    }

    fun testRapidSelectionsAndDisposalCannotRestoreAnOldPreview() {
        setup(
            manifest(
                node("first").apply { addProperty("compiled_code", "select 1") },
                node("second").apply { addProperty("compiled_code", "select 2") },
            ),
        )
        val first = myFixture.addFileToProject("repo/warehouse/models/first.sql", "select 1")
        val second = myFixture.addFileToProject("repo/warehouse/models/second.sql", "select 2")
        val panel = DbtArtifactPanel(project)
        try {
            repeat(10) {
                panel.load(first.virtualFile)
                panel.load(second.virtualFile)
            }
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
                "Latest selection wins",
                { panel.previewText == "select 2" },
                10,
            )
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
        panel.load(first.virtualFile) // A queued editor event after disposal must be harmless.
    }
}
