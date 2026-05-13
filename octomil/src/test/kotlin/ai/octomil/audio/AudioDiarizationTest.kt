package ai.octomil.audio

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RuntimeCapability
import ai.octomil.runtime.nativebridge.NativeRuntimeBridge
import ai.octomil.runtime.nativebridge.NativeRuntimeCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for the [AudioDiarization] facade.
 *
 * Parity contract cell: `audio.diarization`.
 *
 * These tests verify:
 *   1. Facade exists and is callable.
 *   2. Returns RUNTIME_UNAVAILABLE when the JNI library is absent.
 *   3. [DiarizationSegment] data class has the required fields.
 *   4. Input validation (empty audio, bad sample rate).
 *   5. `audio.diarization` is registered in
 *      [NativeRuntimeCapabilities.LIVE_NATIVE_CONDITIONAL_PROFILES].
 */
class AudioDiarizationTest {

    // ------------------------------------------------------------------
    // 1. Facade callable — fails closed with RUNTIME_UNAVAILABLE when
    //    the JNI library is absent (unit-test environment has no .so).
    // ------------------------------------------------------------------

    @Test
    fun `create surfaces RUNTIME_UNAVAILABLE when JNI library is absent`() = runBlocking {
        val bridge = NativeRuntimeBridge("octomil_runtime_jni_missing_for_unit_test")
        val diarization = AudioDiarization(bridge)

        try {
            diarization.create(floatArrayOf(0f, 1f, -1f), sampleRate = 16_000)
            fail("Expected OctomilException with RUNTIME_UNAVAILABLE")
        } catch (e: OctomilException) {
            assertEquals(
                "Expected RUNTIME_UNAVAILABLE error code",
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                e.errorCode,
            )
            assertTrue(
                "Error message must reference the capability or unavailability",
                e.message?.contains("unavailable", ignoreCase = true) == true ||
                    e.message?.contains("diarization", ignoreCase = true) == true,
            )
        }
    }

    @Test
    fun `create rejects empty audio`() = runBlocking {
        val diarization = AudioDiarization()
        try {
            diarization.create(floatArrayOf(), sampleRate = 16_000)
            fail("Expected IllegalArgumentException for empty audio")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("empty") == true)
        }
    }

    @Test
    fun `create rejects zero sampleRate`() = runBlocking {
        val diarization = AudioDiarization()
        try {
            diarization.create(floatArrayOf(0f), sampleRate = 0)
            fail("Expected IllegalArgumentException for sampleRate=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("sampleRate") == true)
        }
    }

    // ------------------------------------------------------------------
    // 2. DiarizationSegment data class
    // ------------------------------------------------------------------

    @Test
    fun `DiarizationSegment has expected fields`() {
        val seg = DiarizationSegment(
            startMs = 0L,
            endMs = 2_500L,
            speakerTag = 1,
            confidence = 0.87,
        )
        assertEquals(0L, seg.startMs)
        assertEquals(2_500L, seg.endMs)
        assertEquals(1, seg.speakerTag)
        assertEquals(0.87, seg.confidence)
    }

    @Test
    fun `DiarizationSegment confidence is nullable`() {
        val seg = DiarizationSegment(startMs = 100L, endMs = 800L, speakerTag = 0)
        assertEquals(null, seg.confidence)
    }

    // ------------------------------------------------------------------
    // 3. LIVE_NATIVE_CONDITIONAL_PROFILES registration
    // ------------------------------------------------------------------

    @Test
    fun `audio_diarization is registered in LIVE_NATIVE_CONDITIONAL_PROFILES`() {
        assertTrue(
            "AUDIO_DIARIZATION must be in LIVE_NATIVE_CONDITIONAL_PROFILES",
            NativeRuntimeCapabilities.LIVE_NATIVE_CONDITIONAL_PROFILES.contains(
                RuntimeCapability.AUDIO_DIARIZATION,
            ),
        )
    }
}
