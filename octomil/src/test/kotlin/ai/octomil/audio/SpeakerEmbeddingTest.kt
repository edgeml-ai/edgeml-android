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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SpeakerEmbedding] Phase 4 JNI passthrough.
 *
 * Tests verify that:
 *  1. EmbeddingVector event is correctly mapped to [SpeakerEmbeddingResult].
 *  2. The bridge is called with [RuntimeCapability.AUDIO_SPEAKER_EMBEDDING].
 *  3. A non-zero modelHandle is passed (model-required path).
 *  4. Absent .so → RUNTIME_UNAVAILABLE is propagated.
 */
class SpeakerEmbeddingTest {

    // -----------------------------------------------------------------------
    // Regression: facade reaches embedding event when bridge is wired
    // -----------------------------------------------------------------------

    @Test
    fun `embed returns embedding vector when bridge returns started, vector, completed`() = runBlocking {
        val expectedValues = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val fake = FakeSpeakerEmbeddingJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_started", engine = "wespeaker"),
                ),
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(
                        kind = "embedding_vector",
                        floats = expectedValues,
                        normalized = true,
                    ),
                ),
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val speakerEmbedding = SpeakerEmbedding(bridge)

        val result = speakerEmbedding.embed(floatArrayOf(0.1f, 0.2f), sampleRate = 16000)

        assertArrayEquals(expectedValues, result.values, 0.0001f)
        assertTrue(result.normalized)
    }

    @Test
    fun `embed uses capability AUDIO_SPEAKER_EMBEDDING in session open`() = runBlocking {
        val fake = FakeSpeakerEmbeddingJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val speakerEmbedding = SpeakerEmbedding(bridge)

        speakerEmbedding.embed(floatArrayOf(0f))

        assertTrue(
            "sessionOpen must use audio.speaker.embedding capability",
            fake.sessionOpenCapabilities.contains(RuntimeCapability.AUDIO_SPEAKER_EMBEDDING.code),
        )
    }

    @Test
    fun `embed passes non-zero modelHandle (model-required path)`() = runBlocking {
        val fake = FakeSpeakerEmbeddingJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val speakerEmbedding = SpeakerEmbedding(bridge)

        speakerEmbedding.embed(floatArrayOf(0f))

        assertTrue(
            "speaker embedding must use a non-zero model handle",
            fake.sessionOpenModelHandles.all { it > 0L },
        )
    }

    // -----------------------------------------------------------------------
    // Regression: Skipped JNI (absent .so) → RUNTIME_UNAVAILABLE
    // -----------------------------------------------------------------------

    @Test
    fun `embed throws RUNTIME_UNAVAILABLE when JNI library is absent`() {
        val bridge = NativeRuntimeBridge("octomil_runtime_jni_absent_for_speaker_test")
        val speakerEmbedding = SpeakerEmbedding(bridge)

        var caught: OctomilException? = null
        runBlocking {
            try {
                speakerEmbedding.embed(floatArrayOf(0f))
            } catch (e: OctomilException) {
                caught = e
            }
        }

        assertNotNull(caught)
        assertEquals(OctomilErrorCode.RUNTIME_UNAVAILABLE, caught!!.errorCode)
    }

    // -----------------------------------------------------------------------
    // Regression: missing embedding event → returns empty vector (not crash)
    // -----------------------------------------------------------------------

    @Test
    fun `embed returns empty vector when session completes without embedding event`() = runBlocking {
        val fake = FakeSpeakerEmbeddingJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val speakerEmbedding = SpeakerEmbedding(bridge)

        val result = speakerEmbedding.embed(floatArrayOf(0f))

        assertEquals(0, result.values.size)
        assertFalse(result.normalized)
    }
}

// ---------------------------------------------------------------------------
// Stub JNI implementation for SpeakerEmbedding tests
// ---------------------------------------------------------------------------

private class FakeSpeakerEmbeddingJni(
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
        supportedEngines = arrayOf("wespeaker"),
        supportedCapabilities = arrayOf(RuntimeCapability.AUDIO_SPEAKER_EMBEDDING.code),
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
