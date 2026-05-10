package ai.octomil.conformance

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTO-GENERATED cross-cutting conformance stub.
 * Source contract: conformance/event_sequence.yaml
 * Conformance version: 0.1.5-rc1
 * Generator: scripts/generate_conformance.py (target=kotlin)
 *
 * Cross-cutting contracts encode declarative tables. This stub registers
 * a JUnit target so the conformance manifest is honoured.
 */
class CrossCuttingConformanceTest_EVENTSEQUENCE {

    @Test
    fun `terminal session events are populated`() {
        assertTrue(TERMINAL_SESSION_EVENTS.isNotEmpty())
    }

    @Test
    fun `all event names start with OCT_EVENT_`() {
        val all = TERMINAL_SESSION_EVENTS + INTERMEDIATE_SESSION_EVENTS + RUNTIME_SCOPE_EVENTS
        for (ev in all) {
            assertTrue("${ev} must start with OCT_EVENT_", ev.startsWith("OCT_EVENT_"))
        }
    }

    @Test
    fun `runtime_scope events include OCT_EVENT_METRIC`() {
        assertTrue(RUNTIME_SCOPE_EVENTS.contains("OCT_EVENT_METRIC"))
    }
}

private val TERMINAL_SESSION_EVENTS: List<String> = listOf("OCT_EVENT_SESSION_COMPLETED")
private val INTERMEDIATE_SESSION_EVENTS: List<String> = listOf("OCT_EVENT_SESSION_STARTED", "OCT_EVENT_TRANSCRIPT_CHUNK", "OCT_EVENT_TRANSCRIPT_SEGMENT", "OCT_EVENT_TRANSCRIPT_FINAL", "OCT_EVENT_EMBEDDING_VECTOR", "OCT_EVENT_VAD_TRANSITION", "OCT_EVENT_TTS_AUDIO_CHUNK", "OCT_EVENT_DIARIZATION_SEGMENT", "OCT_EVENT_AUDIO_CHUNK", "OCT_EVENT_USER_SPEECH_DETECTED", "OCT_EVENT_TURN_ENDED", "OCT_EVENT_CAPABILITY_VERIFIED", "OCT_EVENT_INPUT_DROPPED", "OCT_EVENT_QUEUED", "OCT_EVENT_PREEMPTED", "OCT_EVENT_WATCHDOG_TIMEOUT", "OCT_EVENT_ERROR")
private val RUNTIME_SCOPE_EVENTS: List<String> = listOf("OCT_EVENT_MODEL_LOADED", "OCT_EVENT_MODEL_EVICTED", "OCT_EVENT_CACHE_HIT", "OCT_EVENT_CACHE_MISS", "OCT_EVENT_MEMORY_PRESSURE", "OCT_EVENT_THERMAL_STATE", "OCT_EVENT_METRIC")
