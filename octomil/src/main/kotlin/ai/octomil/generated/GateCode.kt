package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class GateCode(val code: String) {
    ARTIFACT_VERIFIED("artifact_verified"),
    RUNTIME_AVAILABLE("runtime_available"),
    MODEL_LOADS("model_loads"),
    CONTEXT_FITS("context_fits"),
    MODALITY_SUPPORTED("modality_supported"),
    TOOL_SUPPORT("tool_support"),
    MIN_TOKENS_PER_SECOND("min_tokens_per_second"),
    MAX_TTFT_MS("max_ttft_ms"),
    MAX_ERROR_RATE("max_error_rate"),
    MIN_FREE_MEMORY_BYTES("min_free_memory_bytes"),
    MIN_FREE_STORAGE_BYTES("min_free_storage_bytes"),
    BENCHMARK_FRESH("benchmark_fresh"),
    MIN_BATTERY_PCT("min_battery_pct"),
    MAX_THERMAL_STATE("max_thermal_state"),
    REQUIRE_CHARGING("require_charging"),
    REQUIRE_WIFI("require_wifi"),
    SCHEMA_VALID("schema_valid"),
    TOOL_CALL_VALID("tool_call_valid"),
    SAFETY_PASSED("safety_passed"),
    EVALUATOR_SCORE_MIN("evaluator_score_min"),
    JSON_PARSEABLE("json_parseable"),
    MAX_REFUSAL_RATE("max_refusal_rate");

    companion object {
        fun fromCode(code: String): GateCode? =
            entries.firstOrNull { it.code == code }
    }
}
