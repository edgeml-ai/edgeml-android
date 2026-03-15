package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class SupportTier(val code: String) {
    BLESSED("blessed"),
    SUPPORTED("supported"),
    EXPERIMENTAL("experimental"),
    RESEARCH("research");

    companion object {
        fun fromCode(code: String): SupportTier? =
            entries.firstOrNull { it.code == code }
    }
}
