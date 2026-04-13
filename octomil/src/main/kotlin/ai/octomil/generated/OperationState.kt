package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class OperationState(val code: String) {
    QUEUED("queued"),
    LEASED("leased"),
    RUNNING("running"),
    SUCCESS("success"),
    FAILED("failed"),
    PAUSED("paused");

    companion object {
        fun fromCode(code: String): OperationState? =
            entries.firstOrNull { it.code == code }
    }
}
