package dev.dbt.intellij.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
@State(name = "DbtLensSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DbtSettings : PersistentStateComponent<DbtSettings.State> {
    data class State(var preserveNativeSql: Boolean = false)
    private var value = State()
    override fun getState() = value
    override fun loadState(state: State) { value = state }
    var preserveNativeSql: Boolean
        get() = value.preserveNativeSql
        set(value) { this.value.preserveNativeSql = value }
}

class DbtSettingsConfigurable(private val project: Project) : Configurable {
    private var native: JCheckBox? = null
    override fun getDisplayName() = "dbt Lens"
    override fun createComponent() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        native = JCheckBox("Keep the native SQL editor in dbt projects (requires Database Tools)").also { add(it) }
        add(JLabel("Uses JetBrains dialect settings; dbt Lens Jinja features in .sql files are disabled in this mode."))
        add(JLabel("Restart the IDE after changing this option to refresh existing SQL editors."))
        reset()
    }
    override fun isModified() = native?.isSelected != project.getService(DbtSettings::class.java).preserveNativeSql
    override fun apply() { project.getService(DbtSettings::class.java).preserveNativeSql = native?.isSelected ?: false }
    override fun reset() { native?.isSelected = project.getService(DbtSettings::class.java).preserveNativeSql }
    override fun disposeUIResources() { native = null }
}
