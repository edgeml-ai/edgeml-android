package ai.octomil.audio

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RuntimeCapability
import ai.octomil.runtime.nativebridge.NativeAudioView
import ai.octomil.runtime.nativebridge.NativeModelConfig
import ai.octomil.runtime.nativebridge.NativeModelOpenWire
import ai.octomil.runtime.nativebridge.NativeRuntimeAbiVersion
import ai.octomil.runtime.nativebridge.NativeRuntimeAvailability
import ai.octomil.runtime.nativebridge.NativeRuntimeBridge
import ai.octomil.runtime.nativebridge.NativeRuntimeCacheIntrospectWire
import ai.octomil.runtime.nativebridge.NativeRuntimeCapabilitiesWire
import ai.octomil.runtime.nativebridge.NativeRuntimeConfig
import ai.octomil.runtime.nativebridge.NativeRuntimeJni
import ai.octomil.runtime.nativebridge.NativeRuntimeOpenWire
import ai.octomil.runtime.nativebridge.NativeRuntimeStatus
import ai.octomil.runtime.nativebridge.NativeRuntimeStatusWire
import ai.octomil.runtime.nativebridge.NativeSessionConfig
import ai.octomil.runtime.nativebridge.NativeSessionEventWire
import ai.octomil.runtime.nativebridge.NativeSessionOpenWire
import ai.octomil.runtime.nativebridge.NativeSessionPollWire
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AudioVad] Phase 4 JNI passthrough.
 *
 * All tests use a stub [NativeRuntimeJni] so no real native library is needed.
 * Tests verify that:
 *  1. The bridge is called with the correct capability string.
 *  2. VAD transition events surface as [VadEvent.Transition].
 *  3. SessionCompleted surfaces as [VadEvent.Completed].
 *  4. A Skipped bridge result (absent .so) throws RUNTIME_UNAVAILABLE.
 *  5. VAD uses the model-free session-open path (modelHandle = 0).
 */
class AudioVadTest {

    // -----------------------------------------------------------------------
    // Regression: facade reaches events case when bridge is wired
    // -----------------------------------------------------------------------

    @Test
    fun `detect emits vad transitions when bridge returns session started, vad segment, completed`() = runBlocking {
        val events = mutableListOf<NativeSessionPollWire>()
        events += NativeSessionPollWire(
            statusCode = NativeRuntimeStatus.OK.code,
            event = NativeSessionEventWire(kind = "session_started", engine = "silero_vad"),
        )
        events += NativeSessionPollWire(
            statusCode = NativeRuntimeStatus.OK.code,
            event = NativeSessionEventWire(
                kind = "vad_segment",
                startMs = 100L,
                endMs = 500L,
                voiced = true,
                confidence = 0.92,
            ),
        )
        events += NativeSessionPollWire(
            statusCode = NativeRuntimeStatus.OK.code,
            event = NativeSessionEventWire(
                kind = "session_completed",
                terminalStatusCode = NativeRuntimeStatus.OK.code,
                totalLatencyMs = 12.5,
            ),
        )
        val fake = FakeVadJni(pollQueue = events)
        val bridge = NativeRuntimeBridge(fake)
        val vad = AudioVad(bridge)

        val result = vad.detect(floatArrayOf(0.1f, 0.2f), sampleRate = 16000).toList()

        assertTrue("should emit at least one VadEvent", result.isNotEmpty())
        val transition = result.filterIsInstance<VadEvent.Transition>()
        assertTrue("should emit at least one Transition", transition.isNotEmpty())
        assertEquals(100L, transition.first().startMs)
        assertEquals(500L, transition.first().endMs)
        assertTrue(transition.first().voiced)
        assertEquals(0.92, transition.first().confidence!!, 0.001)

        val completed = result.filterIsInstance<VadEvent.Completed>()
        assertEquals(1, completed.size)
        assertEquals(NativeRuntimeStatus.OK.code, completed.first().terminalStatusCode)
        assertEquals(12.5, completed.first().totalLatencyMs!!, 0.001)
    }

    @Test
    fun `detect uses capability AUDIO_VAD in session open`() = runBlocking {
        val fake = FakeVadJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val vad = AudioVad(bridge)

        vad.detect(floatArrayOf(0f), sampleRate = 16000).toList()

        assertTrue(
            "sessionOpen must use audio.vad capability",
            fake.sessionOpenCapabilities.contains(RuntimeCapability.AUDIO_VAD.code),
        )
    }

    @Test
    fun `detect uses model-free session open (modelHandle = 0)`() = runBlocking {
        val fake = FakeVadJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val vad = AudioVad(bridge)

        vad.detect(floatArrayOf(0f)).toList()

        // VAD must pass modelHandle=0 — the model-free path.
        assertEquals(
            "model-free path must pass modelHandle=0",
            listOf(0L),
            fake.sessionOpenModelHandles,
        )
    }

