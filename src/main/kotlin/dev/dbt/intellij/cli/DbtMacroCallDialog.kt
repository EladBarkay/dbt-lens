package dev.dbt.intellij.cli

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

internal class DbtMacroCallDialog(project: Project, calls: Map<String, String>) :
    DialogWrapper(project) {
    private val choices = JComboBox(calls.keys.toTypedArray())
    private val call = JTextArea(calls.values.first(), 6, 50)
    val invocation
        get() = call.text.trim()

    val macroName
        get() = choices.selectedItem as String

    init {
        title = "Compile macro call"
        setOKButtonText("Compile")
        choices.addActionListener { call.text = calls[macroName].orEmpty() }
        init()
    }

    override fun createCenterPanel(): JComponent =
        JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(230))
            add(choices, BorderLayout.NORTH)
            add(JBScrollPane(call), BorderLayout.CENTER)
            add(
                JLabel(
                    "Supply the invocation and arguments. Compilation may query the selected warehouse."
                ),
                BorderLayout.SOUTH,
            )
        }

    override fun doValidate() =
        if (invocation.isBlank() || invocation.length > 65_536)
            ValidationInfo("Enter a macro call smaller than 64 KB", call)
        else null

    override fun getPreferredFocusedComponent(): JComponent = call
}
