package dev.dbt.intellij.cli

/** Bounds for dbt command input, output and process handling. */
internal object CommandLimits {
    const val MAX_ENVIRONMENT_FILE_BYTES = 65_536L
    const val MAX_INVOCATION_CHARS = 65_536
    const val MAX_PARTIAL_LINE_CHARS = 2_100_000
    const val MAX_RESULT_CHARS = 2_000_000
    const val MAX_LOG_CHARS = 200_000
    const val PROCESS_POLL_MILLIS = 100L
    const val TERMINATE_TIMEOUT_MILLIS = 2_000L
}
