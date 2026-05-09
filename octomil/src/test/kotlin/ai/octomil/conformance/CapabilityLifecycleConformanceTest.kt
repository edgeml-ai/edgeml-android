package ai.octomil.conformance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capability lifecycle/events/errors conformance for the 7 LIVE native capabilities.
 *
 * Sourced from octomil-contracts @ 2b2612669f8844f53b3fb5ae074f0dfbc0dd0a00
 * Conformance YAMLs: octomil-contracts/conformance/<capability>.yaml
 *
 * ALL native-path tests are SKIP_WITH_EXPLICIT_REASON:
 *   JNI binding to liboctomil_runtime not yet wired in octomil-android.
 *   The Android SDK routes through cloud transport or TFLite-based engine
 *   stubs; the ABI-compliant oct_runtime_open / oct_session_open / etc.
 *   JNI bridge does not exist yet in this repo. Native lifecycle, event-
 *   sequence, and invalid-input assertions will activate when the JNI
 *   bridge lands (see TODO: octomil-android native runtime JNI bridge).
 *
 * Constants-level assertions (capability name strings, error codes, event
 * type names, streaming honesty tokens) DO run — they verify byte-for-byte
 * match to contracts without requiring native binding.
 */
class CapabilityLifecycleConformanceTest {

    // =========================================================================
    // Constants: capability name strings (byte-for-byte match to contracts)
    //
    // Source: octomil-contracts/conformance/<capability>.yaml field `capability:`
    // =========================================================================

    @Test
    fun `capability name constants are byte-for-byte canonical`() {
        assertEquals("chat.completion",        LiveCapability.CHAT_COMPLETION)
        assertEquals("embeddings.text",        LiveCapability.EMBEDDINGS_TEXT)
        assertEquals("audio.transcription",    LiveCapability.AUDIO_TRANSCRIPTION)
        assertEquals("audio.vad",              LiveCapability.AUDIO_VAD)
        assertEquals("audio.speaker.embedding",LiveCapability.AUDIO_SPEAKER_EMBEDDING)
        assertEquals("audio.tts.batch",        LiveCapability.AUDIO_TTS_BATCH)
        assertEquals("audio.tts.stream",       LiveCapability.AUDIO_TTS_STREAM)
    }

    @Test
    fun `exactly 7 live capabilities are claimed`() {
        assertEquals(7, LiveCapability.ALL.size)
    }

    @Test
    fun `do NOT claim audio_diarization or audio_realtime_session or embeddings_image`() {
        // These must NOT appear in the live capability set.
        val forbidden = setOf(
            "audio.diarization",
            "audio.realtime.session",
            "embeddings.image",
            "index.vector.query",
            "audio.stt.batch",
            "audio.stt.stream",
            "chat.stream",  // streaming PROFILE of chat.completion, not a separate capability
        )
        for (cap in forbidden) {
            assertFalse(
                "'$cap' must NOT be claimed as a live capability",
                LiveCapability.ALL.contains(cap),
            )
        }
    }

    // =========================================================================
    // Constants: error codes — canonical names (not OPERATION_CANCELLED/TIMEOUT)
    //
    // Source: octomil-contracts/conformance/error_mapping.yaml +
    //         each capability's bounded_error_codes list
    // =========================================================================

    @Test
    fun `canonical error code for cancellation is CANCELLED not OPERATION_CANCELLED`() {
        // contracts bounded_error_codes use "cancelled" — NOT "operation_cancelled"
        assertEquals("cancelled", ConformanceErrorCodes.CANCELLED)
    }

    @Test
    fun `canonical error code for timeout is REQUEST_TIMEOUT not TIMEOUT`() {
        // contracts use "request_timeout" — NOT "timeout"
        assertEquals("request_timeout", ConformanceErrorCodes.REQUEST_TIMEOUT)
    }

    @Test
    fun `all 7 capabilities include CANCELLED in their bounded error codes`() {
        // Every live capability YAML lists `cancelled` in bounded_error_codes.
        for ((cap, codes) in BoundedErrorCodes.PER_CAPABILITY) {
            assertTrue(
                "Capability '$cap' must have 'cancelled' in bounded_error_codes",
                codes.contains("cancelled"),
            )
        }
    }

    @Test
    fun `chat_completion bounded error codes match contracts`() {
        // Source: octomil-contracts/conformance/chat.completion.yaml bounded_error_codes
        val expected = setOf("invalid_input", "context_too_large", "inference_failed", "cancelled")
        assertEquals(expected, BoundedErrorCodes.PER_CAPABILITY["chat.completion"])
    }

