package dev.dbt.intellij.resources

import com.intellij.psi.PsiElement
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtVariable
import dev.dbt.intellij.psi.LocalVariables

/** Only complete, literal built-in calls are resolved. Dynamic expressions remain unknown. */
data class ResourceCall(val function: String, val name: String, val packageName: String? = null,
    val source: String? = null, val version: String? = null) {
    companion object {
        fun from(call: PsiElement): ResourceCall? {
            if (call.node.elementType != T.CALL || call.node.lastChildNode?.elementType != T.RPAREN) return null
            val callee = call.firstChild as? DbtVariable ?: return null
            val function = callee.text.takeIf { it in setOf("ref", "source", "metric") } ?: return null
            if (LocalVariables.declaration(callee) != null) return null
            val positional = mutableListOf<String>()
            val keyword = linkedMapOf<String, String>()
            for (arg in call.children.filter { it.node.elementType == T.ARGUMENT }) {
                val key = arg.node.findChildByType(T.ARGUMENT_NAME)?.text
                val value = arg.children.lastOrNull { it.node.elementType != T.ARGUMENT_NAME } ?: return null
                if (value.node.elementType != T.VALUE || value.children.isNotEmpty()) return null
                val raw = value.text
                val literal = literal(raw) ?: raw.takeIf { key in setOf("version", "v") && it.matches(Regex("[0-9]+")) } ?: return null
                if (key == null) {
                    if (keyword.isNotEmpty()) return null
                    positional += literal
                } else if (keyword.put(key, literal) != null) return null
            }
            return when (function) {
                "ref" -> {
                    if (keyword.keys.any { it !in setOf("v", "version") } || keyword.size > 1 || positional.size !in 1..2) return null
                    ResourceCall(function, positional.last(), positional.firstOrNull().takeIf { positional.size == 2 }, version = keyword.values.firstOrNull())
                }
                "source" -> if (keyword.isEmpty() && positional.size == 2) ResourceCall(function, positional[1], source = positional[0]) else null
                else -> if (keyword.isEmpty() && positional.size == 1) ResourceCall(function, positional.single()) else null
            }
        }
        private fun literal(raw: String): String? {
            if (raw.length < 2 || raw.first() !in "'\"" || raw.last() != raw.first()) return null
            return raw.substring(1, raw.length - 1).takeIf { it.isNotEmpty() && '\\' !in it && "{{" !in it }
        }
    }
}
