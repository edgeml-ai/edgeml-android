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
 * Unit tests for the [SpeakerEmbedding] facade.
 *
 * Parity contract cell: `audio.speaker.embedding`.
 *
 * These tests verify:
 *   1. Facade exists and is callable.
 *   2. Returns RUNTIME_UNAVAILABLE when the JNI library is absent.
 *   3. Input validation (empty audio, bad sample rate).
 *   4. `audio.speaker.embedding` is registered in
 *      [NativeRuntimeCapabilities.LIVE_NATIVE_CONDITIONAL_PROFILES].
 */
class SpeakerEmbeddingTest {

    // ------------------------------------------------------------------
    // 1. Facade callable — fails closed with RUNTIME_UNAVAILABLE when
    //    the JNI library is absent (unit-test environment has no .so).
    // ------------------------------------------------------------------

    @Test
    fun `create surfaces RUNTIME_UNAVAILABLE when JNI library is absent`() = runBlocking {
        val bridge = NativeRuntimeBridge("octomil_runtime_jni_missing_for_unit_test")
        val embedding = SpeakerEmbedding(bridge)

        try {
            embedding.create(floatArrayOf(0f, 1f, -1f), sampleRate = 16_000)
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
                    e.message?.contains("speaker", ignoreCase = true) == true,
            )
        }
    }

    @Test
    fun `create rejects empty audio`() = runBlocking {
        val embedding = SpeakerEmbedding()
        try {
            embedding.create(floatArrayOf(), sampleRate = 16_000)
            fail("Expected IllegalArgumentException for empty audio")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("empty") == true)
        }
    }

    @Test
    fun `create rejects zero sampleRate`() = runBlocking {
        val embedding = SpeakerEmbedding()
        try {
            embedding.create(floatArrayOf(0f), sampleRate = 0)
            fail("Expected IllegalArgumentException for sampleRate=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("sampleRate") == true)
        }
    }

    // ------------------------------------------------------------------
    // 2. LIVE_NATIVE_CONDITIONAL_PROFILES registration
    // ------------------------------------------------------------------

    @Test
    fun `audio_speaker_embedding is registered in LIVE_NATIVE_CONDITIONAL_PROFILES`() {
        assertTrue(
            "AUDIO_SPEAKER_EMBEDDING must be in LIVE_NATIVE_CONDITIONAL_PROFILES",
            NativeRuntimeCapabilities.LIVE_NATIVE_CONDITIONAL_PROFILES.contains(
                RuntimeCapability.AUDIO_SPEAKER_EMBEDDING,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 3. Regression: poll-loop timeout must be REQUEST_TIMEOUT, not success
    //
    // Previously, if SESSION_COMPLETED never arrived before the drain
    // deadline, drainEmbeddingEvents could return a partial embedding
    // vector or throw INFERENCE_FAILED instead of REQUEST_TIMEOUT.
    // This test pins the fix: an expired deadline without SessionCompleted
    // must always throw REQUEST_TIMEOUT.
    // ------------------------------------------------------------------

    @Test
    fun `drainEmbeddingEvents throws REQUEST_TIMEOUT when deadline expires without SessionCompleted`() {
        // Arrange: a mock bridge whose pollEvent always returns null (JNI TIMEOUT)
        // simulating a hung native session that never delivers SessionCompleted.
        val mockBridge = mockk<NativeRuntimeBridge>(relaxed = true)
        every { mockBridge.sessionPollEvent(any(), any()) } returns NativeRuntimeResult.Success(null)

        val session = NativeSession(mockBridge, handle = 1L)
        val embedding = SpeakerEmbedding(NativeRuntimeBridge("octomil_runtime_jni_missing_for_unit_test"))

        try {
            embedding.drainEmbeddingEvents(session, drainDeadlineMs = 0L)
            fail("Expected OctomilException with REQUEST_TIMEOUT")
        } catch (e: OctomilException) {
            assertEquals(
                "Poll-loop deadline expiry must surface as REQUEST_TIMEOUT",
                OctomilErrorCode.REQUEST_TIMEOUT,
                e.errorCode,
            )
            assertTrue(
                "Error message must mention speaker.embedding and timeout",
                e.message?.contains("speaker", ignoreCase = true) == true &&
                    e.message?.contains("timed out", ignoreCase = true) == true,
            )
        }
    }
}
