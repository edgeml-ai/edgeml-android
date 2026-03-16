package ai.octomil.generated

// Auto-generated span event name constants.

object SpanEventName {
    const val FIRST_TOKEN = "first_token"
    const val CHUNK_PRODUCED = "chunk_produced"
    const val TOOL_CALL_EMITTED = "tool_call_emitted"
    const val FALLBACK_TRIGGERED = "fallback_triggered"
    const val COMPLETED = "completed"
    const val TOOL_CALL_PARSE_SUCCEEDED = "tool_call_parse_succeeded"
    const val TOOL_CALL_PARSE_FAILED = "tool_call_parse_failed"
    const val DOWNLOAD_STARTED = "download_started"
    const val DOWNLOAD_COMPLETED = "download_completed"
    const val CHECKSUM_VERIFIED = "checksum_verified"
    const val RUNTIME_INITIALIZED = "runtime_initialized"
    const val CHUNK_DOWNLOAD_STARTED = "chunk_download_started"
    const val CHUNK_DOWNLOAD_COMPLETED = "chunk_download_completed"
    const val CHUNK_DOWNLOAD_FAILED = "chunk_download_failed"
    const val ARTIFACT_VERIFIED = "artifact_verified"
    const val WARMING_STARTED = "warming_started"
    const val HEALTHCHECK_PASSED = "healthcheck_passed"
    const val HEALTHCHECK_FAILED = "healthcheck_failed"
    const val ACTIVATION_COMPLETE = "activation_complete"
    const val ROLLBACK_TRIGGERED = "rollback_triggered"
    const val PLAN_FETCHED = "plan_fetched"
    const val LOCAL_TRAINING_STARTED = "local_training_started"
    const val LOCAL_TRAINING_COMPLETED = "local_training_completed"
    const val UPDATE_CLIPPED = "update_clipped"
    const val UPDATE_NOISED = "update_noised"
    const val UPDATE_ENCRYPTED = "update_encrypted"
    const val UPLOAD_STARTED = "upload_started"
    const val UPLOAD_COMPLETED = "upload_completed"
    const val PARTICIPATION_ABORTED = "participation_aborted"
    const val ROUND_STARTED = "round_started"
    const val ROUND_AGGREGATED = "round_aggregated"
    const val CANDIDATE_PUBLISHED = "candidate_published"
    const val JOB_COMPLETED = "job_completed"
    const val DESIRED_STATE_FETCHED = "desired_state_fetched"
    const val OBSERVED_STATE_REPORTED = "observed_state_reported"
    const val STATE_DRIFT_DETECTED = "state_drift_detected"
    const val DEVICE_REGISTERED = "device.registered"

    val EVENT_PARENT_SPAN: Map<String, String> = mapOf(
        "first_token" to "octomil.response",
        "chunk_produced" to "octomil.response",
        "tool_call_emitted" to "octomil.response",
        "fallback_triggered" to "octomil.response",
        "completed" to "octomil.response",
        "tool_call_parse_succeeded" to "octomil.response",
        "tool_call_parse_failed" to "octomil.response",
        "download_started" to "octomil.model.load",
        "download_completed" to "octomil.model.load",
        "checksum_verified" to "octomil.model.load",
        "runtime_initialized" to "octomil.model.load",
        "chunk_download_started" to "octomil.artifact.download",
        "chunk_download_completed" to "octomil.artifact.download",
        "chunk_download_failed" to "octomil.artifact.download",
        "artifact_verified" to "octomil.artifact.download",
        "warming_started" to "octomil.artifact.activation",
        "healthcheck_passed" to "octomil.artifact.activation",
        "healthcheck_failed" to "octomil.artifact.activation",
        "activation_complete" to "octomil.artifact.activation",
        "rollback_triggered" to "octomil.artifact.activation",
        "plan_fetched" to "octomil.federation.round",
        "local_training_started" to "octomil.federation.round",
        "local_training_completed" to "octomil.federation.round",
        "update_clipped" to "octomil.federation.round",
        "update_noised" to "octomil.federation.round",
        "update_encrypted" to "octomil.federation.round",
        "upload_started" to "octomil.federation.round",
        "upload_completed" to "octomil.federation.round",
        "participation_aborted" to "octomil.federation.round",
        "round_started" to "octomil.training.job",
        "round_aggregated" to "octomil.training.job",
        "candidate_published" to "octomil.training.job",
        "job_completed" to "octomil.training.job",
        "desired_state_fetched" to "octomil.device.sync",
        "observed_state_reported" to "octomil.device.sync",
        "state_drift_detected" to "octomil.device.sync",
        "device.registered" to "octomil.control.register",
    )
}
