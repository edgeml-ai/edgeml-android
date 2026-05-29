package ai.octomil.conformance

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.fromContractCode
import ai.octomil.errors.retryable
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
 * AUTO-GENERATED — do not edit.
 *
 * Source contracts:
 *   enums/error_code.yaml, enums/model_status.yaml, enums/device_class.yaml,
 *   enums/finish_reason.yaml, enums/compatibility_level.yaml,
 *   telemetry/resource_attributes.yaml, fixtures/errors/
 * Conformance version: 0.1.5-rc1
 * Generator: scripts/generate_conformance.py (target=kotlin)
 *
 * Regenerated from contracts YAML; expected values are injected at code-gen
 * time from YAML source (fixes Codex B1-class finding: oracle reads
 * contract-source, not SDK-self-reference).
 */
class ContractConformanceTest {

    // =========================================================================
    // ErrorCode enum — 101 canonical codes (from enums/error_code.yaml)
    // =========================================================================

    @Test
    fun `generated ErrorCode has exactly 101 entries`() {
        assertEquals(101, ContractErrorCode.entries.size)
    }

    @Test
    fun `generated ErrorCode contains all canonical codes`() {
        val expected = listOf(
            "invalid_api_key",
            "authentication_failed",
            "forbidden",
            "insufficient_scope",
            "missing_org_context",
            "device_not_registered",
            "token_expired",
            "device_revoked",
            "passkey_challenge_expired",
            "passkey_credential_not_found",
            "invalid_token",
            "email_already_verified",
            "email_already_in_use",
            "last_auth_method",
            "oauth_provider_not_linked",
            "network_unavailable",
            "request_timeout",
            "server_error",
            "rate_limited",
            "invalid_input",
            "unsupported_modality",
            "context_too_large",
            "model_not_found",
            "no_default_model",
            "capability_not_supported",
            "previous_response_not_found",
            "app_not_found",
            "capability_not_configured",
            "app_context_conflict",
            "invalid_model_ref",
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
            "provider_error",
            "upstream_provider_error",
            "too_many_tools",
            "unsupported_tool_calling",
            "stream_interrupted",
            "policy_denied",
            "cloud_fallback_disallowed",
            "cloud_inference_not_allowed",
            "hosted_tts_disabled",
            "plan_limit_exceeded",
            "cloud_credentials_missing",
            "cloud_credentials_revoked",
            "cloud_provider_auth_failed",
            "max_tool_rounds_exceeded",
            "training_failed",
            "training_not_supported",
            "weight_upload_failed",
            "control_sync_failed",
            "assignment_not_found",
            "incident_not_found",
            "alert_rule_not_found",
            "deployment_not_found",
            "experiment_not_found",
            "experiment_state_invalid",
            "api_key_not_found",
            "api_key_already_revoked",
            "integration_not_found",
            "billing_customer_not_found",
            "action_not_found",
            "action_state_invalid",
            "credential_not_found",
            "connection_not_found",
            "local_runtime_not_found",
            "checkout_not_complete",
            "upstream_provider_unavailable",
            "agent_system_unavailable",
            "thread_not_found",
            "run_not_found",
            "run_state_invalid",
            "approval_not_found",
            "approval_already_resolved",
            "job_not_found",
            "job_state_invalid",
            "cancelled",
            "app_backgrounded",
            "resource_not_found",
            "catalog_family_not_found",
            "catalog_variant_not_found",
            "catalog_version_not_found",
            "catalog_package_not_found",
            "catalog_resource_not_found",
            "catalog_slug_conflict",
            "catalog_lifecycle_invalid",
            "billing_export_not_found",
            "cloud_catalog_source_not_found",
            "cloud_catalog_mapping_not_found",
            "cloud_catalog_run_not_found",
            "conflict",
            "gone",
            "payload_too_large",
            "unknown",
        )
        val actual = ContractErrorCode.entries.map { it.code }
        assertEquals(expected, actual)
    }

    @Test
    fun `every generated ErrorCode maps to a valid OctomilErrorCode`() {
        // 101 direct mappings + 0 aliases:
        //   
        val aliasMap = emptyMap<String, String>()
        for (contractCode in ContractErrorCode.entries) {
            val sdkCode = OctomilErrorCode.fromContractCode(contractCode.code)
            val expectedName = aliasMap[contractCode.name] ?: contractCode.name
            assertEquals(
                "Contract code '${contractCode.code}' should map to SDK code ${expectedName}",
                expectedName,
                sdkCode.name,
            )
        }
    }

