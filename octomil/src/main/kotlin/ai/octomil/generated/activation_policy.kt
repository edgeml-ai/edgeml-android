package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ActivationPolicy(val code: String) {
    IMMEDIATE("immediate"),
    NEXT_LAUNCH("next_launch"),
    MANUAL("manual"),
    WHEN_IDLE("when_idle");

    companion object {
        fun fromCode(code: String): ActivationPolicy? =
            entries.firstOrNull { it.code == code }
    }
}
