package ai.octomil.runtime.nativebridge

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.generated.ErrorCode
import ai.octomil.generated.RuntimeCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRuntimeBridgeTest {

    @Test
    fun `open reports explicit skip when JNI library is unavailable`() {
        val bridge = NativeRuntimeBridge("octomil_runtime_jni_missing_for_unit_test")

        val result = bridge.open()

        assertTrue(result is NativeRuntimeResult.Skipped)
        val skip = (result as NativeRuntimeResult.Skipped).reason
        assertEquals(NativeRuntimeStatus.RUNTIME_UNAVAILABLE, skip.status)
        assertEquals(OctomilErrorCode.RUNTIME_UNAVAILABLE, skip.sdkErrorCode)
        assertEquals(ErrorCode.RUNTIME_UNAVAILABLE, skip.contractErrorCode)
        assertTrue(skip.message.contains("octomil_runtime_jni_missing_for_unit_test"))
    }

    @Test
    fun `status mapping resolves through generated error taxonomy`() {
        val mappedStatuses = NativeRuntimeStatus.entries.filter { it != NativeRuntimeStatus.OK }

        for (status in mappedStatuses) {
            val sdkCode = status.toSdkErrorCode()
            assertTrue("status ${status.cName} must map to an SDK code", sdkCode != null)

            if (status != NativeRuntimeStatus.UNKNOWN) {
                assertNotEquals(OctomilErrorCode.UNKNOWN, sdkCode)
                val contractCode = ErrorCode.fromCode(sdkCode!!.name.lowercase())
                assertTrue(
                    "status ${status.cName} maps to ${sdkCode.name}, which must exist in generated ErrorCode",
                    contractCode != null,
                )
            }
        }
    }

    @Test
    fun `chat stream remains a non-advertised profile`() {
        val wire = NativeRuntimeCapabilitiesWire(
            statusCode = NativeRuntimeStatus.OK.code,
            message = null,
            supportedEngines = arrayOf("llama_cpp"),
            supportedCapabilities = arrayOf(
                RuntimeCapability.CHAT_COMPLETION.code,
                RuntimeCapability.CHAT_STREAM.code,
            ),
            supportedArchs = arrayOf("android-arm64"),
            ramTotalBytes = 0,
            ramAvailableBytes = 0,
            hasAppleSilicon = false,
            hasCuda = false,
            hasMetal = false,
        )

        val capabilities = NativeRuntimeCapabilities.fromWire(wire)

        assertTrue(capabilities.supportedCapabilities.contains(RuntimeCapability.CHAT_COMPLETION))
        assertFalse(capabilities.supportedCapabilities.contains(RuntimeCapability.CHAT_STREAM))
        assertTrue(capabilities.rejectedProfileCodes.contains(RuntimeCapability.CHAT_STREAM.code))
        assertTrue(NativeRuntimeCapabilities.NON_ADVERTISED_PROFILES.contains(RuntimeCapability.CHAT_STREAM))
    }

    @Test
    fun `unknown native capability codes are preserved without inference`() {
        val wire = NativeRuntimeCapabilitiesWire(
            statusCode = NativeRuntimeStatus.OK.code,
            message = null,
            supportedEngines = emptyArray(),
            supportedCapabilities = arrayOf("future.capability"),
            supportedArchs = emptyArray(),
            ramTotalBytes = 0,
            ramAvailableBytes = 0,
            hasAppleSilicon = false,
            hasCuda = false,
            hasMetal = false,
        )

        val capabilities = NativeRuntimeCapabilities.fromWire(wire)

        assertTrue(capabilities.supportedCapabilities.isEmpty())
        assertEquals(listOf("future.capability"), capabilities.unknownCapabilityCodes)
        assertEquals(listOf("future.capability"), capabilities.rawSupportedCapabilityCodes)
    }
}
