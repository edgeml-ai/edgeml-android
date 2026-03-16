package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class TrainingJobState(val code: String) {
    NEW("new"),
    ELIGIBLE("eligible"),
    QUEUED("queued"),
    PREPARING_DATA("preparing_data"),
    WAITING_FOR_RESOURCES("waiting_for_resources"),
    TRAINING("training"),
    CHECKPOINTING("checkpointing"),
    EVALUATING("evaluating"),
    CANDIDATE_READY("candidate_ready"),
    STAGED("staged"),
    ACTIVATING("activating"),
    ACTIVE("active"),
    COMPLETED("completed"),
    BLOCKED_POLICY("blocked_policy"),
    PAUSED("paused"),
    FAILED_RETRYABLE("failed_retryable"),
    FAILED_FATAL("failed_fatal"),
    REJECTED("rejected"),
    ROLLBACK("rollback"),
    SUPERSEDED("superseded");

    companion object {
        fun fromCode(code: String): TrainingJobState? =
            entries.firstOrNull { it.code == code }
    }
}
