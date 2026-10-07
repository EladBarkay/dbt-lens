package dev.dbt.intellij.artifacts

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.ExtendableHTMLViewFactory

/** Use the IDE's HTML renderer so fonts, wrapping, scaling and theme changes stay native. */
internal class ArtifactDocumentationPane : JBHtmlPane(
    JBHtmlPaneStyleConfiguration {
        spaceBeforeParagraph = 6
        spaceAfterParagraph = 8
        enableInlineCodeBackground = false
    },
    JBHtmlPaneConfiguration {
        extensions(ExtendableHTMLViewFactory.Extensions.WORD_WRAP)
        customStyleSheet("""
            body { margin: 0; }
            h3 { margin-top: 16px; margin-bottom: 6px; font-size: 100%; font-weight: bold; }
            table { width: 100%; border-spacing: 0; }
            th { text-align: left; font-weight: bold; padding: 4px 12px 4px 0; }
            td { vertical-align: top; padding: 4px 12px 4px 0; }
            ul { margin-top: 4px; margin-bottom: 10px; }
            li { margin-top: 3px; margin-bottom: 3px; }
            .bottom { margin-top: 16px; font-size: 90%; }
        """.trimIndent())
    }
) {
    init {
        isEditable = false
        font = UIUtil.getLabelFont().deriveFont(java.awt.Font.PLAIN)
        background = JBColor.namedColor("ToolWindow.background", UIUtil.getPanelBackground())
        foreground = JBColor.namedColor("Label.foreground", UIUtil.getLabelForeground())
        border = JBUI.Borders.empty(12)
    }
}
