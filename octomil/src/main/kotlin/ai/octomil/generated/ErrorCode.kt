// Auto-generated from octomil-contracts. Do not edit.
package ai.octomil.generated

enum class ErrorCode(val code: String) {
    NETWORK_UNAVAILABLE("network_unavailable"),
    REQUEST_TIMEOUT("request_timeout"),
    SERVER_ERROR("server_error"),
    INVALID_API_KEY("invalid_api_key"),
    AUTHENTICATION_FAILED("authentication_failed"),
    FORBIDDEN("forbidden"),
    MODEL_NOT_FOUND("model_not_found"),
    MODEL_DISABLED("model_disabled"),
    DOWNLOAD_FAILED("download_failed"),
    CHECKSUM_MISMATCH("checksum_mismatch"),
    INSUFFICIENT_STORAGE("insufficient_storage"),
    RUNTIME_UNAVAILABLE("runtime_unavailable"),
    MODEL_LOAD_FAILED("model_load_failed"),
    INFERENCE_FAILED("inference_failed"),
    INSUFFICIENT_MEMORY("insufficient_memory"),
    RATE_LIMITED("rate_limited"),
    INVALID_INPUT("invalid_input"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown");

    companion object {
        fun fromCode(code: String): ErrorCode? =
            entries.firstOrNull { it.code == code }
    }
}
