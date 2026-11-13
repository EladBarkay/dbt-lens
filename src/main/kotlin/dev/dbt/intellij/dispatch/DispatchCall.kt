package dev.dbt.intellij.dispatch

import com.intellij.psi.PsiElement
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.DbtVariable
import dev.dbt.intellij.psi.LocalMacros

/** An opening quote, at least one character and a closing quote. */
private const val MIN_QUOTED_NAME_LENGTH = 3

/** Literal calls only, including keyword arguments. No adapter or project code is executed. */
data class DispatchCall(val name: String, val namespace: String?) {
    companion object {
        fun from(call: PsiElement): DispatchCall? {
            if (
                call.node?.elementType != T.CALL || call.node.lastChildNode?.elementType != T.RPAREN
            )
                return null
            val access = call.firstChild.takeIf { it.node?.elementType == T.ACCESS } ?: return null
            if (access.lastChild.text != "dispatch") return null
            val adapter = access.firstChild as? DbtVariable ?: return null
            if (adapter.text != "adapter" || LocalMacros.isShadowed(adapter, "adapter")) return null
            val args = linkedMapOf<String, String?>()
            var positional = 0
            var seenKeyword = false
            for (argument in call.children.filter { it.node?.elementType == T.ARGUMENT }) {
                if (argument.node.getChildren(null).any { it.text in setOf("*", "**") }) return null
                val keyword = argument.node.findChildByType(T.ARGUMENT_NAME)?.text
                if (keyword != null) seenKeyword = true else if (seenKeyword) return null
                val key =
                    keyword
                        ?: when (positional++) {
                            0 -> "macro_name"
                            1 -> "macro_namespace"
                            else -> return null
                        }
                if (key !in setOf("macro_name", "macro_namespace") || args.containsKey(key))
                    return null
                val value =
                    argument.children.lastOrNull { it.node?.elementType != T.ARGUMENT_NAME }
                        ?: return null
                if (key == "macro_namespace" && value.text in setOf("none", "None"))
                    args[key] = null
                else args[key] = literal(value) ?: return null
            }
            val name = args["macro_name"] ?: return null
            return DispatchCall(name, args["macro_namespace"])
        }

        private fun literal(value: PsiElement): String? {
            if (
                value.node?.elementType != T.VALUE ||
                    value.node.firstChildNode?.elementType != T.STRING ||
                    value.children.isNotEmpty()
            )
                return null
            val text = value.text
            if (text.length < MIN_QUOTED_NAME_LENGTH || text.last() != text.first()) return null
            return text.drop(1).dropLast(1).takeIf { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) }
        }
    }
}

data class DispatchImplementation(val adapter: String, val name: String) {
    companion object {
        fun from(name: String?): DispatchImplementation? {
            if (name == null || "__" !in name) return null
            val adapter = name.substringBefore("__")
            val base = name.substringAfter("__")
            return if (adapter.isNotBlank() && base.isNotBlank())
                DispatchImplementation(adapter, base)
            else null
        }
    }
}
