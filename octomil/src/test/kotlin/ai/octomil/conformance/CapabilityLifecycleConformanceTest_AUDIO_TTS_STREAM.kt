package ai.octomil.conformance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test

/**
 * AUTO-GENERATED — do not edit.
 *
 * Source contract: conformance/audio.tts.stream.yaml
 * Conformance version: 0.1.5-rc1
 * Generator: scripts/generate_conformance.py (target=kotlin)
 *
 * Regenerated from contracts YAML; assertions read from contract source,
 * not SDK-self-reference (fixes Codex B1-class finding).
 *
 * Required runtime ABI: {major:0, minor:10}
 * is_advertised: true
 *
 * Native-path lifecycle tests carry @Ignore — native shared library and
 * artifact wiring are not yet present in octomil-android. Each @Ignore body
 * calls fail() so removal without the bridge artifacts causes a loud failure.
 */
@Suppress("ClassName")
class CapabilityLifecycleConformanceTest_AUDIO_TTS_STREAM {
    @Test
    fun `capability name is byte-for-byte canonical`() {
        assertEquals("audio.tts.stream", CAPABILITY)
    }

    @Test
    fun `is_advertised flag matches contract`() {
        assertEquals(true, IS_ADVERTISED)
    }

    @Test
    fun `lifecycle steps match contract`() {
        val expected = listOf("runtime_open", "model_open", "model_warm", "session_open", "send_text", "poll_event", "session_close", "model_close", "runtime_close")
        assertEquals(expected, LIFECYCLE_STEPS)
    }

    @Test
    fun `bounded error codes match contract`() {
        val expected = setOf("cancelled", "inference_failed", "invalid_input", "model_not_found", "runtime_unavailable")
        assertEquals(expected, BOUNDED_ERROR_CODES)
    }

    @Test
    fun `cancelled is always present in bounded error codes`() {
        assertTrue("cancelled must be in bounded_error_codes", BOUNDED_ERROR_CODES.contains("cancelled"))
    }

    @Test
    fun `expected event sequence matches contract`() {
        assertEquals(4, EVENT_SEQUENCE.size)
        assertEquals("OCT_EVENT_SESSION_STARTED", EVENT_SEQUENCE[0].eventName)
        assertEquals(Quantifier.EXACTLY_ONE, EVENT_SEQUENCE[0].quantifier)
        assertEquals("OCT_EVENT_TTS_AUDIO_CHUNK", EVENT_SEQUENCE[1].eventName)
        assertEquals(Quantifier.ONE_OR_MORE, EVENT_SEQUENCE[1].quantifier)
        assertEquals("OCT_EVENT_METRIC", EVENT_SEQUENCE[2].eventName)
        assertEquals(Quantifier.ZERO_OR_MORE, EVENT_SEQUENCE[2].quantifier)
        assertEquals("OCT_EVENT_SESSION_COMPLETED", EVENT_SEQUENCE[3].eventName)
        assertEquals(Quantifier.EXACTLY_ONE, EVENT_SEQUENCE[3].quantifier)
    }

    @Test
    fun `delivery_timing matches contract`() {
        assertEquals("progressive_during_synthesis", DELIVERY_TIMING)
    }

    @Test
    fun `privacy deny_field_substrings are documented`() {
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("audio_bytes"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("raw_audio"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("audio_pcm"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("wav_bytes"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("transcript_text"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("input_text"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("prompt_text"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("voice_metadata"))
    }

    // =========================================================================
    // SKIP_WITH_EXPLICIT_REASON: native path lifecycle
    // =========================================================================

    @Test
    @Ignore("SKIP_WITH_EXPLICIT_REASON: native runtime bridge artifacts are not yet wired in octomil-android — liboctomil_runtime_jni.so / liboctomil_runtime.so are missing. NativePathSkip.CLOUD_FALLBACK_ACTIVE = false (cloud transport explicitly disallowed from masking native skip). See TODO: native-ffi-binding")
    fun `audioTtsStream_native_lifecycle`() {
        fail("Native bridge artifacts are missing — this test should not be running")
    }
}

// =============================================================================
// Contract constants — read from YAML, not from SDK (fixes B1 oracle drift)
// =============================================================================

private const val CAPABILITY = "audio.tts.stream"
private val IS_ADVERTISED = true
private val LIFECYCLE_STEPS: List<String> = listOf("runtime_open", "model_open", "model_warm", "session_open", "send_text", "poll_event", "session_close", "model_close", "runtime_close")
private val BOUNDED_ERROR_CODES: Set<String> = setOf("cancelled", "inference_failed", "invalid_input", "model_not_found", "runtime_unavailable")
private val EVENT_SEQUENCE: List<EventStep> = listOf(
    EventStep("OCT_EVENT_SESSION_STARTED", Quantifier.EXACTLY_ONE),
    EventStep("OCT_EVENT_TTS_AUDIO_CHUNK", Quantifier.ONE_OR_MORE),
    EventStep("OCT_EVENT_METRIC", Quantifier.ZERO_OR_MORE),
    EventStep("OCT_EVENT_SESSION_COMPLETED", Quantifier.EXACTLY_ONE),
)
private val DENY_FIELD_SUBSTRINGS: Set<String> = setOf("audio_bytes", "raw_audio", "audio_pcm", "wav_bytes", "transcript_text", "input_text", "prompt_text", "voice_metadata")
private const val DELIVERY_TIMING = "progressive_during_synthesis"

// Stub set used by is_advertised=false assertion — populated at wiring time
private val LIVE_CAPABILITY_STUBS: Set<String> = emptySet()
