// Auto-generated span status mapping constants.

object SpanStatusMapping {
    const val ERROR_TYPE = "error.type"
    const val OCTOMIL_ERROR_CODE = "octomil.error.code"
    const val OCTOMIL_ERROR_RETRYABLE = "octomil.error.retryable"
    const val OCTOMIL_ERROR_MESSAGE = "octomil.error.message"

    val SPAN_EXPECTED_ERRORS: Map<String, List<String>> = mapOf(
        "octomil.response" to listOf("inference_failed", "model_load_failed", "insufficient_memory", "runtime_unavailable", "stream_interrupted", "context_too_large", "unsupported_modality", "cancelled", "app_backgrounded", "max_tool_rounds_exceeded", "policy_denied"),
        "octomil.model.load" to listOf("model_not_found", "model_disabled", "version_not_found", "download_failed", "checksum_mismatch", "insufficient_storage", "insufficient_memory", "runtime_unavailable", "model_load_failed", "accelerator_unavailable"),
        "octomil.tool.execute" to listOf("inference_failed", "cancelled", "max_tool_rounds_exceeded"),
        "octomil.fallback.cloud" to listOf("network_unavailable", "request_timeout", "server_error", "rate_limited", "cloud_fallback_disallowed", "authentication_failed"),
        "octomil.control.refresh" to listOf("control_sync_failed", "network_unavailable", "authentication_failed", "forbidden"),
        "octomil.control.heartbeat" to listOf("control_sync_failed", "network_unavailable", "request_timeout"),
        "octomil.rollout.sync" to listOf("control_sync_failed", "download_failed", "insufficient_storage", "network_unavailable"),
    )
}
