package ai.octomil.audio

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RuntimeCapability
import ai.octomil.runtime.nativebridge.NativeRuntimeBridge
import ai.octomil.runtime.nativebridge.NativeRuntimeCapabilities
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for the [AudioSpeechStream] facade.
 *
 * Parity contract cell: `audio.tts.stream`.
 *
 * These tests verify:
 *   1. Facade exists and is callable, returns a [kotlinx.coroutines.flow.Flow].
 *   2. Returns RUNTIME_UNAVAILABLE when the JNI library is absent.
 *   3. [TtsPcmChunk] data class has the required fields.
 *   4. Voice validation (null → "0", non-numeric → INVALID_INPUT).
 *   5. Input validation (blank input).
 *   6. `audio.tts.stream` is registered in
 *      [NativeRuntimeCapabilities.LIVE_NATIVE_CONDITIONAL_PROFILES].
 */
class AudioSpeechStreamTest {

    // ------------------------------------------------------------------
    // 1. Facade callable — fails closed with RUNTIME_UNAVAILABLE when
    //    the JNI library is absent (unit-test environment has no .so).
    // ------------------------------------------------------------------

    @Test
    fun `stream surfaces RUNTIME_UNAVAILABLE when JNI library is absent`() = runBlocking {
        val bridge = NativeRuntimeBridge("octomil_runtime_jni_missing_for_unit_test")
        val speechStream = AudioSpeechStream(bridge)

        try {
            speechStream.stream(model = "kokoro-82m", input = "hello").toList()
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
                    e.message?.contains("tts", ignoreCase = true) == true,
            )
        }
    }

    @Test
    fun `stream rejects blank input`() = runBlocking {
        val speechStream = AudioSpeechStream()
        try {
            speechStream.stream(model = "kokoro-82m", input = "   ").toList()
            fail("Expected IllegalArgumentException for blank input")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("non-empty") == true || e.message?.contains("blank") == true)
        }
    }

    @Test
    fun `stream rejects non-positive speed`() = runBlocking {
        val speechStream = AudioSpeechStream()
        try {
            speechStream.stream(model = "kokoro-82m", input = "hello", speed = 0f).toList()
            fail("Expected IllegalArgumentException for speed=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("speed") == true)
        }
    }

    // ------------------------------------------------------------------
    // 2. Voice validation
    // ------------------------------------------------------------------

    @Test
    fun `validateVoice maps null to 0`() {
        assertEquals("0", AudioSpeechStream.validateVoice(null))
    }

    @Test
    fun `validateVoice maps empty string to 0`() {
        assertEquals("0", AudioSpeechStream.validateVoice(""))
        assertEquals("0", AudioSpeechStream.validateVoice("   "))
    }

    @Test
    fun `validateVoice passes numeric speaker ids through`() {
        assertEquals("0", AudioSpeechStream.validateVoice("0"))
        assertEquals("42", AudioSpeechStream.validateVoice("42"))
    }

    @Test
    fun `validateVoice rejects non-numeric voice strings with INVALID_INPUT`() {
        try {
            AudioSpeechStream.validateVoice("af_bella")
            fail("Expected OctomilException for non-numeric voice")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.INVALID_INPUT, e.errorCode)
            assertTrue(e.message?.contains("af_bella") == true)
        }
    }

    // ------------------------------------------------------------------
    // 3. TtsPcmChunk data class
    // ------------------------------------------------------------------

    @Test
    fun `TtsPcmChunk has expected fields`() {
        val chunk = TtsPcmChunk(
            pcm = byteArrayOf(0, 1, 2),
            sampleRate = 22_050,
            chunkIndex = 0,
            isFinal = false,
        )
        assertEquals(22_050, chunk.sampleRate)
        assertEquals(0, chunk.chunkIndex)
        assertEquals(false, chunk.isFinal)
        assertEquals(3, chunk.pcm.size)
    }

    // ------------------------------------------------------------------
    // 4. LIVE_NATIVE_CONDITIONAL_PROFILES registration
    // ------------------------------------------------------------------

    @Test
    fun `audio_tts_stream is registered in LIVE_NATIVE_CONDITIONAL_PROFILES`() {
        assertTrue(
            "AUDIO_TTS_STREAM must be in LIVE_NATIVE_CONDITIONAL_PROFILES",
            NativeRuntimeCapabilities.LIVE_NATIVE_CONDITIONAL_PROFILES.contains(
                RuntimeCapability.AUDIO_TTS_STREAM,
            ),
        )
    }
}
