package dev.dbt.intellij.artifacts

/** Layout of the DBT Lens tool window, in pixels unless noted. */
internal object ArtifactLayout {
    const val COMMAND_OUTPUT_TAB = 3
    const val BUTTON_GAP = 4
    const val PARAGRAPH_SPACE_BEFORE = 6
    const val PARAGRAPH_SPACE_AFTER = 8
    const val PANE_PADDING = 12

    /** Width of the documentation column-name cell, in percent, with and without a type column. */
    const val NAME_WIDTH_WITH_TYPES = 30
    const val NAME_WIDTH = 35

    /** Lineage graph geometry. */
    object Graph {
        const val MARGIN = 24
        const val TOP = 40
        const val VERTICAL_PADDING = 60
        const val COLUMN_WIDTH = 280
        const val ROW_HEIGHT = 76
        const val BOX_WIDTH = 240
        const val BOX_HEIGHT = 56
        const val BOX_ARC = 8
        const val MIN_WIDTH = 300
        const val MIN_HEIGHT = 160
        const val ARROW_LENGTH = 6
        const val ARROW_SPREAD = 4
        const val CAPTION_BASELINE = 22
        const val TEXT_INSET = 10
        const val NAME_BASELINE = 22
        const val DETAIL_BASELINE = 42
        const val NAME_CHARS = 30
        const val DETAIL_CHARS = 36
    }
}