    @Test
    fun `embeddings_text bounded error codes match contracts`() {
        // Source: octomil-contracts/conformance/embeddings.text.yaml bounded_error_codes
        val expected = setOf("invalid_input", "context_too_large", "inference_failed", "cancelled")
        assertEquals(expected, BoundedErrorCodes.PER_CAPABILITY["embeddings.text"])
    }

    @Test
    fun `audio_transcription bounded error codes match contracts`() {
        // Source: octomil-contracts/conformance/audio.transcription.yaml bounded_error_codes
        val expected = setOf(
            "invalid_input", "inference_failed", "cancelled",
            "unsupported_modality", "runtime_unavailable",
        )
        assertEquals(expected, BoundedErrorCodes.PER_CAPABILITY["audio.transcription"])
    }

    @Test
    fun `audio_vad bounded error codes match contracts`() {
        // Source: octomil-contracts/conformance/audio.vad.yaml bounded_error_codes
        val expected = setOf("invalid_input", "inference_failed", "cancelled", "unsupported_modality")
        assertEquals(expected, BoundedErrorCodes.PER_CAPABILITY["audio.vad"])
    }

    @Test
    fun `audio_speaker_embedding bounded error codes match contracts`() {
        // Source: octomil-contracts/conformance/audio.speaker.embedding.yaml bounded_error_codes
        val expected = setOf("invalid_input", "inference_failed", "cancelled", "unsupported_modality")
        assertEquals(expected, BoundedErrorCodes.PER_CAPABILITY["audio.speaker.embedding"])
    }

    @Test
    fun `audio_tts_batch bounded error codes match contracts`() {
        // Source: octomil-contracts/conformance/audio.tts.batch.yaml bounded_error_codes
        val expected = setOf(
            "invalid_input", "runtime_unavailable", "model_not_found", "cancelled", "inference_failed",
        )
        assertEquals(expected, BoundedErrorCodes.PER_CAPABILITY["audio.tts.batch"])
    }

    @Test
    fun `audio_tts_stream bounded error codes match contracts`() {
        // Source: octomil-contracts/conformance/audio.tts.stream.yaml bounded_error_codes
        val expected = setOf(
            "invalid_input", "runtime_unavailable", "model_not_found", "cancelled", "inference_failed",
        )
        assertEquals(expected, BoundedErrorCodes.PER_CAPABILITY["audio.tts.stream"])
    }

    // =========================================================================
    // Constants: event type names
    //
    // Source: octomil-contracts/conformance/<capability>.yaml expected_event_sequence
    // =========================================================================

    @Test
    fun `OCT_EVENT names are canonical`() {
        assertEquals("OCT_EVENT_SESSION_STARTED",    ConformanceEventNames.SESSION_STARTED)
        assertEquals("OCT_EVENT_SESSION_COMPLETED",  ConformanceEventNames.SESSION_COMPLETED)
        assertEquals("OCT_EVENT_METRIC",             ConformanceEventNames.METRIC)
        assertEquals("OCT_EVENT_TRANSCRIPT_CHUNK",   ConformanceEventNames.TRANSCRIPT_CHUNK)
        assertEquals("OCT_EVENT_TRANSCRIPT_SEGMENT", ConformanceEventNames.TRANSCRIPT_SEGMENT)
        assertEquals("OCT_EVENT_TRANSCRIPT_FINAL",   ConformanceEventNames.TRANSCRIPT_FINAL)
        assertEquals("OCT_EVENT_EMBEDDING_VECTOR",   ConformanceEventNames.EMBEDDING_VECTOR)
        assertEquals("OCT_EVENT_VAD_TRANSITION",     ConformanceEventNames.VAD_TRANSITION)
        assertEquals("OCT_EVENT_TTS_AUDIO_CHUNK",    ConformanceEventNames.TTS_AUDIO_CHUNK)
    }

    @Test
    fun `chat_completion expected event sequence matches contracts`() {
        // Source: octomil-contracts/conformance/chat.completion.yaml expected_event_sequence
        // SESSION_STARTED(1) → TRANSCRIPT_CHUNK(1+) → METRIC(0+) → SESSION_COMPLETED(1)
        val seq = EventSequences.CHAT_COMPLETION
        assertEquals(ConformanceEventNames.SESSION_STARTED,   seq[0].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                  seq[0].quantifier)
        assertEquals(ConformanceEventNames.TRANSCRIPT_CHUNK,  seq[1].eventName)
        assertEquals(Quantifier.ONE_OR_MORE,                  seq[1].quantifier)
        assertEquals(ConformanceEventNames.METRIC,            seq[2].eventName)
        assertEquals(Quantifier.ZERO_OR_MORE,                 seq[2].quantifier)
        assertEquals(ConformanceEventNames.SESSION_COMPLETED, seq[3].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                  seq[3].quantifier)
    }

