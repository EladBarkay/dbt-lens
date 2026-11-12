package dev.dbt.intellij.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

@Service(Service.Level.PROJECT)
@State(name = "DbtLensSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DbtSettings : PersistentStateComponent<DbtSettings.State> {
    data class State(
        var preserveNativeSql: Boolean = false,
        var executable: String = "",
        var profilesDirectory: String = "",
        var profile: String = "",
        var target: String = "",
        var environmentFile: String = "",
    )

    private var value = State()

    override fun getState() = value

    override fun loadState(state: State) {
        value = state
    }

    var preserveNativeSql: Boolean
        get() = value.preserveNativeSql
        set(value) {
            this.value.preserveNativeSql = value
        }
}

class DbtSettingsConfigurable(private val project: Project) : Configurable {
    private var native: JCheckBox? = null
    private var executable: TextFieldWithBrowseButton? = null
    private var profiles: TextFieldWithBrowseButton? = null
    private var environment: TextFieldWithBrowseButton? = null
    private var profile: JTextField? = null
    private var target: JTextField? = null

    override fun getDisplayName() = "DBT Lens"

    override fun createComponent() =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            fun control(component: javax.swing.JComponent) {
                component.alignmentX = 0f
                component.maximumSize =
                    java.awt.Dimension(Int.MAX_VALUE, component.preferredSize.height)
                add(component)
            }
            native =
                JCheckBox("Keep the native SQL editor in dbt projects (requires Database Tools)")
                    .also { add(it) }
            add(
                JLabel(
                    "Uses JetBrains dialect settings; DBT Lens Jinja features in .sql files are disabled in this mode.",
                ),
            )
            add(
                JLabel(
                    "Restart the IDE after changing this option to refresh existing SQL editors.",
                ),
            )
            add(JLabel("dbt commands"))
            fun path(label: String, directory: Boolean = false): TextFieldWithBrowseButton {
                add(JLabel(label))
                return TextFieldWithBrowseButton().also {
                    it.addBrowseFolderListener(
                        project,
                        if (directory) FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        else FileChooserDescriptor(true, false, false, false, false, false),
                    )
                    control(it)
                }
            }
            executable = path("dbt executable (blank: project virtualenv, then PATH)")
            profiles = path("Profiles directory (optional)", true)
            add(JLabel("Profile override (blank: use dbt_project.yml)"))
            profile = JTextField().also(::control)
            add(JLabel("Target (blank: use profile default)"))
            target = JTextField().also(::control)
            environment = path("Environment file (optional, literal NAME=value entries)")
            add(
                JLabel(
                    "Commands inherit the IDE environment. Environment values stay in the selected file.",
                ),
            )
            add(
                JLabel(
                    "Compile may query the selected warehouse. Commands run only when you click an action.",
                ),
            )
            reset()
        }

    private fun edited() =
        DbtSettings.State(
            native?.isSelected ?: false,
            executable?.text?.trim().orEmpty(),
            profiles?.text?.trim().orEmpty(),
            profile?.text?.trim().orEmpty(),
            target?.text?.trim().orEmpty(),
            environment?.text?.trim().orEmpty(),
        )

    override fun isModified() = edited() != project.getService(DbtSettings::class.java).state

    override fun apply() {
        project.getService(DbtSettings::class.java).loadState(edited())
    }

    override fun reset() {
        val state = project.getService(DbtSettings::class.java).state
        native?.isSelected = state.preserveNativeSql
        executable?.text = state.executable
        profiles?.text = state.profilesDirectory
        profile?.text = state.profile
        target?.text = state.target
        environment?.text = state.environmentFile
    }

    override fun disposeUIResources() {
        native = null
        executable = null
        profiles = null
        profile = null
        target = null
        environment = null
    }
}