    @Test
    fun `OctomilErrorCode covers all contract codes`() {
        // SDK has 101 entries; contract has 101.
        assertEquals(101, OctomilErrorCode.entries.size)
        assertEquals(101, ContractErrorCode.entries.size)
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
    // Fixture-driven error deserialization (from fixtures/errors/)
    // =========================================================================

    @Test
    fun `fixture model_not_found — code parses correctly`() {
        // Source: fixtures/errors/model_not_found.json — input.code = "model_not_found"
        val code = OctomilErrorCode.fromContractCode("model_not_found")
        assertEquals(OctomilErrorCode.MODEL_NOT_FOUND, code)
        assertEquals(false, code.retryable)
    }

    @Test
    fun `fixture rate_limited — code parses correctly`() {
        // Source: fixtures/errors/rate_limited.json — input.code = "rate_limited"
        val code = OctomilErrorCode.fromContractCode("rate_limited")
        assertEquals(OctomilErrorCode.RATE_LIMITED, code)
        assertEquals(true, code.retryable)
    }

    @Test
    fun `fixture inference_failed — code parses correctly`() {
        // Source: fixtures/errors/inference_failed.json — input.code = "inference_failed"
        val code = OctomilErrorCode.fromContractCode("inference_failed")
        assertEquals(OctomilErrorCode.INFERENCE_FAILED, code)
        assertEquals(true, code.retryable)
    }

    @Test
    fun `fixture unknown_error_fallback — unrecognised code falls back to UNKNOWN`() {
        // Source: fixtures/errors/unknown_error_fallback.json — input.code = "some_future_error_code"
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
    // ModelStatus — 4 canonical statuses (from enums/model_status.yaml)
    // =========================================================================

    @Test
    fun `generated ModelStatus has exactly 4 entries`() {
        assertEquals(4, ContractModelStatus.entries.size)
    }

    @Test
    fun `generated ModelStatus contains all canonical codes`() {
        val expected = listOf(
            "not_cached",
            "downloading",
            "ready",
            "error",
        )
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
    // DeviceClass — 4 canonical classes (from enums/device_class.yaml)
    // =========================================================================

    @Test
    fun `generated DeviceClass has exactly 4 entries`() {
        assertEquals(4, DeviceClass.entries.size)
    }

    @Test
    fun `generated DeviceClass contains all canonical codes`() {
        val expected = listOf(
            "flagship",
            "high",
            "mid",
            "low",
        )
        val actual = DeviceClass.entries.map { it.code }
        assertEquals(expected, actual)
    }

    // =========================================================================
    // FinishReason — 4 canonical reasons (from enums/finish_reason.yaml)
    // =========================================================================

    @Test
    fun `generated FinishReason has exactly 4 entries`() {
        assertEquals(4, FinishReason.entries.size)
    }

    @Test
    fun `generated FinishReason contains all canonical codes`() {
        val expected = listOf(
            "stop",
            "tool_calls",
            "length",
            "content_filter",
        )
        val actual = FinishReason.entries.map { it.code }
        assertEquals(expected, actual)
    }

    // =========================================================================
    // CompatibilityLevel — 4 canonical levels (from enums/compatibility_level.yaml)
    // =========================================================================

    @Test
    fun `generated CompatibilityLevel has exactly 4 entries`() {
        assertEquals(4, CompatibilityLevel.entries.size)
    }

    @Test
    fun `generated CompatibilityLevel contains all canonical codes`() {
        val expected = listOf(
            "stable",
            "beta",
            "experimental",
            "compatibility",
        )
        val actual = CompatibilityLevel.entries.map { it.code }
        assertEquals(expected, actual)
    }

    // =========================================================================
    // OtlpResourceAttribute — from telemetry/resource_attributes.yaml
    // =========================================================================

    @Test
    fun `OtlpResourceAttribute contains all canonical keys`() {
        assertEquals("service.name", OtlpResourceAttribute.SERVICE_NAME)
        assertEquals("service.version", OtlpResourceAttribute.SERVICE_VERSION)
        assertEquals("telemetry.sdk.name", OtlpResourceAttribute.TELEMETRY_SDK_NAME)
        assertEquals("telemetry.sdk.language", OtlpResourceAttribute.TELEMETRY_SDK_LANGUAGE)
        assertEquals("telemetry.sdk.version", OtlpResourceAttribute.TELEMETRY_SDK_VERSION)
        assertEquals("octomil.org.id", OtlpResourceAttribute.OCTOMIL_ORG_ID)
        assertEquals("octomil.device.id", OtlpResourceAttribute.OCTOMIL_DEVICE_ID)
        assertEquals("octomil.platform", OtlpResourceAttribute.OCTOMIL_PLATFORM)
        assertEquals("octomil.sdk.surface", OtlpResourceAttribute.OCTOMIL_SDK_SURFACE)
        assertEquals("octomil.install.id", OtlpResourceAttribute.OCTOMIL_INSTALL_ID)
        assertEquals("octomil.device.class", OtlpResourceAttribute.OCTOMIL_DEVICE_CLASS)
        assertEquals("octomil.available_runtimes", OtlpResourceAttribute.OCTOMIL_AVAILABLE_RUNTIMES)
        assertEquals("octomil.accelerators", OtlpResourceAttribute.OCTOMIL_ACCELERATORS)
    }
}