    // -----------------------------------------------------------------------
    // Regression: Skipped JNI status (absent .so) → RUNTIME_UNAVAILABLE
    // -----------------------------------------------------------------------

    @Test
    fun `detect throws RUNTIME_UNAVAILABLE when JNI library is absent`() {
        val bridge = NativeRuntimeBridge("octomil_runtime_jni_absent_for_vad_test")
        val vad = AudioVad(bridge)

        var caught: OctomilException? = null
        runBlocking {
            try {
                vad.detect(floatArrayOf(0f)).toList()
            } catch (e: OctomilException) {
                caught = e
            }
        }

        assertFalse("must not succeed when library is absent", caught == null)
        assertEquals(OctomilErrorCode.RUNTIME_UNAVAILABLE, caught!!.errorCode)
    }

    // -----------------------------------------------------------------------
    // Regression: silence VAD segment (voiced=false) is surfaced correctly
    // -----------------------------------------------------------------------

    @Test
    fun `detect emits silence transition with voiced=false`() = runBlocking {
        val fake = FakeVadJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(
                        kind = "vad_segment",
                        startMs = 0L,
                        endMs = 200L,
                        voiced = false,
                        confidence = 0.05,
                    ),
                ),
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val vad = AudioVad(bridge)

        val result = vad.detect(floatArrayOf(0f)).toList()

        val transitions = result.filterIsInstance<VadEvent.Transition>()
        assertEquals(1, transitions.size)
        assertFalse(transitions.first().voiced)
        assertEquals(0L, transitions.first().startMs)
        assertEquals(200L, transitions.first().endMs)
    }
}

// ---------------------------------------------------------------------------
// Stub JNI implementation for AudioVad tests
// ---------------------------------------------------------------------------

private class FakeVadJni(
    private val pollQueue: List<NativeSessionPollWire> = emptyList(),
) : NativeRuntimeJni {
    val sessionOpenCapabilities = mutableListOf<String>()
    val sessionOpenModelHandles = mutableListOf<Long>()
    private var pollIndex = 0

    override fun ensureAvailable() = NativeRuntimeAvailability.Available

    override fun abiVersion() = NativeRuntimeAbiVersion(0, 10, 0)

    override fun open(config: NativeRuntimeConfig) = NativeRuntimeOpenWire(
        statusCode = NativeRuntimeStatus.OK.code,
        handle = 10L,
        message = null,
    )

    override fun capabilities(handle: Long) = NativeRuntimeCapabilitiesWire(
        statusCode = NativeRuntimeStatus.OK.code,
        message = null,
        supportedEngines = arrayOf("silero_vad"),
        supportedCapabilities = arrayOf(RuntimeCapability.AUDIO_VAD.code),
        supportedArchs = arrayOf("android-arm64"),
        ramTotalBytes = 0L,
        ramAvailableBytes = 0L,
        hasAppleSilicon = false,
        hasCuda = false,
        hasMetal = false,
    )

    override fun cacheIntrospect(runtimeHandle: Long, bufferBytes: Int) =
        NativeRuntimeCacheIntrospectWire(statusCode = NativeRuntimeStatus.UNSUPPORTED.code)

    override fun modelOpen(runtimeHandle: Long, config: NativeModelConfig) =
        NativeModelOpenWire(statusCode = NativeRuntimeStatus.OK.code, handle = 20L)

    override fun modelWarm(modelHandle: Long) = NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)

    override fun modelClose(modelHandle: Long) = Unit

    override fun sessionOpen(runtimeHandle: Long, modelHandle: Long, config: NativeSessionConfig): NativeSessionOpenWire {
        sessionOpenCapabilities += config.capability?.code ?: ""
        sessionOpenModelHandles += modelHandle
        return NativeSessionOpenWire(statusCode = NativeRuntimeStatus.OK.code, handle = 30L)
    }

    override fun sessionSendAudio(sessionHandle: Long, audio: NativeAudioView) =
        NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)

    override fun sessionSendText(sessionHandle: Long, text: String) =
        NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)

    override fun sessionPollEvent(sessionHandle: Long, timeoutMs: Int): NativeSessionPollWire {
        if (pollIndex < pollQueue.size) {
            return pollQueue[pollIndex++]
        }
        // Safety net: return session_completed so tests never loop forever.
        return NativeSessionPollWire(
            statusCode = NativeRuntimeStatus.OK.code,
            event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
        )
    }

    override fun sessionCancel(sessionHandle: Long) = NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)

    override fun sessionClose(sessionHandle: Long) = Unit

    override fun lastError(handle: Long): String? = null

    override fun lastThreadError(): String? = null

    override fun close(handle: Long) = Unit
}
