package ai.octomil.generated

// Auto-generated span event name constants.

object SpanEventName {
    const val FIRST_TOKEN = "first_token"
    const val CHUNK_PRODUCED = "chunk_produced"
    const val TOOL_CALL_EMITTED = "tool_call_emitted"
    const val FALLBACK_TRIGGERED = "fallback_triggered"
    const val COMPLETED = "completed"
    const val TOOL_CALL_PARSE_SUCCEEDED = "tool_call_parse_succeeded"
    const val TOOL_CALL_PARSE_FAILED = "tool_call_parse_failed"
    const val DOWNLOAD_STARTED = "download_started"
    const val DOWNLOAD_COMPLETED = "download_completed"
    const val CHECKSUM_VERIFIED = "checksum_verified"
    const val RUNTIME_INITIALIZED = "runtime_initialized"

    val EVENT_PARENT_SPAN: Map<String, String> = mapOf(
        "first_token" to "octomil.response",
        "chunk_produced" to "octomil.response",
        "tool_call_emitted" to "octomil.response",
        "fallback_triggered" to "octomil.response",
        "completed" to "octomil.response",
        "tool_call_parse_succeeded" to "octomil.response",
        "tool_call_parse_failed" to "octomil.response",
        "download_started" to "octomil.model.load",
        "download_completed" to "octomil.model.load",
        "checksum_verified" to "octomil.model.load",
        "runtime_initialized" to "octomil.model.load",
    )
}
