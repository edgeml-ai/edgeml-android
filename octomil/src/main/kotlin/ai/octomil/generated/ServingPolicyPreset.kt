package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ServingPolicyPreset(val code: String) {
    PRIVATE("private"),
    LOCAL_FIRST("local_first"),
    PERFORMANCE_FIRST("performance_first"),
    QUALITY_FIRST("quality_first"),
    CLOUD_FIRST("cloud_first"),
    CLOUD_ONLY("cloud_only");

    companion object {
        fun fromCode(code: String): ServingPolicyPreset? =
            entries.firstOrNull { it.code == code }
    }
}
