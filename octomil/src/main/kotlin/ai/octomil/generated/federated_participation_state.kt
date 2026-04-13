package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class FederatedParticipationState(val code: String) {
    NOT_ENROLLED("not_enrolled"),
    OFFERED("offered"),
    ACCEPTED("accepted"),
    PLAN_FETCHING("plan_fetching"),
    PLAN_READY("plan_ready"),
    WAITING_FOR_WINDOW("waiting_for_window"),
    LOCAL_TRAINING("local_training"),
    LOCAL_EVAL("local_eval"),
    UPDATE_PREPARING("update_preparing"),
    CLIPPING("clipping"),
    NOISING("noising"),
    ENCRYPTING("encrypting"),
    UPLOADING("uploading"),
    UPLOADED("uploaded"),
    ACKNOWLEDGED("acknowledged"),
    COMPLETED("completed"),
    DECLINED_POLICY("declined_policy"),
    FAILED_RETRYABLE("failed_retryable"),
    ABORTED_POLICY("aborted_policy"),
    REJECTED_LOCAL("rejected_local"),
    UPLOAD_DEFERRED("upload_deferred"),
    EXPIRED_ROUND("expired_round");

    companion object {
        fun fromCode(code: String): FederatedParticipationState? =
            entries.firstOrNull { it.code == code }
    }
}
