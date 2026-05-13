package ai.octomil.audio

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RuntimeCapability
import ai.octomil.runtime.nativebridge.NativeRuntimeBridge
import ai.octomil.runtime.nativebridge.NativeRuntimeCapabilities
import ai.octomil.runtime.nativebridge.NativeRuntimeResult
import ai.octomil.runtime.nativebridge.NativeSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for the [AudioVad] facade.
 *
 * Parity contract cell: `audio.vad`.
 *
 * These tests verify:
 *   1. Facade exists and is callable.
 *   2. Returns RUNTIME_UNAVAILABLE when the JNI library is absent.
 *   3. [VadTransition] data class has the required fields.
 *   4. `audio.vad` is registered in [NativeRuntimeCapabilities.LIVE_NATIVE_CONDITIONAL_PROFILES].
 */
class AudioVadTest {

    // ------------------------------------------------------------------
    // 1. Facade callable — fails closed with RUNTIME_UNAVAILABLE when
    //    the JNI library is absent (unit-test environment has no .so).
    // ------------------------------------------------------------------

    @Test
    fun `detect surfaces RUNTIME_UNAVAILABLE when JNI library is absent`() = runBlocking {
        val bridge = NativeRuntimeBridge("octomil_runtime_jni_missing_for_unit_test")
        val vad = AudioVad(bridge)

        try {
            vad.detect(floatArrayOf(0f, 1f, -1f), sampleRate = 16_000)
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
                    e.message?.contains("audio.vad", ignoreCase = true) == true,
            )
        }
    }

    @Test
    fun `detect rejects empty audio with IllegalArgumentException`() = runBlocking {
        val vad = AudioVad()
        try {
            vad.detect(floatArrayOf(), sampleRate = 16_000)
            fail("Expected IllegalArgumentException for empty audio")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("empty") == true)
        }
    }

    @Test
    fun `detect rejects zero or negative sampleRate`() = runBlocking {
        val vad = AudioVad()
        try {
            vad.detect(floatArrayOf(0f), sampleRate = 0)
            fail("Expected IllegalArgumentException for sampleRate=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("sampleRate") == true)
        }
    }

    // ------------------------------------------------------------------
    // 2. VadTransition data class
    // ------------------------------------------------------------------

    @Test
    fun `VadTransition has expected fields`() {
        val t = VadTransition(
            kind = "speech_start",
            timestampMs = 1_200L,
            confidence = 0.95,
        )
        assertEquals("speech_start", t.kind)
        assertEquals(1_200L, t.timestampMs)
        assertEquals(0.95, t.confidence)
    }

    @Test
    fun `VadTransition confidence is nullable`() {
        val t = VadTransition(kind = "speech_end", timestampMs = 500L)
        assertEquals(null, t.confidence)
    }

    // ------------------------------------------------------------------
    // 3. LIVE_NATIVE_CONDITIONAL_PROFILES registration
    // ------------------------------------------------------------------

    @Test
    fun `audio_vad is registered in LIVE_NATIVE_CONDITIONAL_PROFILES`() {
        assertTrue(
            "AUDIO_VAD must be in LIVE_NATIVE_CONDITIONAL_PROFILES",
            NativeRuntimeCapabilities.LIVE_NATIVE_CONDITIONAL_PROFILES.contains(
                RuntimeCapability.AUDIO_VAD,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 4. Regression: poll-loop timeout must be REQUEST_TIMEOUT, not success
    //
    // Previously, if SESSION_COMPLETED never arrived before the drain
    // deadline, drainVadEvents returned a (possibly partial) list silently.
    // This test pins the fix: an expired deadline without SessionCompleted
    // must throw REQUEST_TIMEOUT.
    // ------------------------------------------------------------------

    @Test
    fun `drainVadEvents throws REQUEST_TIMEOUT when deadline expires without SessionCompleted`() {
        // Arrange: a mock bridge whose pollEvent always returns null (JNI TIMEOUT)
        // simulating a hung native session that never delivers SessionCompleted.
        val mockBridge = mockk<NativeRuntimeBridge>(relaxed = true)
        every { mockBridge.sessionPollEvent(any(), any()) } returns NativeRuntimeResult.Success(null)

        // NativeSession is constructed from the same module; use handle 1L.
        val session = NativeSession(mockBridge, handle = 1L)
        val vad = AudioVad(NativeRuntimeBridge("octomil_runtime_jni_missing_for_unit_test"))

        try {
            // drainDeadlineMs = 0 means the deadline is already in the past on
            // the very first iteration of the while loop.
            vad.drainVadEvents(session, drainDeadlineMs = 0L)
            fail("Expected OctomilException with REQUEST_TIMEOUT")
        } catch (e: OctomilException) {
            assertEquals(
                "Poll-loop deadline expiry must surface as REQUEST_TIMEOUT, not silent empty result",
                OctomilErrorCode.REQUEST_TIMEOUT,
                e.errorCode,
            )
            assertTrue(
                "Error message must mention vad and timeout",
                e.message?.contains("vad", ignoreCase = true) == true &&
                    e.message?.contains("timed out", ignoreCase = true) == true,
            )
        }
    }
}
