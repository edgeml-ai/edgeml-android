package ai.octomil.generated

// Auto-generated metric view constants.

data class MetricView(
    val name: String,
    val instrument: String,
    val unit: String,
    val sourceSpan: String,
)

object MetricViews {
    const val OCTOMIL_RESPONSE_DURATION = "octomil.response.duration"
    const val OCTOMIL_RESPONSE_TTFT = "octomil.response.ttft"
    const val OCTOMIL_RESPONSE_TOKENS_PER_SECOND = "octomil.response.tokens_per_second"
    const val OCTOMIL_MODEL_LOAD_DURATION = "octomil.model.load.duration"
    const val OCTOMIL_MODEL_LOAD_FAILURE_RATE = "octomil.model.load.failure_rate"
    const val OCTOMIL_FALLBACK_RATE = "octomil.fallback.rate"
    const val OCTOMIL_HEARTBEAT_FRESHNESS = "octomil.heartbeat.freshness"
    const val OCTOMIL_TOOL_EXECUTE_DURATION = "octomil.tool.execute.duration"

    val ALL_METRIC_VIEWS = listOf(
        MetricView("octomil.response.duration", "histogram", "ms", "octomil.response"),
        MetricView("octomil.response.ttft", "histogram", "ms", "octomil.response"),
        MetricView("octomil.response.tokens_per_second", "histogram", "{tokens}/s", "octomil.response"),
        MetricView("octomil.model.load.duration", "histogram", "ms", "octomil.model.load"),
        MetricView("octomil.model.load.failure_rate", "counter", "{failures}", "octomil.model.load"),
        MetricView("octomil.fallback.rate", "counter", "{fallbacks}", "octomil.response"),
        MetricView("octomil.heartbeat.freshness", "gauge", "s", "octomil.control.heartbeat"),
        MetricView("octomil.tool.execute.duration", "histogram", "ms", "octomil.tool.execute"),
    )
}
