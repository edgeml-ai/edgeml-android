// Auto-generated span attribute key constants.

object SpanAttribute {
    const val MODEL_ID = "model.id"
    const val MODEL_VERSION = "model.version"
    const val RUNTIME_EXECUTOR = "runtime.executor"
    const val REQUEST_MODE = "request.mode"
    const val LOCALITY = "locality"
    const val STREAMING = "streaming"
    const val ROUTE_POLICY = "route.policy"
    const val ROUTE_DECISION = "route.decision"
    const val DEVICE_CLASS = "device.class"
    const val FALLBACK_REASON = "fallback.reason"
    const val ERROR_TYPE = "error.type"
    const val TOOL_CALL_TIER = "tool.call_tier"
    const val MODEL_SOURCE_FORMAT = "model.source_format"
    const val MODEL_SIZE_BYTES = "model.size_bytes"
    const val TOOL_NAME = "tool.name"
    const val TOOL_ROUND = "tool.round"
    const val FALLBACK_PROVIDER = "fallback.provider"
    const val ASSIGNMENT_COUNT = "assignment_count"
    const val HEARTBEAT_SEQUENCE = "heartbeat.sequence"
    const val ROLLOUT_ID = "rollout.id"
    const val MODELS_SYNCED = "models_synced"

    val SPAN_REQUIRED_ATTRIBUTES: Map<String, List<String>> = mapOf(
        "octomil.response" to listOf("model.id", "model.version", "runtime.executor", "request.mode", "locality", "streaming"),
        "octomil.model.load" to listOf("model.id", "model.version", "runtime.executor"),
        "octomil.tool.execute" to listOf("tool.name", "tool.round"),
        "octomil.fallback.cloud" to listOf("model.id", "fallback.reason"),
        "octomil.control.refresh" to listOf(),
        "octomil.control.heartbeat" to listOf("heartbeat.sequence"),
        "octomil.rollout.sync" to listOf("rollout.id"),
    )
}
