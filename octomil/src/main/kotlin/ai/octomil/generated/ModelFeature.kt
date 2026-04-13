package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ModelFeature(val code: String) {
    STREAMING("streaming"),
    BATCH("batch"),
    FUNCTION_CALLING("function_calling"),
    STRUCTURED_OUTPUT("structured_output");

    companion object {
        fun fromCode(code: String): ModelFeature? =
            entries.firstOrNull { it.code == code }
    }
}
