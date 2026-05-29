package ai.octomil.errors

import ai.octomil.generated.ErrorCategory
import ai.octomil.generated.ErrorCode
import ai.octomil.generated.RetryClass
import ai.octomil.generated.SuggestedAction

/**
 * Public Octomil error code — an alias for the generated contract [ErrorCode]
 * taxonomy, 1:1.
 *
 * Previously this was a hand-curated 53-code subset of the contract, with two
 * collapsing aliases (TOKEN_EXPIRED→AUTHENTICATION_FAILED,
 * DEVICE_REVOKED→FORBIDDEN) and ~10 "internal-only" codes mapped to UNKNOWN.
 * That design drifted from the contract (android fell three minor versions
 * behind, 1.26.0 vs 1.29.0), required a manual classification decision for every
 * new contract code, and was LOSSY — a device that received e.g.
 * `agent_system_unavailable` got UNKNOWN, losing diagnostic signal.
 *
 * It is now an alias for the full generated [ErrorCode], matching the other
 * SDKs (python exposes `ErrorCode as OctomilErrorCode`). Benefits:
 *   - Cross-SDK parity (the whole point of the contract).
 *   - Zero drift, zero manual per-code classification — it regenerates with the
 *     contract.
 *   - Lossless: every wire code maps to itself.
 *
 * Error metadata ([category], [retryClass], [fallbackEligible],
 * [suggestedAction]) is carried directly on the generated enum; [retryable] is
 * the convenience below.
 */
typealias OctomilErrorCode = ErrorCode

/** Whether the operation that produced this error is safe to retry. */
val OctomilErrorCode.retryable: Boolean
    get() = retryClass != RetryClass.NEVER

/**
 * Map an HTTP status code to the most appropriate [OctomilErrorCode].
 *
 * Lossy fallback — prefer [fromServerResponse], which uses the wire-format
 * `code` field when available.
 */
fun OctomilErrorCode.Companion.fromHttpStatus(code: Int): OctomilErrorCode = when (code) {
    400 -> ErrorCode.INVALID_INPUT
    401 -> ErrorCode.AUTHENTICATION_FAILED
    403 -> ErrorCode.FORBIDDEN
    404 -> ErrorCode.MODEL_NOT_FOUND
    408 -> ErrorCode.REQUEST_TIMEOUT
    429 -> ErrorCode.RATE_LIMITED
    in 500..599 -> ErrorCode.SERVER_ERROR
    else -> ErrorCode.UNKNOWN
}

/**
 * Map a wire-format error code string (e.g. "model_not_found") to its
 * [OctomilErrorCode]. Unrecognised codes fall back to [ErrorCode.UNKNOWN].
 */
fun OctomilErrorCode.Companion.fromContractCode(code: String): OctomilErrorCode =
    ErrorCode.fromCode(code) ?: ErrorCode.UNKNOWN

/**
 * Resolve an [OctomilErrorCode] from a server error response: prefer the
 * wire-format `code` string, falling back to HTTP status code mapping.
 */
fun OctomilErrorCode.Companion.fromServerResponse(code: String?, httpStatus: Int): OctomilErrorCode {
    if (code != null) {
        val resolved = fromContractCode(code)
        if (resolved != ErrorCode.UNKNOWN) return resolved
    }
    return fromHttpStatus(httpStatus)
}

/**
 * Unified exception for all Octomil SDK errors.
 *
 * Wraps an [OctomilErrorCode] with a human-readable message and optional cause.
 * The [retryable] convenience property delegates to the error code so callers
 * can decide whether to retry without inspecting the enum directly.
 *
 * @param retryAfterMs Milliseconds the caller should wait before retrying, derived
 *   from the server `Retry-After` header when present. Only set for transient
 *   errors such as [OctomilErrorCode.RATE_LIMITED]; `null` when the server did not
 *   specify a delay. Mirrors `retry_after_ms` on the Python SDK's `OctomilError`.
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
