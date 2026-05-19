package ai.octomil.errors

import ai.octomil.generated.ErrorCategory
import ai.octomil.generated.ErrorCode as ContractErrorCode
import ai.octomil.generated.RetryClass
import ai.octomil.generated.SuggestedAction

/**
 * Canonical error codes matching the octomil-contracts error taxonomy.
 * 53 SDK-facing codes derived from 65 contract codes.
 *
 * Collapsing rules:
 *   - TOKEN_EXPIRED      → AUTHENTICATION_FAILED  (both require re-auth)
 *   - DEVICE_REVOKED     → FORBIDDEN              (device-level access denial)
 *
 * Intentionally omitted (10 internal/server-ops codes — not user-visible):
 *   INCIDENT_NOT_FOUND, DEPLOYMENT_NOT_FOUND, EXPERIMENT_NOT_FOUND,
 *   EXPERIMENT_STATE_INVALID, API_KEY_NOT_FOUND, API_KEY_ALREADY_REVOKED,
 *   INTEGRATION_NOT_FOUND, BILLING_CUSTOMER_NOT_FOUND,
 *   ACTION_NOT_FOUND, ACTION_STATE_INVALID.
 *   These codes are emitted by server-side management APIs, not SDK call paths.
 *   If the SDK ever wraps those endpoints, promote the relevant codes here.
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
    // Auth
    INVALID_API_KEY,
    AUTHENTICATION_FAILED,
    FORBIDDEN,
    INSUFFICIENT_SCOPE,
    MISSING_ORG_CONTEXT,
    DEVICE_NOT_REGISTERED,
    CLOUD_CREDENTIALS_MISSING,
    CLOUD_CREDENTIALS_REVOKED,
    CLOUD_PROVIDER_AUTH_FAILED,
    // Network
    NETWORK_UNAVAILABLE,
    REQUEST_TIMEOUT,
    SERVER_ERROR,
    RATE_LIMITED,
    // Input
    INVALID_INPUT,
    UNSUPPORTED_MODALITY,
    CONTEXT_TOO_LARGE,
    // Catalog
    MODEL_NOT_FOUND,
    NO_DEFAULT_MODEL,
    CAPABILITY_NOT_SUPPORTED,
    PREVIOUS_RESPONSE_NOT_FOUND,
    APP_NOT_FOUND,
    CAPABILITY_NOT_CONFIGURED,
    APP_CONTEXT_CONFLICT,
    INVALID_MODEL_REF,
    MODEL_DISABLED,
    VERSION_NOT_FOUND,
    // Download
    DOWNLOAD_FAILED,
    CHECKSUM_MISMATCH,
    // Device
    INSUFFICIENT_STORAGE,
    INSUFFICIENT_MEMORY,
    RUNTIME_UNAVAILABLE,
    ACCELERATOR_UNAVAILABLE,
    // Runtime
    MODEL_LOAD_FAILED,
    INFERENCE_FAILED,
    PROVIDER_ERROR,
    UPSTREAM_PROVIDER_ERROR,
    TOO_MANY_TOOLS,
    UNSUPPORTED_TOOL_CALLING,
    STREAM_INTERRUPTED,
    // Policy
    POLICY_DENIED,
    CLOUD_FALLBACK_DISALLOWED,
    CLOUD_INFERENCE_NOT_ALLOWED,
    HOSTED_TTS_DISABLED,
    PLAN_LIMIT_EXCEEDED,
    MAX_TOOL_ROUNDS_EXCEEDED,
    // Training
    TRAINING_FAILED,
    TRAINING_NOT_SUPPORTED,
    WEIGHT_UPLOAD_FAILED,
    // Control
    CONTROL_SYNC_FAILED,
    ASSIGNMENT_NOT_FOUND,
    // Lifecycle
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
 *
 * @param retryAfterMs Milliseconds the caller should wait before retrying, derived
 *   from the server `Retry-After` header when present. Only set for [OctomilErrorCode.RATE_LIMITED]
 *   and similar transient errors; `null` when the server did not specify a delay.
 *   Mirrors the `retry_after_ms` field on the Python SDK's `OctomilError`.
 */
open class OctomilException(
    val errorCode: OctomilErrorCode,
    message: String,
    cause: Throwable? = null,
    val retryAfterMs: Long? = null,
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
        "OctomilException(code=$errorCode, retryable=$retryable, retryAfterMs=$retryAfterMs, message=$message)"
}
