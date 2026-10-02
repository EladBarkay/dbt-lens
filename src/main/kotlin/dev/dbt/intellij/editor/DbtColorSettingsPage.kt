package dev.dbt.intellij.editor

import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import dev.dbt.intellij.lang.DbtFileType

class DbtColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = "dbt Lens"
    override fun getIcon() = DbtFileType.INSTANCE.icon
    override fun getHighlighter() = DbtSyntaxHighlighter()
    override fun getDemoText() = """
        {% macro <decl>example</decl>(<var>rows</var>) %}
          {# Macro documentation #}
          {% set <var>value</var> = <global>graph</global>.nodes %}
          {{ <call>example</call>(<var>rows</var>) }}
          select 100.0 from {{ <global>ref</global>('orders') }}
        {% endmacro %}
    """.trimIndent()
    override fun getAdditionalHighlightingTagToDescriptorMap() = mapOf("decl" to DbtAnnotator.DECLARATION,
        "call" to DbtAnnotator.CALL, "var" to DbtAnnotator.VARIABLE, "global" to DbtAnnotator.GLOBAL)
    override fun getAttributeDescriptors() = arrayOf(
        AttributesDescriptor("Macro declarations", DbtAnnotator.DECLARATION),
        AttributesDescriptor("Function calls", DbtAnnotator.CALL),
        AttributesDescriptor("Variables and parameters", DbtAnnotator.VARIABLE),
        AttributesDescriptor("dbt context globals", DbtAnnotator.GLOBAL)
    ) + DbtSyntaxHighlighter.KEYS.values.distinct().map { AttributesDescriptor(it.externalName.removePrefix("DBT_").replace('_', ' '), it) }
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
}
