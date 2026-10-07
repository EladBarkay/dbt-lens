package dev.dbt.intellij.psi

import com.intellij.psi.PsiElement
import dev.dbt.intellij.lang.DbtTypes as T

object DbtCallables {
    enum class Use {
        CALL,
        FILTER,
        TEST,
        VALUE,
    }

    fun memberAccess(element: DbtVariable): PsiElement? =
        element.parent.takeIf {
            it.node.elementType == T.ACCESS &&
                it.lastChild == element &&
                it.node.findChildByType(T.DOT) != null
        }

    fun use(element: DbtVariable): Use {
        val target = memberAccess(element) ?: element
        var callee: PsiElement = target
        while (
            callee.parent?.node?.elementType in setOf(T.CALL, T.ACCESS) &&
                callee.parent.firstChild == callee
        ) callee = callee.parent
        val binary = callee.parent
        if (binary?.node?.elementType == T.BINARY && binary.firstChild != callee) {
            val operator =
                binary.node.getChildren(null).firstOrNull { it.text in setOf("|", "is") }?.text
            if (operator == "|") return Use.FILTER
            if (operator == "is") return Use.TEST
        }
        return if (target.parent.node.elementType == T.CALL && target.parent.firstChild == target)
            Use.CALL
        else Use.VALUE
    }

    // Built-in filters have their own Jinja registry. Do not jump to an unrelated same-named macro.
    val BUILTIN_FILTERS =
        setOf(
            "abs",
            "attr",
            "batch",
            "capitalize",
            "center",
            "count",
            "d",
            "default",
            "dictsort",
            "e",
            "escape",
            "filesizeformat",
            "first",
            "float",
            "forceescape",
            "format",
            "groupby",
            "indent",
            "int",
            "items",
            "join",
            "last",
            "length",
            "list",
            "lower",
            "map",
            "max",
            "min",
            "pprint",
            "random",
            "reject",
            "rejectattr",
            "replace",
            "reverse",
            "round",
            "safe",
            "select",
            "selectattr",
            "slice",
            "sort",
            "string",
            "striptags",
            "sum",
            "title",
            "tojson",
            "trim",
            "truncate",
            "unique",
            "upper",
            "urlencode",
            "urlize",
            "wordcount",
            "wordwrap",
            "xmlattr",
            "as_bool",
            "as_native",
            "as_number",
            "as_text",
            "is_list",
        )
}
