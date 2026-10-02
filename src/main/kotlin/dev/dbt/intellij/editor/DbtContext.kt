package dev.dbt.intellij.editor

/** Local bindings take priority. `result` itself is not a global. */
object DbtContext {
    val NAMES: Set<String> get() = dev.dbt.intellij.semantic.DbtSymbols.CONTEXT.keys
}
