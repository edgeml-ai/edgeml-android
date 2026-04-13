package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class FederatedRoundState(val code: String) {
    DRAFT("draft"),
    SCHEDULED("scheduled"),
    OPEN("open"),
    ACCEPTING_PARTICIPANTS("accepting_participants"),
    TRAINING_IN_PROGRESS("training_in_progress"),
    AGGREGATING("aggregating"),
    VALIDATING("validating"),
    PUBLISHING("publishing"),
    PUBLISHED("published"),
    CLOSED("closed"),
    CANCELLED("cancelled"),
    FAILED_VALIDATION("failed_validation"),
    INSUFFICIENT_UPDATES("insufficient_updates"),
    PARTIAL_PUBLISH("partial_publish");

    companion object {
        fun fromCode(code: String): FederatedRoundState? =
            entries.firstOrNull { it.code == code }
    }
}
