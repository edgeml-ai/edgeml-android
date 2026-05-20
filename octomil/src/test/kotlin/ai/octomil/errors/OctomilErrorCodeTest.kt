package ai.octomil.errors

import ai.octomil.generated.ErrorCode as ContractErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OctomilErrorCodeTest {

    // Internal/server-ops codes intentionally omitted from the SDK enum.
    // They are emitted by management APIs, not by SDK call paths.
    private val internalOnlyCodes: Set<ContractErrorCode> = setOf(
        ContractErrorCode.INCIDENT_NOT_FOUND,
        ContractErrorCode.DEPLOYMENT_NOT_FOUND,
        ContractErrorCode.EXPERIMENT_NOT_FOUND,
        ContractErrorCode.EXPERIMENT_STATE_INVALID,
        ContractErrorCode.API_KEY_NOT_FOUND,
        ContractErrorCode.API_KEY_ALREADY_REVOKED,
        ContractErrorCode.INTEGRATION_NOT_FOUND,
        ContractErrorCode.BILLING_CUSTOMER_NOT_FOUND,
        ContractErrorCode.ACTION_NOT_FOUND,
        ContractErrorCode.ACTION_STATE_INVALID,
    )

    // =========================================================================
    // Enum completeness
    // =========================================================================

    @Test
    fun `SDK error code count matches generated contract aliases`() {
        assertEquals(expectedSdkCodes().size, OctomilErrorCode.entries.size)
    }

    @Test
    fun `all generated contract codes map to SDK codes`() {
        val actual = OctomilErrorCode.entries.toSet()
        assertEquals(expectedSdkCodes(), actual)
    }

    @Test
    fun `internal-only contract codes map to UNKNOWN`() {
        for (contractCode in internalOnlyCodes) {
            assertEquals(
                "Expected ${contractCode.name} to map to UNKNOWN",
                OctomilErrorCode.UNKNOWN,
                OctomilErrorCode.fromContractCode(contractCode.code),
            )
        }
    }

    private fun expectedSdkCodes(): Set<OctomilErrorCode> =
        ContractErrorCode.entries
            .filter { it !in internalOnlyCodes }
            .map { contractCode ->
                when (contractCode) {
                    ContractErrorCode.TOKEN_EXPIRED -> OctomilErrorCode.AUTHENTICATION_FAILED
                    ContractErrorCode.DEVICE_REVOKED -> OctomilErrorCode.FORBIDDEN
                    else -> OctomilErrorCode.valueOf(contractCode.name)
                }
            }.toSet()

    // =========================================================================
    // Retryable flags
    // =========================================================================

    @Test
    fun `retryable codes are marked correctly`() {
        // Derived from contract RetryClass != NEVER. Cross-check against
        // the generated ContractErrorCode rather than hardcoding each code.
        for (code in OctomilErrorCode.entries) {
            val contractCode = ai.octomil.generated.ErrorCode.fromCode(code.name.lowercase())
                ?: ai.octomil.generated.ErrorCode.UNKNOWN
            val expectedRetryable = contractCode.retryClass != ai.octomil.generated.RetryClass.NEVER
            assertEquals(
                "$code: retryable should be $expectedRetryable (retryClass=${contractCode.retryClass})",
                expectedRetryable,
                code.retryable,
            )
        }
    }

    // =========================================================================
    // New codes added in contract sync (v1.25.0)
    // =========================================================================

    @Test
    fun `new auth codes round-trip through fromContractCode`() {
        assertEquals(OctomilErrorCode.INSUFFICIENT_SCOPE, OctomilErrorCode.fromContractCode("insufficient_scope"))
        assertEquals(OctomilErrorCode.MISSING_ORG_CONTEXT, OctomilErrorCode.fromContractCode("missing_org_context"))
    }

    @Test
    fun `new catalog codes round-trip through fromContractCode`() {
        assertEquals(OctomilErrorCode.NO_DEFAULT_MODEL, OctomilErrorCode.fromContractCode("no_default_model"))
        assertEquals(OctomilErrorCode.CAPABILITY_NOT_SUPPORTED, OctomilErrorCode.fromContractCode("capability_not_supported"))
        assertEquals(OctomilErrorCode.PREVIOUS_RESPONSE_NOT_FOUND, OctomilErrorCode.fromContractCode("previous_response_not_found"))
        assertEquals(OctomilErrorCode.APP_NOT_FOUND, OctomilErrorCode.fromContractCode("app_not_found"))
        assertEquals(OctomilErrorCode.CAPABILITY_NOT_CONFIGURED, OctomilErrorCode.fromContractCode("capability_not_configured"))
        assertEquals(OctomilErrorCode.APP_CONTEXT_CONFLICT, OctomilErrorCode.fromContractCode("app_context_conflict"))
        assertEquals(OctomilErrorCode.INVALID_MODEL_REF, OctomilErrorCode.fromContractCode("invalid_model_ref"))
    }

    @Test
    fun `new runtime codes round-trip through fromContractCode`() {
        assertEquals(OctomilErrorCode.PROVIDER_ERROR, OctomilErrorCode.fromContractCode("provider_error"))
        assertEquals(OctomilErrorCode.UPSTREAM_PROVIDER_ERROR, OctomilErrorCode.fromContractCode("upstream_provider_error"))
        assertEquals(OctomilErrorCode.TOO_MANY_TOOLS, OctomilErrorCode.fromContractCode("too_many_tools"))
        assertEquals(OctomilErrorCode.UNSUPPORTED_TOOL_CALLING, OctomilErrorCode.fromContractCode("unsupported_tool_calling"))
    }

    @Test
    fun `new policy codes round-trip through fromContractCode`() {
        assertEquals(OctomilErrorCode.CLOUD_INFERENCE_NOT_ALLOWED, OctomilErrorCode.fromContractCode("cloud_inference_not_allowed"))
        assertEquals(OctomilErrorCode.HOSTED_TTS_DISABLED, OctomilErrorCode.fromContractCode("hosted_tts_disabled"))
        assertEquals(OctomilErrorCode.PLAN_LIMIT_EXCEEDED, OctomilErrorCode.fromContractCode("plan_limit_exceeded"))
    }

    @Test
    fun `new codes retryable matches catalog`() {
        // Non-retryable: auth, policy, and most catalog codes (RetryClass.NEVER)
        assertFalse(OctomilErrorCode.INSUFFICIENT_SCOPE.retryable)
        assertFalse(OctomilErrorCode.MISSING_ORG_CONTEXT.retryable)
        assertFalse(OctomilErrorCode.NO_DEFAULT_MODEL.retryable)
        assertFalse(OctomilErrorCode.CLOUD_INFERENCE_NOT_ALLOWED.retryable)
        assertFalse(OctomilErrorCode.HOSTED_TTS_DISABLED.retryable)
        assertFalse(OctomilErrorCode.PLAN_LIMIT_EXCEEDED.retryable)
        assertFalse(OctomilErrorCode.TOO_MANY_TOOLS.retryable)
        // PROVIDER_ERROR is RetryClass.NEVER despite fallbackEligible=true
        assertFalse(OctomilErrorCode.PROVIDER_ERROR.retryable)
        // UNSUPPORTED_TOOL_CALLING is RetryClass.NEVER
        assertFalse(OctomilErrorCode.UNSUPPORTED_TOOL_CALLING.retryable)
        // CAPABILITY_NOT_SUPPORTED is RetryClass.NEVER
        assertFalse(OctomilErrorCode.CAPABILITY_NOT_SUPPORTED.retryable)
        // Only UPSTREAM_PROVIDER_ERROR is BACKOFF_SAFE (retryable)
        assertTrue(OctomilErrorCode.UPSTREAM_PROVIDER_ERROR.retryable)
    }

    // =========================================================================
    // HTTP status mapping
    // =========================================================================

    @Test
    fun `fromHttpStatus maps 400 to INVALID_INPUT`() {
        assertEquals(OctomilErrorCode.INVALID_INPUT, OctomilErrorCode.fromHttpStatus(400))
    }

    @Test
    fun `fromHttpStatus maps 401 to AUTHENTICATION_FAILED`() {
        assertEquals(OctomilErrorCode.AUTHENTICATION_FAILED, OctomilErrorCode.fromHttpStatus(401))
    }

    @Test
    fun `fromHttpStatus maps 403 to FORBIDDEN`() {
        assertEquals(OctomilErrorCode.FORBIDDEN, OctomilErrorCode.fromHttpStatus(403))
    }

    @Test
    fun `fromHttpStatus maps 404 to MODEL_NOT_FOUND`() {
        assertEquals(OctomilErrorCode.MODEL_NOT_FOUND, OctomilErrorCode.fromHttpStatus(404))
    }

    @Test
    fun `fromHttpStatus maps 408 to REQUEST_TIMEOUT`() {
        assertEquals(OctomilErrorCode.REQUEST_TIMEOUT, OctomilErrorCode.fromHttpStatus(408))
    }

    @Test
    fun `fromHttpStatus maps 429 to RATE_LIMITED`() {
        assertEquals(OctomilErrorCode.RATE_LIMITED, OctomilErrorCode.fromHttpStatus(429))
    }

    @Test
    fun `fromHttpStatus maps 500 to SERVER_ERROR`() {
        assertEquals(OctomilErrorCode.SERVER_ERROR, OctomilErrorCode.fromHttpStatus(500))
    }

    @Test
    fun `fromHttpStatus maps 502 to SERVER_ERROR`() {
        assertEquals(OctomilErrorCode.SERVER_ERROR, OctomilErrorCode.fromHttpStatus(502))
    }

    @Test
    fun `fromHttpStatus maps 503 to SERVER_ERROR`() {
        assertEquals(OctomilErrorCode.SERVER_ERROR, OctomilErrorCode.fromHttpStatus(503))
    }

    @Test
    fun `fromHttpStatus maps 599 to SERVER_ERROR`() {
        assertEquals(OctomilErrorCode.SERVER_ERROR, OctomilErrorCode.fromHttpStatus(599))
    }

    @Test
    fun `fromHttpStatus maps unknown 4xx to UNKNOWN`() {
        assertEquals(OctomilErrorCode.UNKNOWN, OctomilErrorCode.fromHttpStatus(418))
        assertEquals(OctomilErrorCode.UNKNOWN, OctomilErrorCode.fromHttpStatus(422))
    }

    @Test
    fun `fromHttpStatus maps 200 to UNKNOWN`() {
        // Non-error codes should not match any specific error
        assertEquals(OctomilErrorCode.UNKNOWN, OctomilErrorCode.fromHttpStatus(200))
    }

    // =========================================================================
    // OctomilException
    // =========================================================================

    @Test
    fun `OctomilException carries error code and message`() {
        val ex = OctomilException(
            errorCode = OctomilErrorCode.RATE_LIMITED,
            message = "Too many requests",
        )
        assertEquals(OctomilErrorCode.RATE_LIMITED, ex.errorCode)
        assertEquals("Too many requests", ex.message)
        assertTrue(ex.retryable)
        assertNull(ex.cause)
        assertNull(ex.retryAfterMs)
    }

    @Test
    fun `OctomilException retryAfterMs is null by default`() {
        val ex = OctomilException(OctomilErrorCode.SERVER_ERROR, "error")
        assertNull(ex.retryAfterMs)
    }

    @Test
    fun `OctomilException retryAfterMs carries Retry-After value when set`() {
        val ex = OctomilException(
            errorCode = OctomilErrorCode.RATE_LIMITED,
            message = "Too many requests",
            retryAfterMs = 5000L,
        )
        assertEquals(5000L, ex.retryAfterMs)
        assertTrue(ex.retryable)
    }

    @Test
    fun `OctomilException retryAfterMs included in toString`() {
        val ex = OctomilException(
            errorCode = OctomilErrorCode.RATE_LIMITED,
            message = "rate limited",
            retryAfterMs = 3000L,
        )
        val str = ex.toString()
        assertTrue(str.contains("retryAfterMs=3000"))
    }

    @Test
    fun `OctomilException retryAfterMs null shown in toString`() {
        val ex = OctomilException(OctomilErrorCode.FORBIDDEN, "forbidden")
        assertTrue(ex.toString().contains("retryAfterMs=null"))
    }

    @Test
    fun `OctomilException carries cause`() {
        val cause = RuntimeException("connection reset")
        val ex = OctomilException(
            errorCode = OctomilErrorCode.NETWORK_UNAVAILABLE,
            message = "Network error",
            cause = cause,
        )
        assertEquals(cause, ex.cause)
        assertTrue(ex.retryable)
    }

    @Test
    fun `OctomilException non-retryable`() {
        val ex = OctomilException(
            errorCode = OctomilErrorCode.INVALID_API_KEY,
            message = "Bad key",
        )
        assertFalse(ex.retryable)
    }

    @Test
    fun `OctomilException toString includes relevant info`() {
        val ex = OctomilException(
            errorCode = OctomilErrorCode.FORBIDDEN,
            message = "Access denied",
        )
        val str = ex.toString()
        assertTrue(str.contains("FORBIDDEN"))
        assertTrue(str.contains("retryable=false"))
        assertTrue(str.contains("Access denied"))
    }

    @Test
    fun `OctomilException is an Exception`() {
        val ex = OctomilException(
            errorCode = OctomilErrorCode.UNKNOWN,
            message = "something went wrong",
        )
        // Verify it's catchable as Exception
        assertTrue(ex is Exception)
        assertNotNull(ex.message)
    }

    @Test
    fun `retryable property delegates to error code`() {
        for (code in OctomilErrorCode.entries) {
            val ex = OctomilException(code, "test")
            assertEquals(
                "Retryable mismatch for $code",
                code.retryable,
                ex.retryable,
            )
        }
    }

    // =========================================================================
    // fromContractCode
    // =========================================================================

    @Test
    fun `fromContractCode maps known code string`() {
        assertEquals(OctomilErrorCode.MODEL_NOT_FOUND, OctomilErrorCode.fromContractCode("model_not_found"))
    }

    @Test
    fun `fromContractCode returns UNKNOWN for unrecognised code`() {
        assertEquals(OctomilErrorCode.UNKNOWN, OctomilErrorCode.fromContractCode("totally_bogus_code"))
    }

    // =========================================================================
    // fromServerResponse
    // =========================================================================

    @Test
    fun `fromServerResponse prefers code over HTTP status`() {
        // code=rate_limited should win even though HTTP 500 would map to SERVER_ERROR
        assertEquals(
            OctomilErrorCode.RATE_LIMITED,
            OctomilErrorCode.fromServerResponse("rate_limited", 500),
        )
    }

    @Test
    fun `fromServerResponse falls back to HTTP status when code is null`() {
        assertEquals(
            OctomilErrorCode.FORBIDDEN,
            OctomilErrorCode.fromServerResponse(null, 403),
        )
    }

    @Test
    fun `fromServerResponse falls back to HTTP status when code is unrecognised`() {
        assertEquals(
            OctomilErrorCode.SERVER_ERROR,
            OctomilErrorCode.fromServerResponse("not_a_real_code", 502),
        )
    }
}