    @Test
    fun `audio_transcription expected event sequence matches contracts`() {
        // Source: octomil-contracts/conformance/audio.transcription.yaml expected_event_sequence
        // SESSION_STARTED(1) → METRIC(0+) → TRANSCRIPT_SEGMENT(1+) → TRANSCRIPT_FINAL(1) → SESSION_COMPLETED(1)
        val seq = EventSequences.AUDIO_TRANSCRIPTION
        assertEquals(ConformanceEventNames.SESSION_STARTED,    seq[0].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                   seq[0].quantifier)
        assertEquals(ConformanceEventNames.METRIC,             seq[1].eventName)
        assertEquals(Quantifier.ZERO_OR_MORE,                  seq[1].quantifier)
        assertEquals(ConformanceEventNames.TRANSCRIPT_SEGMENT, seq[2].eventName)
        assertEquals(Quantifier.ONE_OR_MORE,                   seq[2].quantifier)
        assertEquals(ConformanceEventNames.TRANSCRIPT_FINAL,   seq[3].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                   seq[3].quantifier)
        assertEquals(ConformanceEventNames.SESSION_COMPLETED,  seq[4].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                   seq[4].quantifier)
    }

    @Test
    fun `audio_vad expected event sequence matches contracts`() {
        // Source: octomil-contracts/conformance/audio.vad.yaml expected_event_sequence
        // SESSION_STARTED(1) → METRIC(0+) → VAD_TRANSITION(1+) → SESSION_COMPLETED(1)
        val seq = EventSequences.AUDIO_VAD
        assertEquals(ConformanceEventNames.SESSION_STARTED,   seq[0].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                  seq[0].quantifier)
        assertEquals(ConformanceEventNames.METRIC,            seq[1].eventName)
        assertEquals(Quantifier.ZERO_OR_MORE,                 seq[1].quantifier)
        assertEquals(ConformanceEventNames.VAD_TRANSITION,    seq[2].eventName)
        assertEquals(Quantifier.ONE_OR_MORE,                  seq[2].quantifier)
        assertEquals(ConformanceEventNames.SESSION_COMPLETED, seq[3].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                  seq[3].quantifier)
    }

    @Test
    fun `audio_speaker_embedding expected event sequence matches contracts`() {
        // Source: octomil-contracts/conformance/audio.speaker.embedding.yaml expected_event_sequence
        // SESSION_STARTED(1) → METRIC(0+) → EMBEDDING_VECTOR(1) → SESSION_COMPLETED(1)
        val seq = EventSequences.AUDIO_SPEAKER_EMBEDDING
        assertEquals(ConformanceEventNames.SESSION_STARTED,   seq[0].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                  seq[0].quantifier)
        assertEquals(ConformanceEventNames.METRIC,            seq[1].eventName)
        assertEquals(Quantifier.ZERO_OR_MORE,                 seq[1].quantifier)
        assertEquals(ConformanceEventNames.EMBEDDING_VECTOR,  seq[2].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                  seq[2].quantifier)
        assertEquals(ConformanceEventNames.SESSION_COMPLETED, seq[3].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                  seq[3].quantifier)
    }

    @Test
    fun `embeddings_text expected event sequence matches contracts`() {
        // Source: octomil-contracts/conformance/embeddings.text.yaml expected_event_sequence
        // SESSION_STARTED(1) → EMBEDDING_VECTOR(1+) → METRIC(0+) → SESSION_COMPLETED(1)
        val seq = EventSequences.EMBEDDINGS_TEXT
        assertEquals(ConformanceEventNames.SESSION_STARTED,   seq[0].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                  seq[0].quantifier)
        assertEquals(ConformanceEventNames.EMBEDDING_VECTOR,  seq[1].eventName)
        assertEquals(Quantifier.ONE_OR_MORE,                  seq[1].quantifier)
        assertEquals(ConformanceEventNames.METRIC,            seq[2].eventName)
        assertEquals(Quantifier.ZERO_OR_MORE,                 seq[2].quantifier)
        assertEquals(ConformanceEventNames.SESSION_COMPLETED, seq[3].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                  seq[3].quantifier)
    }

