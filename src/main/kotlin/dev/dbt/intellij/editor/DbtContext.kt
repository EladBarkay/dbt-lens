package dev.dbt.intellij.editor

/** dbt context names; local bindings take priority. `result` itself is a local name, not a global. */
object DbtContext {
    val NAMES = setOf("ref", "source", "var", "config", "target", "this", "adapter", "env_var", "is_incremental",
        "execute", "return", "log", "graph", "model", "modules", "flags", "run_started_at", "invocation_id",
        "project_name", "database", "schema", "selected_resources", "results", "schemas", "exceptions",
        "run_query", "statement", "load_result", "store_result", "debug", "print", "tojson", "fromjson", "toyaml", "fromyaml")
}
