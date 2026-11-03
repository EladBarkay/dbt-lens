package dev.dbt.intellij.lang

import com.intellij.lang.ASTNode

/** Structural tag pairing uses only the current tree, including nested blocks, never indexes. */
object DbtBlocks {
    val CLOSING =
        mapOf(
            "macro" to "endmacro",
            "if" to "endif",
            "for" to "endfor",
            "set" to "endset",
            "call" to "endcall",
            "filter" to "endfilter",
            "block" to "endblock",
            "raw" to "endraw",
            "with" to "endwith",
            "docs" to "enddocs",
            "snapshot" to "endsnapshot",
            "test" to "endtest",
            "materialization" to "endmaterialization",
        )

    fun keyword(statement: ASTNode): ASTNode? =
        statement.getChildren(null).firstOrNull {
            it.elementType in setOf(DbtTypes.KEYWORD, DbtTypes.IDENTIFIER)
        }

    fun headers(block: ASTNode): List<ASTNode> =
        block.getChildren(null).filter { it.elementType == DbtTypes.STATEMENT }

    fun openingFor(closing: ASTNode): ASTNode? {
        val block = closing.treeParent ?: return null
        if (block.elementType !in setOf(DbtTypes.MACRO, DbtTypes.BLOCK)) return null
        val headers = headers(block)
        val opening = headers.firstOrNull() ?: return null
        if (opening === closing || headers.lastOrNull() !== closing) return null
        if (CLOSING[keyword(opening)?.text] != keyword(closing)?.text) return null
        if (
            opening.findChildByType(DbtTypes.STMT_CLOSE) == null ||
                closing.findChildByType(DbtTypes.STMT_CLOSE) == null
        )
            return null
        return opening
    }

    fun closingFor(opening: ASTNode): ASTNode? =
        opening.treeParent?.let { block ->
            headers(block).lastOrNull()?.takeIf { openingFor(it) === opening }
        }

    fun isStructuralHeader(statement: ASTNode): Boolean {
        val block = statement.treeParent ?: return false
        if (block.elementType !in setOf(DbtTypes.MACRO, DbtTypes.BLOCK)) return false
        return block.findChildByType(DbtTypes.STATEMENT) === statement ||
            keyword(statement)?.text in setOf("elif", "else")
    }
}
