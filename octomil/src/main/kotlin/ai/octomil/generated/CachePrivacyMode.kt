package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class CachePrivacyMode(val code: String) {
    STRICT("strict"),
    POLICY_ALLOWED("policy_allowed");

    companion object {
        fun fromCode(code: String): CachePrivacyMode? =
            entries.firstOrNull { it.code == code }
    }
}
