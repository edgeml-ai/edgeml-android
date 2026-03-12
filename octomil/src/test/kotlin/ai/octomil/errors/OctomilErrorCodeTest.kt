package ai.octomil.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OctomilErrorCodeTest {

    // =========================================================================
    // Enum completeness
    // =========================================================================

    @Test
    fun `has exactly 19 canonical error codes`() {
        assertEquals(19, OctomilErrorCode.entries.size)
    }

    @Test
    fun `all expected codes are present`() {
        val expected = setOf(
            "NETWORK_UNAVAILABLE",
            "REQUEST_TIMEOUT",
            "SERVER_ERROR",
            "INVALID_API_KEY",
            "AUTHENTICATION_FAILED",
            "FORBIDDEN",
            "MODEL_NOT_FOUND",
            "MODEL_DISABLED",
            "DOWNLOAD_FAILED",
            "CHECKSUM_MISMATCH",
            "INSUFFICIENT_STORAGE",
            "RUNTIME_UNAVAILABLE",
            "MODEL_LOAD_FAILED",
            "INFERENCE_FAILED",
            "INSUFFICIENT_MEMORY",
            "RATE_LIMITED",
            "INVALID_INPUT",
            "CANCELLED",
            "UNKNOWN",
        )
        val actual = OctomilErrorCode.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    // =========================================================================
    // Retryable flags
    // =========================================================================

    @Test
    fun `retryable codes are marked correctly`() {
        val retryable = setOf(
            OctomilErrorCode.NETWORK_UNAVAILABLE,
            OctomilErrorCode.REQUEST_TIMEOUT,
            OctomilErrorCode.SERVER_ERROR,
            OctomilErrorCode.DOWNLOAD_FAILED,
            OctomilErrorCode.CHECKSUM_MISMATCH,
            OctomilErrorCode.INFERENCE_FAILED,
            OctomilErrorCode.RATE_LIMITED,
        )
        for (code in OctomilErrorCode.entries) {
            if (code in retryable) {
                assertTrue("$code should be retryable", code.retryable)
            } else {
                assertFalse("$code should not be retryable", code.retryable)
            }
        }
    }

    // =========================================================================
    // HTTP status mapping
    // =========================================================================

    @Test
    fun `fromHttpStatus maps 401 to INVALID_API_KEY`() {
        assertEquals(OctomilErrorCode.INVALID_API_KEY, OctomilErrorCode.fromHttpStatus(401))
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
}