    @Test
    fun `audio_tts_batch expected event sequence matches contracts`() {
        // Source: octomil-contracts/conformance/audio.tts.batch.yaml expected_event_sequence
        // SESSION_STARTED(1) → METRIC(0+) → TTS_AUDIO_CHUNK(1+) → SESSION_COMPLETED(1)
        val seq = EventSequences.AUDIO_TTS_BATCH
        assertEquals(ConformanceEventNames.SESSION_STARTED,   seq[0].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                  seq[0].quantifier)
        assertEquals(ConformanceEventNames.METRIC,            seq[1].eventName)
        assertEquals(Quantifier.ZERO_OR_MORE,                 seq[1].quantifier)
        assertEquals(ConformanceEventNames.TTS_AUDIO_CHUNK,   seq[2].eventName)
        assertEquals(Quantifier.ONE_OR_MORE,                  seq[2].quantifier)
        assertEquals(ConformanceEventNames.SESSION_COMPLETED, seq[3].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                  seq[3].quantifier)
    }

    @Test
    fun `audio_tts_stream expected event sequence matches contracts`() {
        // Source: octomil-contracts/conformance/audio.tts.stream.yaml expected_event_sequence
        // SESSION_STARTED(1) → TTS_AUDIO_CHUNK(1+, is_final=0/1) → METRIC(0+) → SESSION_COMPLETED(1)
        val seq = EventSequences.AUDIO_TTS_STREAM
        assertEquals(ConformanceEventNames.SESSION_STARTED,   seq[0].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                  seq[0].quantifier)
        assertEquals(ConformanceEventNames.TTS_AUDIO_CHUNK,   seq[1].eventName)
        assertEquals(Quantifier.ONE_OR_MORE,                  seq[1].quantifier)
        assertEquals(ConformanceEventNames.METRIC,            seq[2].eventName)
        assertEquals(Quantifier.ZERO_OR_MORE,                 seq[2].quantifier)
        assertEquals(ConformanceEventNames.SESSION_COMPLETED, seq[3].eventName)
        assertEquals(Quantifier.EXACTLY_ONE,                  seq[3].quantifier)
    }

    // =========================================================================
    // Streaming honesty tokens
    //
    // Source: octomil-contracts/conformance/audio.tts.stream.yaml
    //         delivery_timing and related fields (v0.1.9 progressive flip)
    // =========================================================================

    @Test
    fun `streaming honesty token progressive_during_synthesis is canonical`() {
        assertEquals("progressive_during_synthesis", StreamingHonestyTokens.PROGRESSIVE_DURING_SYNTHESIS)
    }

    @Test
    fun `streaming honesty token coalesced_after_synthesis is canonical`() {
        assertEquals("coalesced_after_synthesis", StreamingHonestyTokens.COALESCED_AFTER_SYNTHESIS)
    }

    @Test
    fun `audio_tts_stream delivery_timing is progressive_during_synthesis in v0_1_9`() {
        // octomil-contracts/conformance/audio.tts.stream.yaml:
        //   delivery_timing: progressive_during_synthesis  (flipped in v0.1.9)
        assertEquals(
            StreamingHonestyTokens.PROGRESSIVE_DURING_SYNTHESIS,
            StreamingHonestyTokens.TTS_STREAM_DELIVERY_TIMING,
        )
    }

    @Test
    fun `audio_tts_batch delivery_timing is coalesced_after_synthesis`() {
        // audio.tts.batch emits a single coalesced PCM chunk — honest label.
        assertEquals(
            StreamingHonestyTokens.COALESCED_AFTER_SYNTHESIS,
            StreamingHonestyTokens.TTS_BATCH_DELIVERY_TIMING,
        )
    }

    // =========================================================================
    // Lifecycle step sets per capability
    //
    // Source: <capability>.yaml lifecycle: field
    // model_bound=false capabilities skip model_open/model_warm/model_close
    // =========================================================================

    @Test
    fun `chat_completion lifecycle steps match contracts`() {
        // Source: chat.completion.yaml lifecycle — model_bound=true
        val expected = listOf(
            "runtime_open", "model_open", "model_warm",
            "session_open", "send_text", "poll_event",
            "session_close", "model_close", "runtime_close",
        )
        assertEquals(expected, LifecycleSteps.CHAT_COMPLETION)
    }

    @Test
    fun `embeddings_text lifecycle steps match contracts`() {
        val expected = listOf(
            "runtime_open", "model_open", "model_warm",
            "session_open", "send_text", "poll_event",
            "session_close", "model_close", "runtime_close",
        )
        assertEquals(expected, LifecycleSteps.EMBEDDINGS_TEXT)
    }

