package dev.dbt.intellij.artifacts

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import dev.dbt.intellij.cli.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class DbtArtifactToolWindow : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DbtArtifactPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** One shared artifact snapshot drives preview, docs and lineage; CLI actions are explicit. */
class DbtArtifactPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = project.getService(DbtArtifacts::class.java)
    private val cli = project.getService(DbtCli::class.java)
    private val status = JLabel("Open a file inside a dbt project")
    private val nodes = JComboBox<ArtifactNode>()
    private val direction = JComboBox(LineageDirection.entries.toTypedArray())
    private val depth = JSpinner(SpinnerNumberModel(1, 1, 3, 1))
    private val docs = ArtifactDocumentationPane()
    private val editor: Editor = EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(""), project,
        FileTypeManager.getInstance().getFileTypeByExtension("sql"), true)
    private val graph = LineageGraph(::navigate)
    private val tabs = JTabbedPane()
    private val commandStatus = JLabel()
    private val output = JTextArea().apply {
        isEditable = false; lineWrap = true
        font = Font(Font.MONOSPACED, Font.PLAIN, com.intellij.util.ui.UIUtil.getLabelFont().size)
    }
    private val generate = JButton("Generate manifest").apply { addActionListener { runCommand(DbtCommandKind.PARSE) } }
    private val compile = JButton("Compile current").apply { addActionListener { runCommand(DbtCommandKind.COMPILE) } }
    private val compileProject = JButton("Compile project").apply { addActionListener { runCommand(DbtCommandKind.PROJECT) } }
    private val cancel = JButton("Cancel").apply { addActionListener { cli.cancel() } }
    private var handledCommand = 0L
    private var file: VirtualFile? = null
    private var request: ArtifactRequest? = null
    private var snapshot = ArtifactSnapshot()
    private var generation = 0
    private var rendering = 0
    private var disposed = false
    private var filling = false
    internal val previewText get() = editor.document.text
    internal val stateText get() = status.text
    internal val resourceCount get() = nodes.itemCount
    internal fun selectResource(index: Int) { nodes.selectedIndex = index }

    init {
        com.intellij.openapi.util.Disposer.register(this, docs)
        nodes.renderer = DefaultListCellRenderer().let { base -> ListCellRenderer { list, value, index, selected, focus ->
            base.getListCellRendererComponent(list, value?.label.orEmpty(), index, selected, focus)
        } }
        direction.renderer = DefaultListCellRenderer().let { base -> ListCellRenderer { list, value, index, selected, focus ->
            base.getListCellRendererComponent(list, value?.title.orEmpty(), index, selected, focus)
        } }
        val toolbar = JPanel(BorderLayout()).apply {
            add(nodes, BorderLayout.CENTER)
            add(JButton("Refresh saved output").apply { addActionListener { load(file, true) } }, BorderLayout.EAST)
        }
        val commands = JPanel(GridLayout(0, 2, 4, 4)).apply {
            add(generate); add(compile); add(compileProject); add(cancel)
            add(JButton("dbt settings").apply { addActionListener {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, "dbt Lens")
            } })
        }
        add(JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(commands, BorderLayout.CENTER)
            add(JPanel(java.awt.GridLayout(0, 1)).apply { add(status); add(commandStatus) }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        tabs.addTab("Compiled SQL", editor.component)
        tabs.addTab("Documentation", JBScrollPane(docs))
        tabs.addTab("Lineage", JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(direction); add(JLabel("Depth")); add(depth)
                add(JLabel("Click a node to open its definition"))
            }, BorderLayout.NORTH)
            add(JBScrollPane(graph), BorderLayout.CENTER)
        })
        tabs.addTab("Command output", JBScrollPane(output))
        add(tabs, BorderLayout.CENTER)
        nodes.addActionListener { if (!filling) render() }
        direction.addActionListener { renderGraph() }
        depth.addChangeListener { renderGraph() }
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) { load(event.newFile) }
        })
        project.messageBus.connect(this).subscribe(DbtCliListener.TOPIC, DbtCliListener(::commandChanged))
        commandChanged(cli.state)
        load(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
    }
    fun load(active: VirtualFile?, force: Boolean = false, preferred: String? = null) {
        val wanted = preferred ?: (nodes.selectedItem as? ArtifactNode)?.id
        file = active
        val ticket = ++generation
        ++rendering
        request = null
        updateCommands()
        snapshot = ArtifactSnapshot()
        filling = true; nodes.removeAllItems(); filling = false
        setSql(""); docs.text = ""; graph.show(snapshot, null, 1, LineageDirection.BOTH)
        if (active == null) { status.text = "Open a file inside a dbt project"; return }
        status.text = "Reading saved dbt output…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val context = ReadAction.compute<ArtifactRequest?, RuntimeException> { PsiManager.getInstance(project).findFile(active)?.let(service::request) }
            val result = context?.let { service.load(it, force) }
            result?.children // Build the reverse index on the worker, before drawing the graph.
            val matches = if (result?.valid == true) ReadAction.compute<List<ArtifactNode>, RuntimeException> {
                PsiManager.getInstance(project).findFile(active)?.let { service.forFile(it, result) }.orEmpty()
            } else emptyList()
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed || ticket != generation) return@invokeLater
                request = context; snapshot = result ?: ArtifactSnapshot()
                filling = true; matches.forEach(nodes::addItem)
                matches.indexOfFirst { it.id == wanted }.takeIf { it >= 0 }?.let { nodes.selectedIndex = it }
                filling = false
                updateCommands()
                if (refreshCompletedCommand(cli.state)) return@invokeLater
                when {
                    context == null -> status.text = "This file is outside a dbt project"
                    result?.valid != true -> status.text = result?.messages?.joinToString("; ").orEmpty()
                    matches.isEmpty() -> status.text = "This file has no resource in the saved manifest. Refresh output with dbt."
                    else -> render()
                }
            }
        }
    }
    private fun render() {
        val node = nodes.selectedItem as? ArtifactNode ?: return
        val context = request ?: return
        val active = file ?: return
        val saved = snapshot
        val ticket = ++rendering
        status.text = "Saved dbt output${saved.generatedAt?.let { " · $it" }.orEmpty()}"
        renderGraph()
        ApplicationManager.getApplication().executeOnPooledThread {
            val code = runCatching { ArtifactReader.compiled(context, node) }.getOrNull()
            val info = ReadAction.compute<Pair<String, Boolean>, RuntimeException> {
                val psi = PsiManager.getInstance(project).findFile(active)
                if (psi == null) "" to false else ArtifactDocumentation.body(psi, node, saved, showStaleness = false) to service.changed(psi, node)
            }
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed || ticket != rendering) return@invokeLater
                docs.text = info.first; docs.caretPosition = 0
                val inline = cli.state.takeIf { it.phase == DbtCliPhase.SUCCEEDED && it.command?.kind == DbtCommandKind.INLINE &&
                    it.command.sourcePath == active.path && it.command.resourceId == node.id }?.compiled
                setSql(inline ?: code ?: if (node.kind == "macro") "-- A macro definition is not compiled SQL.\n-- Use Compile current to supply a macro call and arguments.\n-- Its documentation and dependencies are available in the other tabs."
                    else if (node.language == "python" || node.originalPath.endsWith(".py")) "-- Python models do not have a SQL preview.\n-- Their documentation and dependencies are available in the other tabs."
                    else "-- No saved compiled SQL for this resource.\n-- Use Compile current or Compile project to generate it.")
                status.text = (if (info.second) "Source changed; saved output may be outdated" else "Saved dbt output${saved.generatedAt?.let { " · $it" }.orEmpty()}") +
                    saved.messages.takeIf { it.isNotEmpty() }?.joinToString("; ", " · ").orEmpty()
            }
        }
    }
    private fun updateCommands() {
        val ready = request?.let { it.problem == null && it.target != null } == true && !cli.state.running
        generate.isEnabled = ready; compileProject.isEnabled = ready
        compile.isEnabled = ready && file?.extension.equals("sql", true)
        compile.toolTipText = "Compile the selected SQL resource or an explicit macro call; may query the warehouse"
        compileProject.toolTipText = "Compile this dbt project; may query the warehouse"
        cancel.isEnabled = cli.state.running
    }
    private fun commandChanged(state: DbtCliState) {
        if (disposed) return
        commandStatus.text = state.message
        commandStatus.toolTipText = state.command?.context?.root?.toString()
        if (output.text != state.output) { output.text = state.output; output.caretPosition = output.document.length }
        updateCommands()
        refreshCompletedCommand(state)
    }
    private fun refreshCompletedCommand(state: DbtCliState): Boolean {
        if (state.running || state.id == 0L || state.id == handledCommand) return false
        // A file switch can still be resolving its project when the command completes.
        if (state.phase == DbtCliPhase.SUCCEEDED && request == null) return false
        handledCommand = state.id
        if (state.phase == DbtCliPhase.SUCCEEDED && state.command?.context?.root == request?.root) {
            load(file, true, state.command?.resourceId)
            return true
        }
        return false
    }
    internal fun executeCommand(command: DbtCommand): Boolean = cli.start(command).also { started ->
        if (started) { updateCommands(); tabs.selectedIndex = 3 }
    }
    private fun runCommand(kind: DbtCommandKind) {
        val active = file ?: return
        val documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        val psi = PsiManager.getInstance(project).findFile(active) ?: return
        val root = service.request(psi)?.root ?: return
        documents.unsavedDocuments.filter { doc -> documents.getFile(doc)?.path?.let { java.nio.file.Path.of(it).startsWith(root) } == true }
            .forEach(documents::saveDocument)
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        val context = service.request(psi) ?: return // Saving configuration may change the output directory.
        try {
            val command = if (kind != DbtCommandKind.COMPILE) DbtCommand(kind, context) else {
                val selected = nodes.selectedItem as? ArtifactNode
                val declarations = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(psi, dev.dbt.intellij.psi.DbtMacro::class.java)
                val macroNames = when {
                    selected?.kind == "macro" -> listOf(selected.name) // Also works with the native SQL editor.
                    selected != null -> emptyList() // A model may contain local macro declarations.
                    else -> declarations.mapNotNull { it.name }
                }
                val namespace = dev.dbt.intellij.psi.ProjectMacros.namespace(psi)
                    ?: throw IllegalArgumentException("The dbt package needs a literal name in dbt_project.yml")
                if (macroNames.isNotEmpty()) {
                    val calls = macroNames.associateWith { name -> "{{ $namespace.$name() }}" }
                    if (calls.isEmpty()) return
                    val dialog = DbtMacroCallDialog(project, calls)
                    if (!dialog.showAndGet()) return
                    DbtCommand(DbtCommandKind.INLINE, context, invocation = dialog.invocation, sourcePath = active.path,
                        resourceId = "macro.$namespace.${dialog.macroName}")
                } else {
                    val ownRoot = dev.dbt.intellij.psi.ProjectMacros.root(psi) ?: return
                    val relative = selected?.originalPath ?: com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(active, ownRoot) ?: return
                    val selector = selected?.let(DbtCommandPlanner::selector) ?: if (ownRoot.path == root.toString())
                        DbtCommandPlanner.selector(namespace, relative) else throw IllegalArgumentException("Generate manifest before compiling an installed dependency resource")
                    DbtCommand(kind, context, selector = selector, sourcePath = active.path)
                }
            }
            if (!executeCommand(command)) commandStatus.text = "A dbt command is already running. Cancel it or wait for it to finish."
        } catch (e: IllegalArgumentException) { commandStatus.text = e.message.orEmpty() }
    }
    private fun renderGraph() = graph.show(snapshot, (nodes.selectedItem as? ArtifactNode)?.id, depth.value as Int,
        direction.selectedItem as LineageDirection)
    private fun setSql(code: String) {
        // A detached viewer document: refreshing it cannot alter source or generated files.
        com.intellij.openapi.application.WriteAction.run<RuntimeException> { editor.document.setText(code) }
    }
    private fun navigate(id: String) {
        val node = snapshot.nodes[id] ?: return
        val active = file ?: return
        val psi = PsiManager.getInstance(project).findFile(active) ?: return
        val target = service.file(psi, node) ?: return
        val targetPsi = PsiManager.getInstance(project).findFile(target)
        val resourceCall = when (node.kind) {
            "source" -> dev.dbt.intellij.resources.ResourceCall("source", node.name, node.packageName, node.sourceName)
            "metric" -> dev.dbt.intellij.resources.ResourceCall("metric", node.name, node.packageName)
            else -> null
        }
        val offset = if (node.kind == "macro" && targetPsi != null) dev.dbt.intellij.psi.LocalMacros.candidates(targetPsi, node.name).singleOrNull()?.textOffset ?: 0
            else resourceCall?.let { project.getService(dev.dbt.intellij.resources.DbtResources::class.java).targets(psi, it)
                .singleOrNull { definition -> definition.containingFile.virtualFile == target }?.textOffset } ?: 0
        OpenFileDescriptor(project, target, offset).navigate(true)
    }
    override fun dispose() { disposed = true; generation++; rendering++; EditorFactory.getInstance().releaseEditor(editor) }
}

