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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AudioSpeechStream] Phase 4 JNI passthrough.
 *
 * Tests verify that:
 *  1. TtsChunk events surface as [TtsStreamEvent.AudioChunk].
 *  2. SessionCompleted surfaces as [TtsStreamEvent.Completed].
 *  3. The bridge is called with [RuntimeCapability.AUDIO_TTS_STREAM].
 *  4. A non-zero modelHandle is passed (model-required path).
 *  5. Text is sent via sessionSendText, not sessionSendAudio.
 *  6. Absent .so → RUNTIME_UNAVAILABLE is propagated.
 */
class AudioSpeechStreamTest {

    // -----------------------------------------------------------------------
    // Regression: facade reaches events case when bridge is wired
    // -----------------------------------------------------------------------

    @Test
    fun `stream emits audio chunks when bridge returns started, tts chunks, completed`() = runBlocking {
        val chunk1 = byteArrayOf(0x01, 0x02, 0x03)
        val chunk2 = byteArrayOf(0x04, 0x05)
        val fake = FakeTtsStreamJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_started", engine = "kokoro"),
                ),
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(
                        kind = "tts_chunk",
                        bytes = chunk1,
                        sampleRate = 24000,
                    ),
                ),
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(
                        kind = "tts_chunk",
                        bytes = chunk2,
                        sampleRate = 24000,
                    ),
                ),
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(
                        kind = "session_completed",
                        terminalStatusCode = NativeRuntimeStatus.OK.code,
                        totalLatencyMs = 80.0,
                    ),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val speechStream = AudioSpeechStream(bridge)

        val result = speechStream.stream("hello world").toList()

        val chunks = result.filterIsInstance<TtsStreamEvent.AudioChunk>()
        assertEquals(2, chunks.size)
        assertArrayEquals(chunk1, chunks[0].pcm)
        assertEquals(24000, chunks[0].sampleRate)
        assertArrayEquals(chunk2, chunks[1].pcm)

        val completed = result.filterIsInstance<TtsStreamEvent.Completed>()
        assertEquals(1, completed.size)
        assertEquals(NativeRuntimeStatus.OK.code, completed.first().terminalStatusCode)
        assertEquals(80.0, completed.first().totalLatencyMs!!, 0.001)
    }

    @Test
    fun `stream uses capability AUDIO_TTS_STREAM in session open`() = runBlocking {
        val fake = FakeTtsStreamJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val speechStream = AudioSpeechStream(bridge)

        speechStream.stream("test").toList()

        assertTrue(
            "sessionOpen must use audio.tts.stream capability",
            fake.sessionOpenCapabilities.contains(RuntimeCapability.AUDIO_TTS_STREAM.code),
        )
    }

    @Test
    fun `stream passes non-zero modelHandle (model-required path)`() = runBlocking {
        val fake = FakeTtsStreamJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val speechStream = AudioSpeechStream(bridge)

        speechStream.stream("test").toList()

        assertTrue(
            "TTS stream must use a non-zero model handle",
            fake.sessionOpenModelHandles.all { it > 0L },
        )
    }

    @Test
    fun `stream sends text via sendText not sendAudio`() = runBlocking {
        val fake = FakeTtsStreamJni(
            pollQueue = listOf(
                NativeSessionPollWire(
                    statusCode = NativeRuntimeStatus.OK.code,
                    event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = 0),
                ),
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val speechStream = AudioSpeechStream(bridge)

        speechStream.stream("hello").toList()

        assertTrue("sendText must be called", fake.sentTexts.contains("hello"))
        assertEquals("sendAudio must not be called", 0, fake.sendAudioCallCount)
    }

    // -----------------------------------------------------------------------
    // Regression: Skipped JNI (absent .so) → RUNTIME_UNAVAILABLE
    // -----------------------------------------------------------------------

    @Test
    fun `stream throws RUNTIME_UNAVAILABLE when JNI library is absent`() {
        val bridge = NativeRuntimeBridge("octomil_runtime_jni_absent_for_tts_stream_test")
        val speechStream = AudioSpeechStream(bridge)

        var caught: OctomilException? = null
        runBlocking {
            try {
                speechStream.stream("test").toList()
            } catch (e: OctomilException) {
                caught = e
            }
        }

        assertNotNull(caught)
        assertEquals(OctomilErrorCode.RUNTIME_UNAVAILABLE, caught!!.errorCode)
    }

    // -----------------------------------------------------------------------
    // Regression: empty text is rejected before bridge call
    // -----------------------------------------------------------------------

    @Test
    fun `stream throws IllegalArgumentException for empty text`() {
        val bridge = NativeRuntimeBridge("octomil_runtime_jni_absent_for_tts_empty_test")
        val speechStream = AudioSpeechStream(bridge)

        var caught: Throwable? = null
        runBlocking {
            try {
                speechStream.stream("").toList()
            } catch (e: Throwable) {
                caught = e
            }
        }

        assertNotNull(caught)
        assertTrue("empty text must throw IllegalArgumentException", caught is IllegalArgumentException)
    }
}

// ---------------------------------------------------------------------------
// Stub JNI implementation for AudioSpeechStream tests
// ---------------------------------------------------------------------------

private class FakeTtsStreamJni(
    private val pollQueue: List<NativeSessionPollWire> = emptyList(),
) : NativeRuntimeJni {
    val sessionOpenCapabilities = mutableListOf<String>()
    val sessionOpenModelHandles = mutableListOf<Long>()
    val sentTexts = mutableListOf<String>()
    var sendAudioCallCount = 0
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
        supportedEngines = arrayOf("kokoro"),
        supportedCapabilities = arrayOf(RuntimeCapability.AUDIO_TTS_STREAM.code),
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

    override fun sessionSendAudio(sessionHandle: Long, audio: NativeAudioView): NativeRuntimeStatusWire {
        sendAudioCallCount++
        return NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)
    }

    override fun sessionSendText(sessionHandle: Long, text: String): NativeRuntimeStatusWire {
        sentTexts += text
        return NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)
    }

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