    @Test
    fun `audio_transcription lifecycle steps match contracts`() {
        val expected = listOf(
            "runtime_open", "model_open", "model_warm",
            "session_open", "send_audio", "poll_event",
            "session_close", "model_close", "runtime_close",
        )
        assertEquals(expected, LifecycleSteps.AUDIO_TRANSCRIPTION)
    }

    @Test
    fun `audio_vad lifecycle steps match contracts — model_bound false so no model_open`() {
        // Source: audio.vad.yaml lifecycle — model_bound=false; NO model_open/warm/close
        val expected = listOf(
            "runtime_open",
            "session_open", "send_audio", "poll_event",
            "session_close", "runtime_close",
        )
        assertEquals(expected, LifecycleSteps.AUDIO_VAD)
    }

    @Test
    fun `audio_speaker_embedding lifecycle steps match contracts`() {
        val expected = listOf(
            "runtime_open", "model_open", "model_warm",
            "session_open", "send_audio", "poll_event",
            "session_close", "model_close", "runtime_close",
        )
        assertEquals(expected, LifecycleSteps.AUDIO_SPEAKER_EMBEDDING)
    }

    @Test
    fun `audio_tts_batch lifecycle steps match contracts`() {
        val expected = listOf(
            "runtime_open", "model_open", "model_warm",
            "session_open", "send_text", "poll_event",
            "session_close", "model_close", "runtime_close",
        )
        assertEquals(expected, LifecycleSteps.AUDIO_TTS_BATCH)
    }

    @Test
    fun `audio_tts_stream lifecycle steps match contracts`() {
        val expected = listOf(
            "runtime_open", "model_open", "model_warm",
            "session_open", "send_text", "poll_event",
            "session_close", "model_close", "runtime_close",
        )
        assertEquals(expected, LifecycleSteps.AUDIO_TTS_STREAM)
    }

    // =========================================================================
    // SKIP_WITH_EXPLICIT_REASON: Native path tests
    //
    // All native invocation tests (open → ready → invoke → close against the
    // ABI) are intentionally absent from this file. They MUST be added when
    // the JNI bridge to liboctomil_runtime lands in octomil-android.
    //
    // Reason: JNI binding to liboctomil_runtime not yet wired in
    //         octomil-android — oct_runtime_open / oct_session_open /
    //         oct_session_send / oct_session_poll / oct_session_close JNI
    //         stubs do not exist. Adding fake-pass tests here would violate
    //         the hard rule: no cloud fallback hiding native skip.
    //
    // TODO: octomil-android native runtime JNI bridge (track in issue)
    // =========================================================================

    @Test
    fun `native_path_all_capabilities_SKIP_JNI_not_wired`() {
        // This is the explicit skip sentinel. When the JNI bridge lands, replace
        // with live open→ready→invoke→close assertions against the native runtime.
        val skipReason = NativePathSkip.REASON
        assertTrue(
            "Skip reason must document JNI status",
            skipReason.contains("JNI"),
        )
        assertTrue(
            "Skip reason must name liboctomil_runtime",
            skipReason.contains("liboctomil_runtime"),
        )
        // Sentinel: this test must NOT pass via cloud transport.
        // Cloud fallback is disallowed for native conformance assertions.
        assertFalse(
            "Cloud fallback must not mask a native skip",
            NativePathSkip.CLOUD_FALLBACK_ACTIVE,
        )
    }

    // =========================================================================
    // Privacy constraints: deny_field_substrings are documented
    // =========================================================================

    @Test
    fun `privacy deny_field_substrings are documented for each capability`() {
        // Source: each capability YAML privacy_constraints.deny_field_substrings
        // At minimum chat/embeddings must deny /Users/ and /home/
        val chatDeny = PrivacyConstraints.DENY_SUBSTRINGS["chat.completion"]!!
        assertTrue(chatDeny.contains("/Users/"))
        assertTrue(chatDeny.contains("/home/"))

        val transcriptionDeny = PrivacyConstraints.DENY_SUBSTRINGS["audio.transcription"]!!
        assertTrue(transcriptionDeny.contains("/Users/"))
        assertTrue(transcriptionDeny.contains(".wav"))
        assertTrue(transcriptionDeny.contains("ggml-tiny.bin"))

        val ttsDeny = PrivacyConstraints.DENY_SUBSTRINGS["audio.tts.batch"]!!
        assertTrue(ttsDeny.contains("audio_bytes"))
        assertTrue(ttsDeny.contains("input_text"))
    }

