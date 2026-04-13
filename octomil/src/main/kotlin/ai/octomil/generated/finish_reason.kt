package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class FinishReason(val code: String) {
    STOP("stop"),
    TOOL_CALLS("tool_calls"),
    LENGTH("length"),
    CONTENT_FILTER("content_filter");

    companion object {
        fun fromCode(code: String): FinishReason? =
            entries.firstOrNull { it.code == code }
    }
}
