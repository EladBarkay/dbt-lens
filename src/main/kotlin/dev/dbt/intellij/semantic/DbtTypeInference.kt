package dev.dbt.intellij.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.dbt.intellij.lang.DbtTypes as T
import dev.dbt.intellij.psi.*

object DbtTypeInference {
    fun symbol(element: PsiElement): DbtSymbol? = when {
        element is DbtVariable && DbtCallables.use(element) == DbtCallables.Use.FILTER -> DbtSymbols.FILTERS[element.text]
        element is DbtVariable && DbtCallables.use(element) == DbtCallables.Use.TEST -> DbtSymbols.TESTS[element.text]
        element is DbtVariable && DbtCallables.memberAccess(element) != null -> {
            val receiver = element.parent.firstChild
            DbtSymbols.members(of(receiver)).firstOrNull { it.name == element.text }
        }
        element is DbtVariable && LocalVariables.candidates(element).isEmpty() -> DbtSymbols.CONTEXT[element.text]
            ?.takeUnless { element.reference?.resolve() is DbtMacro }
        else -> null
    }
    fun contextual(element: PsiElement): Map<String, DbtSymbol> {
        val result = linkedMapOf<String, DbtSymbol>()
        LocalVariables.loop(element)?.let { result["loop"] = DbtSymbol("loop", type = DbtType("loop")) }
        if (PsiTreeUtil.getParentOfType(element, DbtMacro::class.java) != null) {
            result["kwargs"] = DbtSymbol("kwargs", type = DbtType.DICT)
            result["varargs"] = DbtSymbol("varargs", type = DbtType.LIST)
            result["caller"] = DbtSymbol("caller", "*args, **kwargs", DbtType.UNKNOWN)
        }
        return result
    }
    fun of(element: PsiElement?, depth: Int = 0, visited: Set<PsiElement> = emptySet()): DbtType {
        if (element == null || depth > 20 || element in visited) return DbtType.UNKNOWN
        val seen = visited + element
        fun infer(child: PsiElement?) = of(child, depth + 1, seen)
        val children = element.children.toList()
        return when (element.node.elementType) {
            T.PARAMETER_NAME, T.BINDING_NAME -> {
                val header = element.parent
                val kind = LocalVariables.keyword(header)
                val values = header.children.filter { it.textOffset > element.textOffset }
                    .takeWhile { it !is DbtBinding }
                val initializer = (if (kind == "set") header.children.toList() else values).firstOrNull { expression(it) }
                when {
                    kind == "for" -> {
                        val iterable = header.children.firstOrNull { expression(it) }
                        val item = infer(iterable).let { it.item ?: if (it.name in setOf("dict", "string")) DbtType.STRING else DbtType.UNKNOWN }
                        val targets = header.children.filterIsInstance<DbtBinding>()
                        if (targets.size > 1) item.fields[targets.indexOf(element).toString()] ?: DbtType.UNKNOWN else item
                    }
                    kind == "set" && header.node.getChildren(null).none { it.text == "=" } -> DbtType.STRING
                    kind == "set" && header.children.filterIsInstance<DbtBinding>().size > 1 -> {
                        val targets = header.children.filterIsInstance<DbtBinding>()
                        infer(initializer).fields[targets.indexOf(element).toString()] ?: DbtType.UNKNOWN
                    }
                    else -> infer(initializer)
                }
            }
            T.VARIABLE -> {
                val access = (element as? DbtVariable)?.let(DbtCallables::memberAccess)
                if (access != null) infer(access)
                else {
                    val bindings = LocalVariables.candidates(element)
                    when {
                        bindings.isNotEmpty() -> common(bindings.map(::infer))
                        contextual(element).containsKey(element.text) -> contextual(element).getValue(element.text).type
                        DbtSymbols.CONTEXT.containsKey(element.text) -> symbol(element)?.type ?: DbtType.UNKNOWN
                        element.reference?.resolve() is DbtMacro -> DbtType("macro")
                        else -> DbtType.UNKNOWN
                    }
                }
            }
            T.VALUE -> when (element.node.firstChildNode?.elementType) {
                T.STRING -> DbtType.STRING
                T.NUMBER -> DbtType.NUMBER
                T.KEYWORD -> if (element.text.lowercase() in setOf("true", "false")) DbtType.BOOL else DbtType.UNKNOWN
                T.LBRACE -> {
                    val pairs = children.chunked(2).filter { it.size == 2 }
                    val fields = pairs.mapNotNull { pair -> literal(pair[0])?.let { it to infer(pair[1]) } }.toMap()
                    DbtType("dict", common(pairs.map { infer(it[1]) }), fields)
                }
                T.LBRACKET -> DbtType("list", common(children.map(::infer)))
                T.LPAREN -> if (element.node.findChildByType(T.COMMA) == null) infer(children.firstOrNull())
                    else DbtType("tuple", fields = children.mapIndexed { i, child -> i.toString() to infer(child) }.toMap())
                else -> DbtType.UNKNOWN
            }
            T.ACCESS -> {
                val receiver = infer(children.firstOrNull())
                if (element.node.findChildByType(T.DOT) != null) DbtSymbols.members(receiver).firstOrNull { it.name == children.lastOrNull()?.text }?.type ?: DbtType.UNKNOWN
                else if (element.node.findChildByType(T.COLON) != null) receiver
                else {
                    val key = children.getOrNull(1)?.let(::literal)
                    receiver.fields[key] ?: receiver.item ?: if (receiver.name == "string") DbtType.STRING else DbtType.UNKNOWN
                }
            }
            T.CALL -> {
                val callee = children.firstOrNull()
                val variable = if (callee is DbtVariable) callee else callee?.lastChild as? DbtVariable
                val known = variable?.let(::symbol)
                val receiver = callee?.takeIf { it.node.elementType == T.ACCESS }?.firstChild?.let(::infer)
                val args = children.filter { it.node.elementType == T.ARGUMENT }
                when {
                    callee?.text in setOf("dict", "namespace") && known != null -> DbtType(callee!!.text, fields = args.mapNotNull { arg ->
                        arg.children.firstOrNull { it.node.elementType == T.ARGUMENT_NAME }?.let { name -> name.text to infer(arg.children.lastOrNull()) }
                    }.toMap())
                    callee?.text == "return" && known != null -> infer(args.firstOrNull()?.children?.lastOrNull())
                    callee?.text == "api.Column" && infer(callee).name == "api.Column" -> DbtType.COLUMN
                    receiver != null && receiver.name == "dict" && variable?.text == "get" -> {
                        val key = args.firstOrNull()?.children?.lastOrNull()?.let(::literal)
                        receiver.fields[key] ?: receiver.item ?: DbtType.UNKNOWN
                    }
                    receiver != null && receiver.name == "dict" && variable?.text in setOf("keys", "values", "items") -> {
                        val value = receiver.item ?: common(receiver.fields.values.toList())
                        val item = when (variable?.text) {
                            "keys" -> DbtType.STRING
                            "items" -> DbtType("tuple", fields = mapOf("0" to DbtType.STRING, "1" to value))
                            else -> value
                        }
                        DbtType("iterable", item)
                    }
                    receiver != null && receiver.name == "list" && variable?.text == "pop" -> receiver.item ?: DbtType.UNKNOWN
                    else -> known?.type ?: DbtType.UNKNOWN
                }
            }
            T.BINARY -> {
                val operator = element.node.getChildren(null).firstOrNull { it.text in setOf("|", "is", "~", "+", "-", "*", "/", "//", "%", "**") }?.text
                val input = infer(children.firstOrNull())
                when (operator) {
                    "~" -> DbtType.STRING
                    "is" -> DbtType.BOOL
                    "|" -> {
                        val name = children.lastOrNull()?.let { if (it.node.elementType == T.CALL) it.firstChild?.text else it.text }
                        when (name) {
                            "list", "sort", "reverse", "batch", "slice" -> DbtType("list", input.item ?: if (input.name == "string") DbtType.STRING else DbtType.UNKNOWN)
                            "select", "reject", "selectattr", "rejectattr", "unique" -> DbtType("iterable", input.item)
                            "map" -> DbtType("iterable")
                            "first", "last", "random" -> input.item ?: DbtType.UNKNOWN
                            "int", "float", "length", "count", "sum", "round", "as_number" -> DbtType.NUMBER
                            "as_bool", "is_list" -> DbtType.BOOL
                            "string", "as_text", "join", "upper", "lower", "trim", "replace", "capitalize", "title", "format", "tojson" -> DbtType.STRING
                            "default", "d" -> input
                            else -> DbtType.UNKNOWN
                        }
                    }
                    else -> common(children.map(::infer))
                }
            }
            T.CONDITIONAL -> common(listOf(infer(children.firstOrNull()), infer(children.lastOrNull())))
            T.UNARY -> if (element.text.startsWith("not")) DbtType.BOOL else infer(children.firstOrNull())
            else -> DbtType.UNKNOWN
        }
    }
    fun literal(element: PsiElement): String? = element.text.takeIf { element.node.elementType == T.VALUE && it.length >= 2 && it.first() in "'\"" && it.last() == it.first() }?.drop(1)?.dropLast(1)
    private fun expression(element: PsiElement) = element.node.elementType in setOf(T.VALUE, T.VARIABLE, T.CALL, T.ACCESS, T.BINARY, T.CONDITIONAL, T.UNARY)
    private fun common(types: List<DbtType>): DbtType = types.distinct().singleOrNull() ?: DbtType.UNKNOWN
}
