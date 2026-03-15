package ai.octomil.generated

// Auto-generated span event attribute key constants.

object SpanEventAttribute {
    const val OCTOMIL_TTFT_MS = "octomil.ttft_ms"
    const val OCTOMIL_CHUNK_INDEX = "octomil.chunk.index"
    const val OCTOMIL_CHUNK_LATENCY_MS = "octomil.chunk.latency_ms"
    const val OCTOMIL_TOOL_NAME = "octomil.tool.name"
    const val OCTOMIL_TOOL_ROUND = "octomil.tool.round"
    const val OCTOMIL_FALLBACK_REASON = "octomil.fallback.reason"
    const val OCTOMIL_FALLBACK_PROVIDER = "octomil.fallback.provider"
    const val OCTOMIL_TOKENS_TOTAL = "octomil.tokens.total"
    const val OCTOMIL_TOKENS_PER_SECOND = "octomil.tokens.per_second"
    const val OCTOMIL_DURATION_MS = "octomil.duration_ms"
    const val OCTOMIL_DOWNLOAD_URL = "octomil.download.url"
    const val OCTOMIL_DOWNLOAD_EXPECTED_BYTES = "octomil.download.expected_bytes"
    const val OCTOMIL_DOWNLOAD_DURATION_MS = "octomil.download.duration_ms"
    const val OCTOMIL_DOWNLOAD_BYTES = "octomil.download.bytes"
    const val OCTOMIL_CHECKSUM_ALGORITHM = "octomil.checksum.algorithm"
    const val OCTOMIL_RUNTIME_EXECUTOR = "octomil.runtime.executor"
    const val OCTOMIL_RUNTIME_INIT_MS = "octomil.runtime.init_ms"
    const val OCTOMIL_TOOL_EXTRACTION_STRATEGY = "octomil.tool.extraction_strategy"
    const val OCTOMIL_TOOL_RAW_TEXT_PREVIEW = "octomil.tool.raw_text_preview"

    val EVENT_REQUIRED_ATTRIBUTES: Map<String, List<String>> = mapOf(
        "first_token" to listOf("octomil.ttft_ms"),
        "chunk_produced" to listOf("octomil.chunk.index"),
        "tool_call_emitted" to listOf("octomil.tool.name", "octomil.tool.round"),
        "fallback_triggered" to listOf("octomil.fallback.reason"),
        "completed" to listOf("octomil.tokens.total", "octomil.tokens.per_second", "octomil.duration_ms"),
        "download_started" to listOf(),
        "download_completed" to listOf("octomil.download.duration_ms", "octomil.download.bytes"),
        "checksum_verified" to listOf(),
        "runtime_initialized" to listOf("octomil.runtime.executor", "octomil.runtime.init_ms"),
        "tool_call_parse_succeeded" to listOf("octomil.tool.name", "octomil.tool.extraction_strategy"),
        "tool_call_parse_failed" to listOf("octomil.tool.extraction_strategy"),
    )
}
