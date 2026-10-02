package dev.dbt.intellij.semantic

/** Static editor hints, checked against Jinja, Python types and dbt Core 1.10.6 context APIs. Never inspects live Python objects or executes dbt. */
data class DbtType(val name: String, val item: DbtType? = null, val fields: Map<String, DbtType> = emptyMap()) {
    companion object {
        val UNKNOWN = DbtType("unknown"); val STRING = DbtType("string"); val NUMBER = DbtType("number")
        val BOOL = DbtType("boolean"); val DICT = DbtType("dict"); val LIST = DbtType("list")
        val RELATION = DbtType("Relation"); val COLUMN = DbtType("Column")
    }
}
data class DbtSymbol(val name: String, val signature: String? = null, val type: DbtType = DbtType.UNKNOWN,
    val description: String = "", val url: String = "https://docs.getdbt.com/reference/dbt-jinja-functions", val deprecated: Boolean = false) {
    val parameters: List<String> get() = signature?.substringAfter("/", signature)?.split(',')?.map { it.trim().substringBefore('=').trim() }
        ?.filter { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) }.orEmpty()
}
object DbtSymbols {
    private fun type(name: String) = DbtType(name)
    private fun f(name: String, signature: String, result: DbtType = DbtType.UNKNOWN, docs: String = "", url: String = "https://docs.getdbt.com/reference/dbt-jinja-functions/$name") = DbtSymbol(name, signature, result, docs, url)
    private fun p(name: String, result: DbtType, docs: String = "") = DbtSymbol(name, type = result, description = docs)
    private val strings = listOf(f("split", "sep=None, maxsplit=-1", DbtType("list", DbtType.STRING)),
        f("rsplit", "sep=None, maxsplit=-1", DbtType("list", DbtType.STRING)), f("splitlines", "keepends=false", DbtType("list", DbtType.STRING)),
        f("strip", "chars=None, /", DbtType.STRING), f("lstrip", "chars=None, /", DbtType.STRING), f("rstrip", "chars=None, /", DbtType.STRING),
        f("replace", "old, new, count=-1, /", DbtType.STRING), f("startswith", "prefix, start=0, end=None, /", DbtType.BOOL),
        f("endswith", "suffix, start=0, end=None, /", DbtType.BOOL), f("lower", "", DbtType.STRING), f("upper", "", DbtType.STRING),
        f("capitalize", "", DbtType.STRING), f("title", "", DbtType.STRING), f("join", "iterable, /", DbtType.STRING),
        f("format", "*args, **kwargs", DbtType.STRING), f("find", "sub, start=0, end=None, /", DbtType.NUMBER), f("count", "sub, start=0, end=None, /", DbtType.NUMBER))
    private val dictionaries = listOf(f("get", "key, default=None, /"), f("items", "", type("iterable")), f("keys", "", type("iterable")),
        f("values", "", type("iterable")), f("update", "other=(), /, **kwargs"), f("pop", "key, default=None, /"),
        f("setdefault", "key, default=None, /"), f("copy", "", DbtType.DICT), f("clear", ""))
    private val lists = listOf(f("append", "object, /"), f("extend", "iterable, /"), f("pop", "index=-1, /"), f("remove", "value, /"),
        f("insert", "index, object, /"), f("count", "value, /", DbtType.NUMBER), f("index", "value, start=0, stop=None, /", DbtType.NUMBER),
        f("sort", "*, key=None, reverse=false"), f("reverse", ""), f("copy", "", DbtType.LIST), f("clear", ""))
    private val relation = listOf(p("database", DbtType.STRING), p("schema", DbtType.STRING), p("identifier", DbtType.STRING), p("type", DbtType.STRING),
        p("is_table", DbtType.BOOL), p("is_view", DbtType.BOOL), p("is_cte", DbtType.BOOL),
        f("include", "database=true, schema=true, identifier=true", DbtType.RELATION),
        f("quote", "database=None, schema=None, identifier=None", DbtType.RELATION))
    private val columns = listOf(p("name", DbtType.STRING), p("column", DbtType.STRING), p("data_type", DbtType.STRING), p("dtype", DbtType.STRING),
        p("quoted", DbtType.STRING)) + listOf("is_string", "is_numeric", "is_number", "is_integer", "is_float").map { f(it, "", DbtType.BOOL) }
    val CONTEXT: Map<String, DbtSymbol> = (listOf(
        f("ref", "*model_names, version=None, v=None", DbtType.RELATION), f("source", "source_name, table_name, /", DbtType.RELATION), f("var", "var_name, default=None"),
        f("config", "**kwargs"), f("env_var", "var, default=None", DbtType.STRING), f("return", "data"), f("log", "msg, info=false"),
        f("print", "msg"), f("is_incremental", "", DbtType.BOOL), f("run_query", "sql", type("agate.Table | None")),
        f("statement", "name=None, fetch_result=false, auto_begin=true"), f("load_result", "name", DbtType("dict", fields = mapOf("response" to DbtType.DICT, "data" to DbtType.LIST, "table" to type("agate.Table")))),
        f("store_result", "name, response, agate_table=None"), f("tojson", "value, default=None, sort_keys=false", DbtType.STRING), f("toyaml", "value, default=None, sort_keys=false", DbtType.STRING),
        f("fromjson", "string, default=None"), f("fromyaml", "value, default=None"), f("local_md5", "value", DbtType.STRING),
        f("doc", "*names", DbtType.STRING), f("debug", ""), f("zip", "*args, default=None", type("iterable")), f("zip_strict", "*args", type("iterable")),
        f("set", "value, default=None", type("set")), f("set_strict", "value", type("set")),
        f("dict", "**kwargs", DbtType.DICT), f("namespace", "**kwargs", type("namespace")), f("range", "start, stop=None, step=1", DbtType("list", DbtType.NUMBER)),
        f("cycler", "*items", type("cycler")), f("joiner", "sep=', '", type("callable")),
        p("api", type("api"), "dbt classes: Relation and Column."), p("adapter", type("adapter")), p("exceptions", type("exceptions")), p("modules", type("modules")),
        p("builtins", type("builtins")), p("this", DbtType.RELATION), p("target", DbtType("dict", fields = listOf("name", "type", "schema", "database", "profile_name").associateWith { DbtType.STRING })),
        p("graph", DbtType("dict", fields = listOf("nodes", "sources", "exposures", "metrics", "groups").associateWith { DbtType.DICT })),
        p("model", DbtType.DICT), p("flags", type("flags")), p("execute", DbtType.BOOL), p("run_started_at", type("datetime")),
        p("selected_resources", DbtType("list", DbtType.STRING)),
        p("results", DbtType.LIST, "Available in on-run-end hooks."), p("schemas", DbtType.LIST, "Available in on-run-end hooks."),
        p("database_schemas", DbtType.LIST, "Available in on-run-end hooks.")
    ) + listOf("dbt_version", "invocation_id", "thread_id", "project_name", "database", "schema").map { p(it, DbtType.STRING) }).map { if (it.name in setOf("dict", "namespace", "range", "cycler", "joiner"))
        it.copy(url = "https://jinja.palletsprojects.com/en/stable/templates/#list-of-global-functions") else it }.associateBy { it.name }
    val FILTERS: Map<String, DbtSymbol> = dev.dbt.intellij.psi.DbtCallables.BUILTIN_FILTERS.associateWith { name ->
        val signature = when (name) {
            "map" -> "filter=None, attribute=None, default=None"; "selectattr", "rejectattr" -> "attribute, test=None, *args"
            "select", "reject" -> "test=None, *args"; "default", "d" -> "default_value='', boolean=false"
            "join" -> "d='', attribute=None"; "sort" -> "reverse=false, case_sensitive=false, attribute=None"
            "replace" -> "old, new, count=None"; "round" -> "precision=0, method='common'"
            "int" -> "default=0, base=10"; "float" -> "default=0.0"; "attr" -> "name"
            "groupby" -> "attribute, default=None, case_sensitive=false"; "batch", "slice" -> "size, fill_with=None"
            "unique" -> "case_sensitive=false, attribute=None"; "sum" -> "attribute=None, start=0"
            else -> null
        }
        DbtSymbol(name, signature, url = if (name.startsWith("as_")) "https://docs.getdbt.com/reference/dbt-jinja-functions/$name"
            else if (name == "is_list") "https://github.com/dbt-labs/dbt-common/blob/main/dbt_common/clients/jinja.py"
            else "https://jinja.palletsprojects.com/en/stable/templates/#list-of-builtin-filters", description = "Jinja/dbt filter: $name")
    }
    val TESTS = listOf("boolean", "callable", "defined", "undefined", "none", "mapping", "iterable", "sequence", "string", "number", "integer", "float",
        "odd", "even", "lower", "upper", "escaped", "true", "false", "filter", "test", "divisibleby", "sameas", "equalto", "eq", "ne", "ge", "gt", "le", "lt", "in").associateWith {
        DbtSymbol(it, if (it in setOf("divisibleby", "sameas", "equalto", "eq", "ne", "ge", "gt", "le", "lt", "in", "filter", "test")) "value" else null,
            DbtType.BOOL, "Jinja test: $it", "https://jinja.palletsprojects.com/en/stable/templates/#list-of-builtin-tests")
    }
    fun members(type: DbtType): List<DbtSymbol> {
        val methods = when (type.name) {
            "string" -> strings
            "dict" -> dictionaries
            "list" -> lists
            "Relation", "Relation | None" -> relation
            "Column" -> columns
            "api" -> listOf(p("Relation", DbtType("api.Relation"), "Use api.Relation.create(...) to construct a dbt Relation."), p("Column", DbtType("api.Column"), "dbt Column class; use api.Column(name, dtype) to construct a column."))
            "api.Relation" -> listOf(f("create", "database=None, schema=None, identifier=None, type=None", DbtType.RELATION, "Creates a dbt Relation.", "https://docs.getdbt.com/reference/dbt-classes"))
            "api.Column" -> listOf(f("create", "name, label_or_dtype", DbtType.COLUMN))
            "builtins" -> listOfNotNull(CONTEXT["ref"], CONTEXT["source"], CONTEXT["config"])
            "adapter" -> listOf(f("get_relation", "database, schema, identifier", DbtType("Relation | None")), f("load_relation", "relation", DbtType("Relation | None")),
                f("get_columns_in_relation", "relation", DbtType("list", DbtType.COLUMN)), f("get_missing_columns", "from_relation, to_relation", DbtType("list", DbtType.COLUMN)),
                f("dispatch", "macro_name, macro_namespace=None", type("callable")), f("quote", "identifier", DbtType.STRING),
                f("create_schema", "relation"), f("drop_schema", "relation"), f("drop_relation", "relation"), f("rename_relation", "from_relation, to_relation"),
                f("expand_target_column_types", "from_relation, to_relation"))
            "exceptions" -> listOf(f("raise_compiler_error", "msg"), f("warn", "msg"))
            "modules" -> listOf("datetime", "pytz", "re").map { p(it, DbtType("modules.$it")) } + DbtSymbol("itertools", type = DbtType("modules.itertools"), description = "Deprecated since dbt Core 1.10.6.", deprecated = true)
            "modules.datetime" -> listOf("date", "datetime", "time", "timedelta", "tzinfo").map { p(it, DbtType("modules.datetime.$it")) }
            "modules.re" -> listOf(f("match", "pattern, string, flags=0"), f("search", "pattern, string, flags=0"), f("sub", "pattern, repl, string, count=0, flags=0", DbtType.STRING),
                f("split", "pattern, string, maxsplit=0, flags=0", DbtType("list", DbtType.STRING))) + listOf("IGNORECASE", "MULTILINE", "DOTALL").map { p(it, DbtType.NUMBER) }
            "modules.pytz" -> listOf(f("timezone", "zone", DbtType("timezone")), p("utc", DbtType("timezone")))
            "loop" -> listOf("index", "index0", "revindex", "revindex0", "length", "depth", "depth0").map { p(it, DbtType.NUMBER) } +
                listOf("first", "last").map { p(it, DbtType.BOOL) } + listOf("previtem", "nextitem").map { p(it, type.item ?: DbtType.UNKNOWN) } +
                listOf(f("cycle", "*args"), f("changed", "*values", DbtType.BOOL))
            "macro" -> listOf(p("name", DbtType.STRING), p("arguments", DbtType.LIST), p("catch_kwargs", DbtType.BOOL), p("catch_varargs", DbtType.BOOL), p("caller", DbtType.BOOL))
            "flags" -> listOf("WHICH", "FULL_REFRESH", "STORE_FAILURES").map { p(it, if (it == "WHICH") DbtType.STRING else DbtType.BOOL) }
            else -> emptyList()
        }
        val url = when (type.name) {
            "dict", "string", "list" -> "https://docs.python.org/3/library/stdtypes.html"
            "Relation", "Relation | None", "Column", "api", "api.Relation", "api.Column" -> "https://docs.getdbt.com/reference/dbt-classes"
            "adapter", "builtins", "modules", "exceptions", "flags" -> "https://docs.getdbt.com/reference/dbt-jinja-functions/${type.name}"
            else -> "https://jinja.palletsprojects.com/en/stable/templates/"
        }
        return methods.map { it.copy(url = url) } + type.fields.filterKeys { key -> key.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) && methods.none { it.name == key } }.map { (name, value) -> p(name, value) }
    }
}
