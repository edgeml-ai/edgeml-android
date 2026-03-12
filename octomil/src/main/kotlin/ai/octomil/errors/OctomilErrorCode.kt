package ai.octomil.errors

/**
 * Canonical error codes matching SDK_FACADE_CONTRACT.md.
 * All 19 required codes from the server's ErrorCode enum.
 *
 * Each code carries a [retryable] flag indicating whether the operation that
 * produced the error is safe to retry automatically.
 */
enum class OctomilErrorCode(val retryable: Boolean) {
    NETWORK_UNAVAILABLE(true),
    REQUEST_TIMEOUT(true),
    SERVER_ERROR(true),
    INVALID_API_KEY(false),
    AUTHENTICATION_FAILED(false),
    FORBIDDEN(false),
    MODEL_NOT_FOUND(false),
    MODEL_DISABLED(false),
    DOWNLOAD_FAILED(true),
    CHECKSUM_MISMATCH(true),
    INSUFFICIENT_STORAGE(false),
    RUNTIME_UNAVAILABLE(false),
    MODEL_LOAD_FAILED(false),
    INFERENCE_FAILED(true),
    INSUFFICIENT_MEMORY(false),
    RATE_LIMITED(true),
    INVALID_INPUT(false),
    CANCELLED(false),
    UNKNOWN(false);

    companion object {
        /**
         * Map an HTTP status code to the most appropriate [OctomilErrorCode].
         *
         * Covers the standard error responses from the Octomil API:
         * - 401 -> INVALID_API_KEY
         * - 403 -> FORBIDDEN
         * - 404 -> MODEL_NOT_FOUND
         * - 408 -> REQUEST_TIMEOUT
         * - 429 -> RATE_LIMITED
         * - 5xx -> SERVER_ERROR
         * - Other 4xx -> UNKNOWN
         */
        fun fromHttpStatus(code: Int): OctomilErrorCode = when (code) {
            401 -> INVALID_API_KEY
            403 -> FORBIDDEN
            404 -> MODEL_NOT_FOUND
            408 -> REQUEST_TIMEOUT
            429 -> RATE_LIMITED
            in 500..599 -> SERVER_ERROR
            else -> UNKNOWN
        }
    }
}

/**
 * Unified exception for all Octomil SDK errors.
 *
 * Wraps an [OctomilErrorCode] with a human-readable message and optional cause.
 * The [retryable] convenience property delegates to the error code so callers
 * can decide whether to retry without inspecting the enum directly.
 */
class OctomilException(
    val errorCode: OctomilErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * Whether the operation that produced this error is safe to retry.
     */
    val retryable: Boolean get() = errorCode.retryable

    override fun toString(): String =
        "OctomilException(code=$errorCode, retryable=$retryable, message=$message)"
}