private class LineageGraph(private val navigate: (String) -> Unit) : JPanel() {
    private var snapshot = ArtifactSnapshot()
    private var active: String? = null
    private var slice = LineageSlice(emptyMap(), emptySet(), false)
    private val boxes = linkedMapOf<String, Rectangle>()
    init {
        toolTipText = ""
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { boxes.entries.firstOrNull { it.value.contains(e.point) }?.let { navigate(it.key) } }
        })
    }
    fun show(data: ArtifactSnapshot, id: String?, depth: Int, direction: LineageDirection) {
        snapshot = data; active = id
        slice = id?.let { LineageSlice.build(data, it, depth, direction) } ?: LineageSlice(emptyMap(), emptySet(), false)
        boxes.clear()
        val layers = slice.layers.entries.groupBy { it.value }.toSortedMap()
        layers.values.forEachIndexed { col, nodes -> nodes.forEachIndexed { row, node -> boxes[node.key] = Rectangle(24 + col * 280, 40 + row * 76, 240, 56) } }
        preferredSize = Dimension((layers.size * 280 + 24).coerceAtLeast(300), ((layers.values.maxOfOrNull { it.size } ?: 0) * 76 + 60).coerceAtLeast(160))
        revalidate(); repaint()
    }
    override fun getToolTipText(event: MouseEvent): String? = boxes.entries.firstOrNull { it.value.contains(event.point) }?.let { snapshot.nodes[it.key]?.label }
    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = JBColor.GRAY
            slice.edges.forEach { (parent, child) ->
                val from = boxes[parent] ?: return@forEach; val to = boxes[child] ?: return@forEach
                val x = to.x; val y = to.y + to.height / 2
                g.drawLine(from.x + from.width, from.y + from.height / 2, x, y)
                g.drawLine(x, y, x - 6, y - 4); g.drawLine(x, y, x - 6, y + 4)
            }
            g.color = foreground
            g.drawString(if (slice.truncated) "Showing up to 80 resources; reduce depth to focus the graph" else "Saved artifact relationships · ${boxes.size} resources", 24, 22)
            boxes.forEach { (id, box) ->
                g.color = if (id == active) com.intellij.util.ui.UIUtil.getListSelectionBackground(true) else background
                g.fillRoundRect(box.x, box.y, box.width, box.height, 8, 8)
                g.color = JBColor.GRAY; g.drawRoundRect(box.x, box.y, box.width, box.height, 8, 8)
                g.color = if (id == active) com.intellij.util.ui.UIUtil.getListSelectionForeground(true) else foreground
                val node = snapshot.nodes[id] ?: return@forEach
                g.drawString(node.name.take(30), box.x + 10, box.y + 22)
                g.drawString("${node.packageName} · ${node.kind}".take(36), box.x + 10, box.y + 42)
            }
        } finally { g.dispose() }
    }
}
