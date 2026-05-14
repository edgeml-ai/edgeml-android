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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AudioDiarization] Phase 4 JNI passthrough.
 *
 * Tests verify that:
 *  1. DiarizationSegment events surface as [DiarizationEvent.Segment].
 *  2. SessionCompleted surfaces as [DiarizationEvent.Completed].
 *  3. The bridge is called with [RuntimeCapability.AUDIO_DIARIZATION].
 *  4. A non-zero modelHandle is passed (model-required path).
 *  5. Absent .so → RUNTIME_UNAVAILABLE is propagated.
 */
class AudioDiarizationTest {

    // -----------------------------------------------------------------------
    // Regression: facade reaches events case when bridge is wired
    // -----------------------------------------------------------------------

    @Test
    fun `diarize emits diarization segments when bridge returns started, segment, completed`() = runBlocking {
        val fake = FakeDiarizationJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_started", engine = "pyannote"),
                ),
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(
                        kind = "diarization_segment",
                        startMs = 0L,
                        endMs = 2500L,
                        speakerTag = 1,
                        confidence = 0.88,
                    ),
                ),
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(
                        kind = "diarization_segment",
                        startMs = 2600L,
                        endMs = 5000L,
                        speakerTag = 2,
                        confidence = 0.75,
                    ),
                ),
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(
                        kind = "session_completed",
                        terminalStatusCode = NativeRuntimeStatus.OK.code,
                        totalLatencyMs = 45.0,
                    ),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val diarization = AudioDiarization(bridge)

        val result = diarization.diarize(floatArrayOf(0.1f, 0.2f), sampleRate = 16000).toList()

        val segments = result.filterIsInstance<DiarizationEvent.Segment>()
        assertEquals(2, segments.size)

        assertEquals(0L, segments[0].startMs)
        assertEquals(2500L, segments[0].endMs)
        assertEquals(1, segments[0].speakerTag)
        assertEquals(0.88, segments[0].confidence!!, 0.001)

        assertEquals(2600L, segments[1].startMs)
        assertEquals(5000L, segments[1].endMs)
        assertEquals(2, segments[1].speakerTag)

        val completed = result.filterIsInstance<DiarizationEvent.Completed>()
        assertEquals(1, completed.size)
        assertEquals(NativeRuntimeStatus.OK.code, completed.first().terminalStatusCode)
        assertEquals(45.0, completed.first().totalLatencyMs!!, 0.001)
    }

    @Test
    fun `diarize uses capability AUDIO_DIARIZATION in session open`() = runBlocking {
        val fake = FakeDiarizationJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val diarization = AudioDiarization(bridge)

        diarization.diarize(floatArrayOf(0f)).toList()

        assertTrue(
            "sessionOpen must use audio.diarization capability",
            fake.sessionOpenCapabilities.contains(RuntimeCapability.AUDIO_DIARIZATION.code),
        )
    }

    @Test
    fun `diarize passes non-zero modelHandle (model-required path)`() = runBlocking {
        val fake = FakeDiarizationJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val diarization = AudioDiarization(bridge)

        diarization.diarize(floatArrayOf(0f)).toList()

        assertTrue(
            "diarization must use a non-zero model handle",
            fake.sessionOpenModelHandles.all { it > 0L },
        )
    }

    // -----------------------------------------------------------------------
    // Regression: Skipped JNI (absent .so) → RUNTIME_UNAVAILABLE
    // -----------------------------------------------------------------------

    @Test
    fun `diarize throws RUNTIME_UNAVAILABLE when JNI library is absent`() {
        val bridge = NativeRuntimeBridge("octomil_runtime_jni_absent_for_diarization_test")
        val diarization = AudioDiarization(bridge)

        var caught: OctomilException? = null
        runBlocking {
            try {
                diarization.diarize(floatArrayOf(0f)).toList()
            } catch (e: OctomilException) {
                caught = e
            }
        }

        assertNotNull(caught)
        assertEquals(OctomilErrorCode.RUNTIME_UNAVAILABLE, caught!!.errorCode)
    }

    // -----------------------------------------------------------------------
    // Regression: NullConfidence is allowed — segment still surfaces
    // -----------------------------------------------------------------------

    @Test
    fun `diarize emits segment without confidence when confidence is null`() = runBlocking {
        val fake = FakeDiarizationJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(
                        kind = "diarization_segment",
                        startMs = 0L,
                        endMs = 1000L,
                        speakerTag = 3,
                        confidence = null,
                    ),
                ),
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val diarization = AudioDiarization(bridge)

        val result = diarization.diarize(floatArrayOf(0f)).toList()

        val segments = result.filterIsInstance<DiarizationEvent.Segment>()
        assertEquals(1, segments.size)
        assertEquals(3, segments.first().speakerTag)
        assertTrue("confidence should be null", segments.first().confidence == null)
    }
}

// ---------------------------------------------------------------------------
// Stub JNI implementation for AudioDiarization tests
// ---------------------------------------------------------------------------

private class FakeDiarizationJni(
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
        supportedEngines = arrayOf("pyannote"),
        supportedCapabilities = arrayOf(RuntimeCapability.AUDIO_DIARIZATION.code),
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
