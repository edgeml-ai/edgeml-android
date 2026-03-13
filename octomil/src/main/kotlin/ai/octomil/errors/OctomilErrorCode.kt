package ai.octomil.errors

import ai.octomil.generated.ErrorCategory
import ai.octomil.generated.ErrorCode as ContractErrorCode
import ai.octomil.generated.RetryClass
import ai.octomil.generated.SuggestedAction

/**
 * Canonical error codes matching the octomil-contracts error taxonomy.
 * 34 SDK-facing codes mapping to 36 contract codes (token_expired → AUTHENTICATION_FAILED, device_revoked → FORBIDDEN).
 *
 * Error metadata ([retryable], [category], [retryClass], [fallbackEligible],
 * [suggestedAction]) is derived from the generated [ContractErrorCode] taxonomy
 * so it stays in sync with the contract without manual maintenance.
 *
 * The generated [ContractErrorCode] enum from octomil-contracts defines the
 * canonical set of wire-format codes. Use [fromContractCode] to map from a
 * server response string to an [OctomilErrorCode].
 */
enum class OctomilErrorCode {
    NETWORK_UNAVAILABLE,
    REQUEST_TIMEOUT,
    SERVER_ERROR,
    INVALID_API_KEY,
    AUTHENTICATION_FAILED,
    FORBIDDEN,
    DEVICE_NOT_REGISTERED,
    MODEL_NOT_FOUND,
    MODEL_DISABLED,
    DOWNLOAD_FAILED,
    CHECKSUM_MISMATCH,
    INSUFFICIENT_STORAGE,
    RUNTIME_UNAVAILABLE,
    MODEL_LOAD_FAILED,
    INFERENCE_FAILED,
    INSUFFICIENT_MEMORY,
    RATE_LIMITED,
    INVALID_INPUT,
    UNSUPPORTED_MODALITY,
    CONTEXT_TOO_LARGE,
    VERSION_NOT_FOUND,
    ACCELERATOR_UNAVAILABLE,
    STREAM_INTERRUPTED,
    POLICY_DENIED,
    CLOUD_FALLBACK_DISALLOWED,
    MAX_TOOL_ROUNDS_EXCEEDED,
    TRAINING_FAILED,
    TRAINING_NOT_SUPPORTED,
    WEIGHT_UPLOAD_FAILED,
    CONTROL_SYNC_FAILED,
    ASSIGNMENT_NOT_FOUND,
    CANCELLED,
    APP_BACKGROUNDED,
    UNKNOWN;

    /**
     * Maps this SDK error code to the generated contract [ContractErrorCode].
     * Falls back to [ContractErrorCode.UNKNOWN] for SDK-specific codes
     * that don't exist in the contract.
     */
    private fun toContractCode(): ContractErrorCode =
        ContractErrorCode.fromCode(name.lowercase()) ?: ContractErrorCode.UNKNOWN

    /** Whether the operation that produced this error is safe to retry. */
    val retryable: Boolean get() = toContractCode().retryClass != RetryClass.NEVER

    /** The error category from the contract taxonomy. */
    val category: ErrorCategory get() = toContractCode().category

    /** The retry classification from the contract taxonomy. */
    val retryClass: RetryClass get() = toContractCode().retryClass

    /** Whether this error is eligible for cloud fallback. */
    val fallbackEligible: Boolean get() = toContractCode().fallbackEligible

    /** The suggested remediation action from the contract taxonomy. */
    val suggestedAction: SuggestedAction get() = toContractCode().suggestedAction

    companion object {
        /**
         * Map an HTTP status code to the most appropriate [OctomilErrorCode].
         *
         * This is a lossy fallback — prefer [fromServerResponse] which uses the
         * wire-format `code` field when available.
         *
         * - 400 -> INVALID_INPUT
         * - 401 -> AUTHENTICATION_FAILED (too broad for INVALID_API_KEY)
         * - 403 -> FORBIDDEN
         * - 404 -> MODEL_NOT_FOUND (assumes model endpoints; server `code` is more precise)
         * - 408 -> REQUEST_TIMEOUT
         * - 429 -> RATE_LIMITED
         * - 5xx -> SERVER_ERROR
         * - Other -> UNKNOWN
         */
        fun fromHttpStatus(code: Int): OctomilErrorCode = when (code) {
            400 -> INVALID_INPUT
            401 -> AUTHENTICATION_FAILED
            403 -> FORBIDDEN
            404 -> MODEL_NOT_FOUND
            408 -> REQUEST_TIMEOUT
            429 -> RATE_LIMITED
            in 500..599 -> SERVER_ERROR
            else -> UNKNOWN
        }

        /**
         * Map a wire-format error code string (e.g. "model_not_found") from a
         * server response to the corresponding [OctomilErrorCode].
         *
         * Uses the generated [ContractErrorCode] enum to parse the string,
         * then maps to the SDK's [OctomilErrorCode]. Unrecognised codes fall
         * back to [UNKNOWN] per the contract specification.
         */
        fun fromContractCode(code: String): OctomilErrorCode {
            val contractCode = ContractErrorCode.fromCode(code)
                ?: return UNKNOWN
            // Codes that exist in the contract but map to a different SDK code
            return when (contractCode) {
                ContractErrorCode.TOKEN_EXPIRED -> AUTHENTICATION_FAILED
                ContractErrorCode.DEVICE_REVOKED -> FORBIDDEN
                else -> try {
                    valueOf(contractCode.name)
                } catch (_: IllegalArgumentException) {
                    UNKNOWN
                }
            }
        }

        /**
         * Resolve an [OctomilErrorCode] from a server error response.
         *
         * Prefers the wire-format `code` string (e.g. "model_not_found") when
         * available, falling back to HTTP status code mapping.
         *
         * @param code The optional error code string from the server response body.
         * @param httpStatus The HTTP status code from the response.
         */
        fun fromServerResponse(code: String?, httpStatus: Int): OctomilErrorCode {
            if (code != null) {
                val resolved = fromContractCode(code)
                if (resolved != UNKNOWN) return resolved
            }
            return fromHttpStatus(httpStatus)
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
    /** Whether the operation that produced this error is safe to retry. */
    val retryable: Boolean get() = errorCode.retryable

    /** The error category from the contract taxonomy. */
    val category: ErrorCategory get() = errorCode.category

    /** The retry classification from the contract taxonomy. */
    val retryClass: RetryClass get() = errorCode.retryClass

    /** Whether this error is eligible for cloud fallback. */
    val fallbackEligible: Boolean get() = errorCode.fallbackEligible

    /** The suggested remediation action from the contract taxonomy. */
    val suggestedAction: SuggestedAction get() = errorCode.suggestedAction

    override fun toString(): String =
        "OctomilException(code=$errorCode, retryable=$retryable, message=$message)"
}
