package ai.octomil.conformance

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTO-GENERATED cross-cutting conformance stub.
 * Source contract: conformance/error_mapping.yaml
 * Conformance version: 0.1.5-rc1
 * Generator: scripts/generate_conformance.py (target=kotlin)
 *
 * Cross-cutting contracts encode declarative tables. This stub registers
 * a JUnit target so the conformance manifest is honoured.
 */
class CrossCuttingConformanceTest_ERRORMAPPING {

    @Test
    fun `status_to_sdk_code table is populated`() {
        assertTrue(STATUS_TO_SDK_CODE.isNotEmpty())
    }

    @Test
    fun `all mapped sdk_codes are non-empty strings`() {
        for ((status, code) in STATUS_TO_SDK_CODE) {
            if (code != null) {
                assertTrue("sdk_code for ${status} must not be blank", code.isNotBlank())
            }
        }
    }
}

// Status-to-SDK-code closed mapping from error_mapping.yaml
private val STATUS_TO_SDK_CODE: Map<String, String?> = mapOf(
        "OCT_STATUS_OK" to null,
        "OCT_STATUS_INVALID_INPUT" to "invalid_input",
        "OCT_STATUS_UNSUPPORTED" to "unsupported_modality",
        "OCT_STATUS_NOT_FOUND" to "model_not_found",
        "OCT_STATUS_BUSY" to "runtime_unavailable",
        "OCT_STATUS_TIMEOUT" to "stream_interrupted",
        "OCT_STATUS_CANCELLED" to "cancelled",
        "OCT_STATUS_INTERNAL" to "inference_failed",
        "OCT_STATUS_VERSION_MISMATCH" to "runtime_unavailable",
)