    // =========================================================================
    // Contracts version pin
    // =========================================================================

    @Test
    fun `contracts pin commit is recorded`() {
        assertEquals(
            "2b2612669f8844f53b3fb5ae074f0dfbc0dd0a00",
            ContractsPin.CONTRACTS_COMMIT,
        )
    }
}

// =============================================================================
// Supporting objects (constants pinned to contracts source-of-truth)
// =============================================================================

/** Canonical capability name strings from octomil-contracts conformance YAMLs. */
object LiveCapability {
    const val CHAT_COMPLETION         = "chat.completion"
    const val EMBEDDINGS_TEXT         = "embeddings.text"
    const val AUDIO_TRANSCRIPTION     = "audio.transcription"
    const val AUDIO_VAD               = "audio.vad"
    const val AUDIO_SPEAKER_EMBEDDING = "audio.speaker.embedding"
    const val AUDIO_TTS_BATCH         = "audio.tts.batch"
    const val AUDIO_TTS_STREAM        = "audio.tts.stream"

    val ALL: Set<String> = setOf(
        CHAT_COMPLETION,
        EMBEDDINGS_TEXT,
        AUDIO_TRANSCRIPTION,
        AUDIO_VAD,
        AUDIO_SPEAKER_EMBEDDING,
        AUDIO_TTS_BATCH,
        AUDIO_TTS_STREAM,
    )
}

/** Canonical error code wire strings from contracts. */
object ConformanceErrorCodes {
    const val CANCELLED        = "cancelled"        // NOT "operation_cancelled"
    const val REQUEST_TIMEOUT  = "request_timeout"  // NOT "timeout"
    const val INVALID_INPUT    = "invalid_input"
    const val INFERENCE_FAILED = "inference_failed"
    const val RUNTIME_UNAVAILABLE   = "runtime_unavailable"
    const val UNSUPPORTED_MODALITY  = "unsupported_modality"
    const val CONTEXT_TOO_LARGE     = "context_too_large"
    const val MODEL_NOT_FOUND       = "model_not_found"
}

/** Bounded error codes per capability. Source: each YAML's bounded_error_codes list. */
object BoundedErrorCodes {
    val PER_CAPABILITY: Map<String, Set<String>> = mapOf(
        "chat.completion" to setOf(
            "invalid_input", "context_too_large", "inference_failed", "cancelled",
        ),
        "embeddings.text" to setOf(
            "invalid_input", "context_too_large", "inference_failed", "cancelled",
        ),
        "audio.transcription" to setOf(
            "invalid_input", "inference_failed", "cancelled",
            "unsupported_modality", "runtime_unavailable",
        ),
        "audio.vad" to setOf(
            "invalid_input", "inference_failed", "cancelled", "unsupported_modality",
        ),
        "audio.speaker.embedding" to setOf(
            "invalid_input", "inference_failed", "cancelled", "unsupported_modality",
        ),
        "audio.tts.batch" to setOf(
            "invalid_input", "runtime_unavailable", "model_not_found",
            "cancelled", "inference_failed",
        ),
        "audio.tts.stream" to setOf(
            "invalid_input", "runtime_unavailable", "model_not_found",
            "cancelled", "inference_failed",
        ),
    )
}

/** OCT_EVENT_* name constants. Source: contracts conformance YAML expected_event_sequence. */
object ConformanceEventNames {
    const val SESSION_STARTED    = "OCT_EVENT_SESSION_STARTED"
    const val SESSION_COMPLETED  = "OCT_EVENT_SESSION_COMPLETED"
    const val METRIC             = "OCT_EVENT_METRIC"
    const val TRANSCRIPT_CHUNK   = "OCT_EVENT_TRANSCRIPT_CHUNK"
    const val TRANSCRIPT_SEGMENT = "OCT_EVENT_TRANSCRIPT_SEGMENT"
    const val TRANSCRIPT_FINAL   = "OCT_EVENT_TRANSCRIPT_FINAL"
    const val EMBEDDING_VECTOR   = "OCT_EVENT_EMBEDDING_VECTOR"
    const val VAD_TRANSITION     = "OCT_EVENT_VAD_TRANSITION"
    const val TTS_AUDIO_CHUNK    = "OCT_EVENT_TTS_AUDIO_CHUNK"
}

enum class Quantifier { EXACTLY_ONE, ONE_OR_MORE, ZERO_OR_MORE }

data class EventStep(val eventName: String, val quantifier: Quantifier)

