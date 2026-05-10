package ai.octomil.conformance

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.generated.CompatibilityLevel
import ai.octomil.generated.DeviceClass
import ai.octomil.generated.ErrorCode as ContractErrorCode
import ai.octomil.generated.FinishReason
import ai.octomil.generated.ModelStatus as ContractModelStatus
import ai.octomil.generated.OtlpResourceAttribute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformance tests validating that the Android SDK matches the
 * octomil-contracts specification.
 *
 * These tests verify:
 * 1. Generated enums contain the expected values from the contract YAML
 * 2. SDK error codes are 1:1 with generated contract error codes
 * 3. Wire-format error code strings round-trip correctly
 * 4. Telemetry event constants match the contract
 * 5. Fixture-driven error deserialization behaves per contract rules
 */
class ContractConformanceTest {

    // =========================================================================
    // ErrorCode enum — 39 canonical codes
    //
    // Counts and ordering must track octomil-contracts/enums/error_code.yaml
    // at the version pinned in `_generated/.contract-version.json`. When
    // contracts add codes, regenerate `generated/ErrorCode.kt` first, then
    // mirror the new entries here in canonical (YAML) order.
    // =========================================================================

    @Test
    fun `generated ErrorCode has exactly 39 entries`() {
        assertEquals(39, ContractErrorCode.entries.size)
    }

    @Test
    fun `generated ErrorCode contains all canonical codes`() {
        val expected = listOf(
            "invalid_api_key",
            "authentication_failed",
            "forbidden",
            "device_not_registered",
            "token_expired",
            "device_revoked",
            "network_unavailable",
            "request_timeout",
            "server_error",
            "rate_limited",
            "invalid_input",
            "unsupported_modality",
            "context_too_large",
            "model_not_found",
            "model_disabled",
            "version_not_found",
            "download_failed",
            "checksum_mismatch",
            "insufficient_storage",
            "insufficient_memory",
            "runtime_unavailable",
            "accelerator_unavailable",
            "model_load_failed",
            "inference_failed",
            "stream_interrupted",
            "policy_denied",
            "cloud_fallback_disallowed",
            // Added in contracts 1.23.0 (cloud-credentials taxonomy).
            "cloud_credentials_missing",
            "cloud_credentials_revoked",
            "cloud_provider_auth_failed",
            "max_tool_rounds_exceeded",
            "training_failed",
            "training_not_supported",
            "weight_upload_failed",
            "control_sync_failed",
            "assignment_not_found",
            "cancelled",
            "app_backgrounded",
            "unknown",
        )
        val actual = ContractErrorCode.entries.map { it.code }
        assertEquals(expected, actual)
    }

    @Test
    fun `every generated ErrorCode maps to a valid OctomilErrorCode`() {
        // 37 SDK codes map to 39 contract codes: TOKEN_EXPIRED -> AUTHENTICATION_FAILED,
        // DEVICE_REVOKED -> FORBIDDEN. Verify every contract code resolves to a non-UNKNOWN
        // SDK code (or to an expected alias). The cloud_* codes added in
        // contracts 1.23.0 have direct SDK equivalents so they don't need aliasing.
        val aliasMap = mapOf(
            "TOKEN_EXPIRED" to "AUTHENTICATION_FAILED",
            "DEVICE_REVOKED" to "FORBIDDEN",
        )
        for (contractCode in ContractErrorCode.entries) {
            val sdkCode = OctomilErrorCode.fromContractCode(contractCode.code)
            val expectedName = aliasMap[contractCode.name] ?: contractCode.name
            assertEquals(
                "Contract code '${contractCode.code}' should map to SDK code $expectedName",
                expectedName,
                sdkCode.name,
            )
        }
    }

    @Test
    fun `OctomilErrorCode covers all contract codes`() {
        // SDK has 37 entries; contract has 39 (TOKEN_EXPIRED and DEVICE_REVOKED
        // are aliased to AUTHENTICATION_FAILED and FORBIDDEN respectively).
        // Counts moved from 34/36 -> 37/39 with contracts 1.23.0 (added
        // CLOUD_CREDENTIALS_MISSING/REVOKED + CLOUD_PROVIDER_AUTH_FAILED).
        assertEquals(37, OctomilErrorCode.entries.size)
        assertEquals(39, ContractErrorCode.entries.size)
        // Every contract code must resolve to a non-UNKNOWN SDK code
        for (contractCode in ContractErrorCode.entries) {
            if (contractCode == ContractErrorCode.UNKNOWN) continue
            val sdkCode = OctomilErrorCode.fromContractCode(contractCode.code)
            assertTrue(
                "Contract code '${contractCode.code}' should not fall back to UNKNOWN",
                sdkCode != OctomilErrorCode.UNKNOWN,
            )
        }
    }

    // =========================================================================
    // Fixture: errors/model_not_found.json
    // =========================================================================

    @Test
    fun `fixture model_not_found — code parses to MODEL_NOT_FOUND`() {
        // From fixtures/errors/model_not_found.json: code = "model_not_found"
        val code = OctomilErrorCode.fromContractCode("model_not_found")
        assertEquals(OctomilErrorCode.MODEL_NOT_FOUND, code)
        assertEquals(false, code.retryable)
    }

