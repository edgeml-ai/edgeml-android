package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class QueryRoutingTier(val code: String) {
    FAST("fast"),
    BALANCED("balanced"),
    QUALITY("quality");

    companion object {
        fun fromCode(code: String): QueryRoutingTier? =
            entries.firstOrNull { it.code == code }
    }
}