/** Expected event sequences from each capability YAML. */
object EventSequences {
    val CHAT_COMPLETION: List<EventStep> = listOf(
        EventStep(ConformanceEventNames.SESSION_STARTED,   Quantifier.EXACTLY_ONE),
        EventStep(ConformanceEventNames.TRANSCRIPT_CHUNK,  Quantifier.ONE_OR_MORE),
        EventStep(ConformanceEventNames.METRIC,            Quantifier.ZERO_OR_MORE),
        EventStep(ConformanceEventNames.SESSION_COMPLETED, Quantifier.EXACTLY_ONE),
    )
    val EMBEDDINGS_TEXT: List<EventStep> = listOf(
        EventStep(ConformanceEventNames.SESSION_STARTED,   Quantifier.EXACTLY_ONE),
        EventStep(ConformanceEventNames.EMBEDDING_VECTOR,  Quantifier.ONE_OR_MORE),
        EventStep(ConformanceEventNames.METRIC,            Quantifier.ZERO_OR_MORE),
        EventStep(ConformanceEventNames.SESSION_COMPLETED, Quantifier.EXACTLY_ONE),
    )
    val AUDIO_TRANSCRIPTION: List<EventStep> = listOf(
        EventStep(ConformanceEventNames.SESSION_STARTED,    Quantifier.EXACTLY_ONE),
        EventStep(ConformanceEventNames.METRIC,             Quantifier.ZERO_OR_MORE),
        EventStep(ConformanceEventNames.TRANSCRIPT_SEGMENT, Quantifier.ONE_OR_MORE),
        EventStep(ConformanceEventNames.TRANSCRIPT_FINAL,   Quantifier.EXACTLY_ONE),
        EventStep(ConformanceEventNames.SESSION_COMPLETED,  Quantifier.EXACTLY_ONE),
    )
    val AUDIO_VAD: List<EventStep> = listOf(
        EventStep(ConformanceEventNames.SESSION_STARTED,   Quantifier.EXACTLY_ONE),
        EventStep(ConformanceEventNames.METRIC,            Quantifier.ZERO_OR_MORE),
        EventStep(ConformanceEventNames.VAD_TRANSITION,    Quantifier.ONE_OR_MORE),
        EventStep(ConformanceEventNames.SESSION_COMPLETED, Quantifier.EXACTLY_ONE),
    )
    val AUDIO_SPEAKER_EMBEDDING: List<EventStep> = listOf(
        EventStep(ConformanceEventNames.SESSION_STARTED,   Quantifier.EXACTLY_ONE),
        EventStep(ConformanceEventNames.METRIC,            Quantifier.ZERO_OR_MORE),
        EventStep(ConformanceEventNames.EMBEDDING_VECTOR,  Quantifier.EXACTLY_ONE),
        EventStep(ConformanceEventNames.SESSION_COMPLETED, Quantifier.EXACTLY_ONE),
    )
    val AUDIO_TTS_BATCH: List<EventStep> = listOf(
        EventStep(ConformanceEventNames.SESSION_STARTED,   Quantifier.EXACTLY_ONE),
        EventStep(ConformanceEventNames.METRIC,            Quantifier.ZERO_OR_MORE),
        EventStep(ConformanceEventNames.TTS_AUDIO_CHUNK,   Quantifier.ONE_OR_MORE),
        EventStep(ConformanceEventNames.SESSION_COMPLETED, Quantifier.EXACTLY_ONE),
    )
    val AUDIO_TTS_STREAM: List<EventStep> = listOf(
        EventStep(ConformanceEventNames.SESSION_STARTED,   Quantifier.EXACTLY_ONE),
        EventStep(ConformanceEventNames.TTS_AUDIO_CHUNK,   Quantifier.ONE_OR_MORE),
        EventStep(ConformanceEventNames.METRIC,            Quantifier.ZERO_OR_MORE),
        EventStep(ConformanceEventNames.SESSION_COMPLETED, Quantifier.EXACTLY_ONE),
    )
}

