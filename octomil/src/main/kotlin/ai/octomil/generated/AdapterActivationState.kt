package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class AdapterActivationState(val code: String) {
    NONE("none"),
    STAGED("staged"),
    WARMING("warming"),
    SHADOW("shadow"),
    ACTIVE("active"),
    DRAINING_OLD("draining_old"),
    FINALIZED("finalized"),
    FAILED_HEALTHCHECK("failed_healthcheck"),
    REJECTED("rejected"),
    ROLLBACK_PENDING("rollback_pending");

    companion object {
        fun fromCode(code: String): AdapterActivationState? =
            entries.firstOrNull { it.code == code }
    }
}
