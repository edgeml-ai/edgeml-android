package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class KvCacheStrategy(val code: String) {
    DISABLED("disabled"),
    BUDGET_ONLY("budget_only"),
    COMPRESSED("compressed");

    companion object {
        fun fromCode(code: String): KvCacheStrategy? =
            entries.firstOrNull { it.code == code }
    }
}