    // =========================================================================
    // Fixture: errors/rate_limited.json
    // =========================================================================

    @Test
    fun `fixture rate_limited — code parses to RATE_LIMITED and is retryable`() {
        // From fixtures/errors/rate_limited.json: code = "rate_limited", retryable = true
        val code = OctomilErrorCode.fromContractCode("rate_limited")
        assertEquals(OctomilErrorCode.RATE_LIMITED, code)
        assertEquals(true, code.retryable)
    }

    // =========================================================================
    // Fixture: errors/inference_failed.json
    // =========================================================================

    @Test
    fun `fixture inference_failed — code parses to INFERENCE_FAILED and is retryable`() {
        // From fixtures/errors/inference_failed.json: code = "inference_failed", retryable = true
        val code = OctomilErrorCode.fromContractCode("inference_failed")
        assertEquals(OctomilErrorCode.INFERENCE_FAILED, code)
        assertEquals(true, code.retryable)
    }

    // =========================================================================
    // Fixture: errors/unknown_error_fallback.json
    // =========================================================================

    @Test
    fun `fixture unknown_error_fallback — unrecognised code falls back to UNKNOWN`() {
        // From fixtures/errors/unknown_error_fallback.json:
        // input.code = "some_future_error_code" -> expected.code = "unknown"
        val code = OctomilErrorCode.fromContractCode("some_future_error_code")
        assertEquals(OctomilErrorCode.UNKNOWN, code)
        assertEquals(false, code.retryable)
    }

    @Test
    fun `empty string falls back to UNKNOWN`() {
        val code = OctomilErrorCode.fromContractCode("")
        assertEquals(OctomilErrorCode.UNKNOWN, code)
    }

    // =========================================================================
    // ErrorCode.fromCode round-trip
    // =========================================================================

    @Test
    fun `ErrorCode fromCode resolves all known codes`() {
        for (entry in ContractErrorCode.entries) {
            val resolved = ContractErrorCode.fromCode(entry.code)
            assertNotNull("fromCode should resolve '${entry.code}'", resolved)
            assertEquals(entry, resolved)
        }
    }

    @Test
    fun `ErrorCode fromCode returns null for unknown code`() {
        assertNull(ContractErrorCode.fromCode("nonexistent_code"))
    }


    // =========================================================================
    // ModelStatus — 4 canonical statuses
    //
    // Contracts 1.23.0 dropped the `queued` lifecycle state and renamed
    // `failed` -> `error` to align with octomil-contracts/enums/model_status.yaml.
    // =========================================================================

    @Test
    fun `generated ModelStatus has exactly 4 entries`() {
        assertEquals(4, ContractModelStatus.entries.size)
    }

    @Test
    fun `generated ModelStatus contains all canonical codes`() {
        val expected = listOf("not_cached", "downloading", "ready", "error")
        val actual = ContractModelStatus.entries.map { it.code }
        assertEquals(expected, actual)
    }

    @Test
    fun `ModelStatus fromCode round-trips`() {
        for (entry in ContractModelStatus.entries) {
            assertEquals(entry, ContractModelStatus.fromCode(entry.code))
        }
    }

    // =========================================================================
    // DeviceClass — 4 canonical classes
    // =========================================================================

    @Test
    fun `generated DeviceClass has exactly 4 entries`() {
        assertEquals(4, DeviceClass.entries.size)
    }

    @Test
    fun `generated DeviceClass contains all canonical codes`() {
        val expected = listOf("flagship", "high", "mid", "low")
        val actual = DeviceClass.entries.map { it.code }
        assertEquals(expected, actual)
    }

    // =========================================================================
    // FinishReason — 4 canonical reasons
    // =========================================================================

    @Test
    fun `generated FinishReason has exactly 4 entries`() {
        assertEquals(4, FinishReason.entries.size)
    }

    @Test
    fun `generated FinishReason contains all canonical codes`() {
        val expected = listOf("stop", "tool_calls", "length", "content_filter")
        val actual = FinishReason.entries.map { it.code }
        assertEquals(expected, actual)
    }

    // =========================================================================
    // CompatibilityLevel — 4 canonical levels
    // =========================================================================

    @Test
    fun `generated CompatibilityLevel has exactly 4 entries`() {
        assertEquals(4, CompatibilityLevel.entries.size)
    }

    @Test
    fun `generated CompatibilityLevel contains all canonical codes`() {
        val expected = listOf("stable", "beta", "experimental", "compatibility")
        val actual = CompatibilityLevel.entries.map { it.code }
        assertEquals(expected, actual)
    }

    // =========================================================================
    // OtlpResourceAttribute — 6 canonical keys
    // =========================================================================

    @Test
    fun `OtlpResourceAttribute contains all canonical keys`() {
        assertEquals("service.name", OtlpResourceAttribute.SERVICE_NAME)
        assertEquals("service.version", OtlpResourceAttribute.SERVICE_VERSION)
        // OCTOMIL_SDK and OS_TYPE were removed from generated code — keys renamed
        assertEquals("octomil.org.id", OtlpResourceAttribute.OCTOMIL_ORG_ID)
        assertEquals("octomil.device.id", OtlpResourceAttribute.OCTOMIL_DEVICE_ID)
    }
}