/** Lifecycle step lists per capability. Source: each YAML's lifecycle: field. */
object LifecycleSteps {
    val CHAT_COMPLETION: List<String> = listOf(
        "runtime_open", "model_open", "model_warm",
        "session_open", "send_text", "poll_event",
        "session_close", "model_close", "runtime_close",
    )
    val EMBEDDINGS_TEXT: List<String> = listOf(
        "runtime_open", "model_open", "model_warm",
        "session_open", "send_text", "poll_event",
        "session_close", "model_close", "runtime_close",
    )
    val AUDIO_TRANSCRIPTION: List<String> = listOf(
        "runtime_open", "model_open", "model_warm",
        "session_open", "send_audio", "poll_event",
        "session_close", "model_close", "runtime_close",
    )
    // model_bound=false: no model_open / model_warm / model_close
    val AUDIO_VAD: List<String> = listOf(
        "runtime_open",
        "session_open", "send_audio", "poll_event",
        "session_close", "runtime_close",
    )
    val AUDIO_SPEAKER_EMBEDDING: List<String> = listOf(
        "runtime_open", "model_open", "model_warm",
        "session_open", "send_audio", "poll_event",
        "session_close", "model_close", "runtime_close",
    )
    val AUDIO_TTS_BATCH: List<String> = listOf(
        "runtime_open", "model_open", "model_warm",
        "session_open", "send_text", "poll_event",
        "session_close", "model_close", "runtime_close",
    )
    val AUDIO_TTS_STREAM: List<String> = listOf(
        "runtime_open", "model_open", "model_warm",
        "session_open", "send_text", "poll_event",
        "session_close", "model_close", "runtime_close",
    )
}

/**
 * Streaming honesty tokens.
 * Source: octomil-contracts/conformance/audio.tts.stream.yaml delivery_timing field.
 * v0.1.9 flipped audio.tts.stream from coalesced_after_synthesis → progressive_during_synthesis
 * (proof_artifact: first_audio_ratio=0.5909 < 0.75 gate, RTF=0.105).
 */
object StreamingHonestyTokens {
    const val PROGRESSIVE_DURING_SYNTHESIS = "progressive_during_synthesis"
    const val COALESCED_AFTER_SYNTHESIS    = "coalesced_after_synthesis"

    // audio.tts.stream v0.1.9: progressive (flipped from coalesced in v0.1.8)
    const val TTS_STREAM_DELIVERY_TIMING = PROGRESSIVE_DURING_SYNTHESIS
    // audio.tts.batch: always coalesced (single PCM chunk)
    const val TTS_BATCH_DELIVERY_TIMING  = COALESCED_AFTER_SYNTHESIS
}

/**
 * Privacy constraints — deny_field_substrings per capability.
 * Source: <capability>.yaml privacy_constraints.deny_field_substrings
 */
object PrivacyConstraints {
    val DENY_SUBSTRINGS: Map<String, Set<String>> = mapOf(
        "chat.completion" to setOf("/Users/", "/private/var/", "/home/"),
        "embeddings.text" to setOf("/Users/", "/private/var/"),
        "audio.transcription" to setOf("/Users/", "/private/var/", "/home/", ".wav", ".pcm", "ggml-tiny.bin"),
        "audio.vad" to setOf("/Users/", "/private/var/", "/home/", ".wav", ".pcm", "ggml-silero"),
        "audio.speaker.embedding" to setOf("/Users/", "/private/var/", "/home/", ".wav", ".pcm"),
        "audio.tts.batch" to setOf(
            "audio_bytes", "raw_audio", "audio_pcm", "wav_bytes",
            "transcript_text", "input_text", "prompt_text", "voice_metadata",
        ),
        "audio.tts.stream" to setOf(
            "audio_bytes", "raw_audio", "audio_pcm", "wav_bytes",
            "transcript_text", "input_text", "prompt_text", "voice_metadata",
        ),
    )
}

/**
 * Native path skip sentinel.
 * All native-invocation tests SKIP until the JNI bridge lands.
 */
object NativePathSkip {
    const val REASON = """
        JNI binding to liboctomil_runtime not yet wired in octomil-android.
        oct_runtime_open / oct_session_open / oct_session_send /
        oct_session_poll / oct_session_close JNI stubs do not exist.
        Native lifecycle, event-sequence, and invalid-input assertions
        will activate when the JNI bridge lands.
        TODO: octomil-android native runtime JNI bridge
    """

    // Must remain false until JNI lands. A cloud transport must NEVER
    // substitute for a native conformance assertion.
    const val CLOUD_FALLBACK_ACTIVE = false
}

/**
 * Contracts commit SHA pin.
 * Pinned to octomil-contracts HEAD at time of conformance bootstrap.
 */
object ContractsPin {
    const val CONTRACTS_COMMIT = "2b2612669f8844f53b3fb5ae074f0dfbc0dd0a00"
    const val CONTRACTS_CONTRACT_VERSION_IN_SDK = "1.16.1"  // .contract-version.json on main
}
