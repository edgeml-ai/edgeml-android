package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ToolCallTier(val code: String) {
    NONE("NONE"),
    TEXT_JSON("TEXT_JSON"),
    GRAMMAR("GRAMMAR"),
    NATIVE("NATIVE");

    companion object {
        fun fromCode(code: String): ToolCallTier? =
            entries.firstOrNull { it.code == code }
    }
}
