package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class TelemetryClass(val code: String) {
    MUST_KEEP("must_keep"),
    IMPORTANT("important"),
    BEST_EFFORT("best_effort");

    companion object {
        fun fromCode(code: String): TelemetryClass? =
            entries.firstOrNull { it.code == code }
    }
}
