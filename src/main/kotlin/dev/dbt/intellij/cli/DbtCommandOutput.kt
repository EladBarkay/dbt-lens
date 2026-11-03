package dev.dbt.intellij.cli

import com.google.gson.JsonParser

/** Bounded readable log and dbt Core CompiledNode events, including chunks split mid-JSON. */
internal class DbtCommandOutput {
    private val partial = StringBuilder()
    private val log = StringBuilder()
    var compiled: String? = null
        private set

    var selectionMissing = false
        private set

    val text
        get() = log.toString()

    fun accept(chunk: String) {
        for (c in chunk) {
            if (c == '\n') {
                line(partial.toString())
                partial.setLength(0)
            } else if (partial.length < 2_100_000) partial.append(c)
        }
    }

    fun flush() {
        if (partial.isNotEmpty()) {
            line(partial.toString())
            partial.setLength(0)
        }
    }

    private fun line(raw: String) {
        val record = runCatching {
            JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
        }
            .getOrNull()
        val info = record?.get("info")?.takeIf { it.isJsonObject }?.asJsonObject
        val name = info?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        if (name == "CompiledNode")
            record
                ?.get("data")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("compiled")
                ?.takeIf {
                    it.isJsonPrimitive &&
                        it.asJsonPrimitive.isString &&
                        it.asString.length <= 2_000_000
                }
                ?.let { compiled = it.asString }
        if (name in setOf("NoNodesForSelectionCriteria", "NothingToDo")) selectionMissing = true
        val message =
            info?.get("msg")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: raw
        log.append(message.trimEnd('\r')).append('\n')
        if (log.length > 200_000) log.delete(0, log.length - 200_000)
    }
}
