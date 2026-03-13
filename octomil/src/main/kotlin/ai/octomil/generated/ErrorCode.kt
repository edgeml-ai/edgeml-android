// Auto-generated from octomil-contracts. Do not edit.

enum class ErrorCategory(val code: String) {
    AUTH("auth"),
    NETWORK("network"),
    INPUT("input"),
    CATALOG("catalog"),
    DOWNLOAD("download"),
    DEVICE("device"),
    RUNTIME("runtime"),
    POLICY("policy"),
    CONTROL("control"),
    LIFECYCLE("lifecycle"),
    UNKNOWN("unknown");
}

enum class RetryClass(val code: String) {
    NEVER("never"),
    IMMEDIATE_SAFE("immediate_safe"),
    BACKOFF_SAFE("backoff_safe"),
    CONDITIONAL("conditional");
}

enum class SuggestedAction(val code: String) {
    FIX_CREDENTIALS("fix_credentials"),
    REAUTHENTICATE("reauthenticate"),
    CHECK_PERMISSIONS("check_permissions"),
    REGISTER_DEVICE("register_device"),
    RETRY_OR_FALLBACK("retry_or_fallback"),
    RETRY("retry"),
    RETRY_AFTER("retry_after"),
    FIX_REQUEST("fix_request"),
    REDUCE_INPUT_OR_FALLBACK("reduce_input_or_fallback"),
    CHECK_MODEL_ID("check_model_id"),
    USE_ALTERNATE_MODEL("use_alternate_model"),
    CHECK_VERSION("check_version"),
    REDOWNLOAD("redownload"),
    FREE_STORAGE_OR_FALLBACK("free_storage_or_fallback"),
    TRY_SMALLER_MODEL("try_smaller_model"),
    TRY_ALTERNATE_RUNTIME("try_alternate_runtime"),
    TRY_CPU_OR_FALLBACK("try_cpu_or_fallback"),
    CHECK_POLICY("check_policy"),
    CHANGE_POLICY_OR_FIX_LOCAL("change_policy_or_fix_local"),
    INCREASE_LIMIT_OR_SIMPLIFY("increase_limit_or_simplify"),
    CHECK_ASSIGNMENT("check_assignment"),
    NONE("none"),
    RESUME_ON_FOREGROUND("resume_on_foreground"),
    REPORT_BUG("report_bug");
}

