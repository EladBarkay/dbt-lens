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
        {# Macro documentation #}
        {% macro <decl>example</decl>(<var>rows</var>) %}
          {% set <var>value</var> = <global>graph</global>.nodes %}
          {{ <call>example</call>(<arg>rows</arg>=<var>rows</var>) }}
          select 100.0 from {{ <call>ref</call>('orders') }}
        {% endmacro %}
    """.trimIndent()
    override fun getAdditionalHighlightingTagToDescriptorMap() = mapOf("decl" to DbtAnnotator.DECLARATION,
        "call" to DbtAnnotator.CALL, "var" to DbtAnnotator.VARIABLE, "global" to DbtAnnotator.GLOBAL, "arg" to DbtAnnotator.ARGUMENT)
    override fun getAttributeDescriptors() = arrayOf(
        AttributesDescriptor("Macro declarations", DbtAnnotator.DECLARATION),
        AttributesDescriptor("Function calls", DbtAnnotator.CALL),
        AttributesDescriptor("Variables and parameters", DbtAnnotator.VARIABLE),
        AttributesDescriptor("dbt context globals", DbtAnnotator.GLOBAL),
        AttributesDescriptor("Keyword argument names", DbtAnnotator.ARGUMENT)
    ) + DbtSyntaxHighlighter.KEYS.values.distinct().map { AttributesDescriptor(it.externalName.removePrefix("DBT_").replace('_', ' '), it) }
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
}
