package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ArtifactStatus(val code: String) {
    NONE("none"),
    DISCOVERED("discovered"),
    DOWNLOADING("downloading"),
    DOWNLOADED_PARTIAL("downloaded_partial"),
    VERIFYING("verifying"),
    VERIFIED("verified"),
    STAGED("staged"),
    WARMING("warming"),
    ACTIVE("active"),
    DRAINING_OLD("draining_old"),
    FINALIZED("finalized"),
    GC_ELIGIBLE("gc_eligible"),
    PAUSED("paused"),
    FAILED_RETRYABLE("failed_retryable"),
    FAILED_CORRUPT("failed_corrupt"),
    FAILED_HEALTHCHECK("failed_healthcheck"),
    ROLLBACK_PENDING("rollback_pending"),
    ROLLED_BACK("rolled_back");

    companion object {
        fun fromCode(code: String): ArtifactStatus? =
            entries.firstOrNull { it.code == code }
    }
}