enum class ErrorCode(
    val code: String,
    val category: ErrorCategory,
    val retryClass: RetryClass,
    val fallbackEligible: Boolean,
    val suggestedAction: SuggestedAction,
) {
    INVALID_API_KEY("invalid_api_key", ErrorCategory.AUTH, RetryClass.NEVER, false, SuggestedAction.FIX_CREDENTIALS),
    AUTHENTICATION_FAILED("authentication_failed", ErrorCategory.AUTH, RetryClass.NEVER, false, SuggestedAction.REAUTHENTICATE),
    FORBIDDEN("forbidden", ErrorCategory.AUTH, RetryClass.NEVER, false, SuggestedAction.CHECK_PERMISSIONS),
    DEVICE_NOT_REGISTERED("device_not_registered", ErrorCategory.AUTH, RetryClass.NEVER, false, SuggestedAction.REGISTER_DEVICE),
    NETWORK_UNAVAILABLE("network_unavailable", ErrorCategory.NETWORK, RetryClass.BACKOFF_SAFE, true, SuggestedAction.RETRY_OR_FALLBACK),
    REQUEST_TIMEOUT("request_timeout", ErrorCategory.NETWORK, RetryClass.CONDITIONAL, true, SuggestedAction.RETRY_OR_FALLBACK),
    SERVER_ERROR("server_error", ErrorCategory.NETWORK, RetryClass.BACKOFF_SAFE, true, SuggestedAction.RETRY),
    RATE_LIMITED("rate_limited", ErrorCategory.NETWORK, RetryClass.CONDITIONAL, false, SuggestedAction.RETRY_AFTER),
    INVALID_INPUT("invalid_input", ErrorCategory.INPUT, RetryClass.NEVER, false, SuggestedAction.FIX_REQUEST),
    UNSUPPORTED_MODALITY("unsupported_modality", ErrorCategory.INPUT, RetryClass.NEVER, false, SuggestedAction.FIX_REQUEST),
    CONTEXT_TOO_LARGE("context_too_large", ErrorCategory.INPUT, RetryClass.NEVER, true, SuggestedAction.REDUCE_INPUT_OR_FALLBACK),
    MODEL_NOT_FOUND("model_not_found", ErrorCategory.CATALOG, RetryClass.NEVER, false, SuggestedAction.CHECK_MODEL_ID),
    MODEL_DISABLED("model_disabled", ErrorCategory.CATALOG, RetryClass.NEVER, true, SuggestedAction.USE_ALTERNATE_MODEL),
    VERSION_NOT_FOUND("version_not_found", ErrorCategory.CATALOG, RetryClass.NEVER, false, SuggestedAction.CHECK_VERSION),
    DOWNLOAD_FAILED("download_failed", ErrorCategory.DOWNLOAD, RetryClass.BACKOFF_SAFE, true, SuggestedAction.RETRY_OR_FALLBACK),
    CHECKSUM_MISMATCH("checksum_mismatch", ErrorCategory.DOWNLOAD, RetryClass.CONDITIONAL, false, SuggestedAction.REDOWNLOAD),
    INSUFFICIENT_STORAGE("insufficient_storage", ErrorCategory.DEVICE, RetryClass.NEVER, true, SuggestedAction.FREE_STORAGE_OR_FALLBACK),
    INSUFFICIENT_MEMORY("insufficient_memory", ErrorCategory.DEVICE, RetryClass.NEVER, true, SuggestedAction.TRY_SMALLER_MODEL),
    RUNTIME_UNAVAILABLE("runtime_unavailable", ErrorCategory.DEVICE, RetryClass.NEVER, true, SuggestedAction.TRY_ALTERNATE_RUNTIME),
    ACCELERATOR_UNAVAILABLE("accelerator_unavailable", ErrorCategory.DEVICE, RetryClass.NEVER, true, SuggestedAction.TRY_CPU_OR_FALLBACK),
    MODEL_LOAD_FAILED("model_load_failed", ErrorCategory.RUNTIME, RetryClass.CONDITIONAL, true, SuggestedAction.RETRY_OR_FALLBACK),
    INFERENCE_FAILED("inference_failed", ErrorCategory.RUNTIME, RetryClass.CONDITIONAL, true, SuggestedAction.RETRY_OR_FALLBACK),
    STREAM_INTERRUPTED("stream_interrupted", ErrorCategory.RUNTIME, RetryClass.IMMEDIATE_SAFE, true, SuggestedAction.RETRY),
    POLICY_DENIED("policy_denied", ErrorCategory.POLICY, RetryClass.NEVER, false, SuggestedAction.CHECK_POLICY),
    CLOUD_FALLBACK_DISALLOWED("cloud_fallback_disallowed", ErrorCategory.POLICY, RetryClass.NEVER, false, SuggestedAction.CHANGE_POLICY_OR_FIX_LOCAL),
    MAX_TOOL_ROUNDS_EXCEEDED("max_tool_rounds_exceeded", ErrorCategory.POLICY, RetryClass.NEVER, false, SuggestedAction.INCREASE_LIMIT_OR_SIMPLIFY),
    CONTROL_SYNC_FAILED("control_sync_failed", ErrorCategory.CONTROL, RetryClass.BACKOFF_SAFE, false, SuggestedAction.RETRY),
    ASSIGNMENT_NOT_FOUND("assignment_not_found", ErrorCategory.CONTROL, RetryClass.NEVER, false, SuggestedAction.CHECK_ASSIGNMENT),
    CANCELLED("cancelled", ErrorCategory.LIFECYCLE, RetryClass.NEVER, false, SuggestedAction.NONE),
    APP_BACKGROUNDED("app_backgrounded", ErrorCategory.LIFECYCLE, RetryClass.CONDITIONAL, false, SuggestedAction.RESUME_ON_FOREGROUND),
    UNKNOWN("unknown", ErrorCategory.UNKNOWN, RetryClass.NEVER, false, SuggestedAction.REPORT_BUG);

    companion object {
        fun fromCode(code: String): ErrorCode? =
            entries.firstOrNull { it.code == code }
    }
}
