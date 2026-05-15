package ai.octomil.runtime.nativebridge

import ai.octomil.generated.RuntimeCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit tests for the Phase 4 session JNI bridge paths.
 *
 * All tests use [SessionFakeJni] — a minimal fake that implements
 * [NativeRuntimeJni] without touching the real JNI boundary. No audio
 * facade files are touched here; the wiring is entirely below the bridge
 * surface.
 */
class NativeRuntimeSessionBridgeTest {

    // ── openSession ──────────────────────────────────────────────────────────

    @Test
    fun `openSession routes through JNI with correct capability code`() {
        val fake = SessionFakeJni()
        val bridge = NativeRuntimeBridge(fake)
        val runtime = bridge.open().successValue<NativeRuntime>()
        val model = runtime.openModel().successValue<NativeModel>()

        val result = model.openSession(
            NativeSessionConfig(
                capability = RuntimeCapability.AUDIO_VAD,
                sampleRateIn = 16_000,
            ),
        )

        assertTrue(result is NativeRuntimeResult.Success)
        assertTrue(fake.calls.contains("sessionOpen:audio.vad:16000"))
    }

    @Test
    fun `openSession returns Error when model handle is zero`() {
        val bridge = NativeRuntimeBridge(SessionFakeJni())
        // Directly invoke internal bridge method with zero model handle.
        val result = bridge.openSession(
            runtimeHandle = 11L,
            modelHandle = 0L,
            config = NativeSessionConfig(capability = RuntimeCapability.AUDIO_VAD),
        )

        assertTrue(result is NativeRuntimeResult.Error)
        val error = (result as NativeRuntimeResult.Error).error
        assertEquals(NativeRuntimeStatus.INVALID_INPUT, error.status)
        assertTrue(error.message.contains("model handle is not open"))
    }

    @Test
    fun `openSession returns Error when runtime handle is zero`() {
        val bridge = NativeRuntimeBridge(SessionFakeJni())
        val result = bridge.openSession(
            runtimeHandle = 0L,
            modelHandle = 21L,
            config = NativeSessionConfig(),
        )

        assertTrue(result is NativeRuntimeResult.Error)
        val error = (result as NativeRuntimeResult.Error).error
        assertEquals(NativeRuntimeStatus.INVALID_INPUT, error.status)
        assertTrue(error.message.contains("runtime handle is not open"))
    }

    @Test
    fun `openSession propagates JNI error status`() {
        val fake = SessionFakeJni(
            sessionOpenWire = NativeSessionOpenWire(
                statusCode = NativeRuntimeStatus.BUSY.code,
                handle = 0L,
                message = "all slots occupied",
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val runtime = bridge.open().successValue<NativeRuntime>()
        val model = runtime.openModel().successValue<NativeModel>()

        val result = model.openSession(NativeSessionConfig())

        assertTrue(result is NativeRuntimeResult.Error)
        val error = (result as NativeRuntimeResult.Error).error
        assertEquals(NativeRuntimeStatus.BUSY, error.status)
        assertEquals("all slots occupied", error.message)
    }

    @Test
    fun `openSession wraps OK-but-zero-handle as internal error`() {
        val fake = SessionFakeJni(
            sessionOpenWire = NativeSessionOpenWire(
                statusCode = NativeRuntimeStatus.OK.code,
                handle = 0L,
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val runtime = bridge.open().successValue<NativeRuntime>()
        val model = runtime.openModel().successValue<NativeModel>()

        val result = model.openSession(NativeSessionConfig())

        assertTrue(result is NativeRuntimeResult.Error)
        assertEquals(NativeRuntimeStatus.INTERNAL, (result as NativeRuntimeResult.Error).error.status)
    }

    // ── openSessionModelFree ─────────────────────────────────────────────────

    @Test
    fun `openSessionModelFree routes through JNI without a model handle`() {
        val fake = SessionFakeJni()
        val bridge = NativeRuntimeBridge(fake)
        val runtime = bridge.open().successValue<NativeRuntime>()

        val result = runtime.openSessionModelFree(
            NativeSessionConfig(
                capability = RuntimeCapability.AUDIO_DIARIZATION,
                sampleRateIn = 16_000,
            ),
        )

        assertTrue(result is NativeRuntimeResult.Success)
        assertTrue(fake.calls.contains("sessionOpenModelFree:audio.diarization:16000"))
        // No modelOpen call should appear — model-free path.
        assertFalse(fake.calls.any { it.startsWith("modelOpen") })
    }

    @Test
    fun `openSessionModelFree returns Error when runtime handle is zero`() {
        val bridge = NativeRuntimeBridge(SessionFakeJni())
        val result = bridge.openSessionModelFree(
            runtimeHandle = 0L,
            config = NativeSessionConfig(capability = RuntimeCapability.AUDIO_VAD),
        )

        assertTrue(result is NativeRuntimeResult.Error)
        assertEquals(NativeRuntimeStatus.INVALID_INPUT, (result as NativeRuntimeResult.Error).error.status)
    }

    @Test
    fun `openSessionModelFree fails closed when runtime is already closed`() {
        val fake = SessionFakeJni()
        val bridge = NativeRuntimeBridge(fake)
        val runtime = bridge.open().successValue<NativeRuntime>()
        runtime.close()

        val result = runtime.openSessionModelFree(NativeSessionConfig())

        assertTrue(result is NativeRuntimeResult.Error)
        assertEquals(NativeRuntimeStatus.INVALID_INPUT, (result as NativeRuntimeResult.Error).error.status)
        assertTrue((result as NativeRuntimeResult.Error).error.message.contains("already closed"))
    }

    // ── sessionSendAudio ─────────────────────────────────────────────────────

    @Test
    fun `sessionSendAudio passes samples, sampleRate, and channels through bridge`() {
        val fake = SessionFakeJni()
        val bridge = NativeRuntimeBridge(fake)
        val session = openSession(bridge)

        val result = session.sendAudio(NativeAudioView(floatArrayOf(0.1f, -0.1f, 0.2f), 16_000, 1))

        assertTrue(result is NativeRuntimeResult.Success)
        assertTrue(fake.calls.contains("sendAudio:3:16000:1"))
    }

    @Test
    fun `sessionSendAudio returns Error when session handle is already closed`() {
        val fake = SessionFakeJni()
        val bridge = NativeRuntimeBridge(fake)
        val session = openSession(bridge)
        session.close()

        val result = session.sendAudio(NativeAudioView(floatArrayOf(0.0f), 16_000))

        assertTrue(result is NativeRuntimeResult.Error)
        assertEquals(NativeRuntimeStatus.INVALID_INPUT, (result as NativeRuntimeResult.Error).error.status)
        assertTrue((result as NativeRuntimeResult.Error).error.message.contains("already closed"))
    }

    @Test
    fun `sessionSendAudio propagates JNI error status`() {
        val fake = SessionFakeJni(
            sendAudioWire = NativeRuntimeStatusWire(
                statusCode = NativeRuntimeStatus.INTERNAL.code,
                message = "encoder overflow",
            ),
        )
        val session = openSession(NativeRuntimeBridge(fake))

        val result = session.sendAudio(NativeAudioView(floatArrayOf(0.0f), 16_000))

        assertTrue(result is NativeRuntimeResult.Error)
        assertEquals(NativeRuntimeStatus.INTERNAL, (result as NativeRuntimeResult.Error).error.status)
        assertEquals("encoder overflow", (result as NativeRuntimeResult.Error).error.message)
    }

    // ── sessionSendText ──────────────────────────────────────────────────────

    @Test
    fun `sessionSendText routes text payload to JNI`() {
        val fake = SessionFakeJni()
        val bridge = NativeRuntimeBridge(fake)
        val session = openSession(bridge)

        val result = session.sendText("translate: hello world")

        assertTrue(result is NativeRuntimeResult.Success)
        assertTrue(fake.calls.contains("sendText:translate: hello world"))
    }

    @Test
    fun `sessionSendText returns Error when session is closed`() {
        val session = openSession(NativeRuntimeBridge(SessionFakeJni()))
        session.close()

        val result = session.sendText("ignored")

        assertTrue(result is NativeRuntimeResult.Error)
        assertEquals(NativeRuntimeStatus.INVALID_INPUT, (result as NativeRuntimeResult.Error).error.status)
    }

    @Test
    fun `sessionSendText propagates JNI error`() {
        val fake = SessionFakeJni(
            sendTextWire = NativeRuntimeStatusWire(
                statusCode = NativeRuntimeStatus.UNSUPPORTED.code,
                message = "text input not supported for this capability",
            ),
        )
        val result = openSession(NativeRuntimeBridge(fake)).sendText("hello")

        assertTrue(result is NativeRuntimeResult.Error)
        assertEquals(NativeRuntimeStatus.UNSUPPORTED, (result as NativeRuntimeResult.Error).error.status)
    }

    // ── sessionPollEvent ─────────────────────────────────────────────────────

    @Test
    fun `pollEvent with TIMEOUT status returns null success`() {
        val fake = SessionFakeJni(
            pollWire = NativeSessionPollWire(
                statusCode = NativeRuntimeStatus.TIMEOUT.code,
                event = null,
            ),
        )
        val result = openSession(NativeRuntimeBridge(fake)).pollEvent(50)

        assertTrue(result is NativeRuntimeResult.Success)
        assertNull((result as NativeRuntimeResult.Success).value)
    }

    @Test
    fun `pollEvent passes timeout_ms through to JNI`() {
        val fake = SessionFakeJni()
        openSession(NativeRuntimeBridge(fake)).pollEvent(250)

        assertTrue(fake.calls.contains("pollEvent:250"))
    }

    @Test
    fun `pollEvent returns Error when session is closed`() {
        val session = openSession(NativeRuntimeBridge(SessionFakeJni()))
        session.close()

        val result = session.pollEvent(0)

        assertTrue(result is NativeRuntimeResult.Error)
        assertEquals(NativeRuntimeStatus.INVALID_INPUT, (result as NativeRuntimeResult.Error).error.status)
    }

    @Test
    fun `pollEvent propagates JNI error status`() {
        val fake = SessionFakeJni(
            pollWire = NativeSessionPollWire(
                statusCode = NativeRuntimeStatus.INTERNAL.code,
                message = "native poll crashed",
            ),
        )
        val result = openSession(NativeRuntimeBridge(fake)).pollEvent(0)

        assertTrue(result is NativeRuntimeResult.Error)
        assertEquals(NativeRuntimeStatus.INTERNAL, (result as NativeRuntimeResult.Error).error.status)
    }

    // ── wire→domain event parsing ─────────────────────────────────────────────

    @Test
    fun `session_started wire maps to SessionStarted domain event`() {
        val event = NativeSessionEventWire(
            kind = "session_started",
            engine = "whisper_cpp",
            modelDigest = "sha256:abc",
            locality = "on_device",
            streamingMode = "batch",
        ).toDomain()

        assertTrue(event is NativeSessionEvent.SessionStarted)
        event as NativeSessionEvent.SessionStarted
        assertEquals("whisper_cpp", event.engine)
        assertEquals("sha256:abc", event.modelDigest)
        assertEquals("on_device", event.locality)
        assertEquals("batch", event.streamingMode)
    }

    @Test
    fun `audio_chunk wire maps to AudioChunk with copied PCM bytes`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val event = NativeSessionEventWire(
            kind = "audio_chunk",
            bytes = pcm,
            sampleRate = 22_050,
        ).toDomain()

        assertTrue(event is NativeSessionEvent.AudioChunk)
        event as NativeSessionEvent.AudioChunk
        assertTrue(pcm.contentEquals(event.pcm))
        assertEquals(22_050, event.sampleRate)
    }

    @Test
    fun `transcript_chunk wire maps to TranscriptChunk`() {
        val event = NativeSessionEventWire(
            kind = "transcript_chunk",
            text = "hello",
        ).toDomain()

        assertTrue(event is NativeSessionEvent.TranscriptChunk)
        assertEquals("hello", (event as NativeSessionEvent.TranscriptChunk).text)
    }

    @Test
    fun `transcript_segment wire maps to TranscriptSegment preserving all fields`() {
        val event = NativeSessionEventWire(
            kind = "transcript_segment",
            text = "world",
            startMs = 100L,
            endMs = 200L,
            segmentIndex = 5,
            isFinal = true,
        ).toDomain()

        assertTrue(event is NativeSessionEvent.TranscriptSegment)
        event as NativeSessionEvent.TranscriptSegment
        assertEquals("world", event.text)
        assertEquals(100L, event.startMs)
        assertEquals(200L, event.endMs)
        assertEquals(5, event.segmentIndex)
        assertTrue(event.isFinal)
    }

    @Test
    fun `transcript_final wire maps to TranscriptFinal`() {
        val event = NativeSessionEventWire(
            kind = "transcript_final",
            text = "the full text",
            nSegments = 3,
            durationMs = 4500L,
        ).toDomain()

        assertTrue(event is NativeSessionEvent.TranscriptFinal)
        event as NativeSessionEvent.TranscriptFinal
        assertEquals("the full text", event.text)
        assertEquals(3, event.nSegments)
        assertEquals(4500L, event.durationMs)
    }

    @Test
    fun `metric wire maps to Metric`() {
        val event = NativeSessionEventWire(
            kind = "metric",
            metricName = "rtf",
            metricValue = 0.42,
        ).toDomain()

        assertTrue(event is NativeSessionEvent.Metric)
        event as NativeSessionEvent.Metric
        assertEquals("rtf", event.name)
        assertEquals(0.42, event.value, 1e-9)
    }

    @Test
    fun `embedding_vector wire maps to EmbeddingVector with copied floats`() {
        val floats = floatArrayOf(0.1f, 0.2f, 0.3f)
        val event = NativeSessionEventWire(
            kind = "embedding_vector",
            floats = floats,
            normalized = true,
        ).toDomain()

        assertTrue(event is NativeSessionEvent.EmbeddingVector)
        event as NativeSessionEvent.EmbeddingVector
        assertTrue(floats.contentEquals(event.values))
        assertTrue(event.normalized)
    }

    @Test
    fun `vad_segment wire maps to VadSegment with voiced flag and confidence`() {
        val event = NativeSessionEventWire(
            kind = "vad_segment",
            startMs = 500L,
            endMs = 500L,
            voiced = true,
            confidence = 0.97,
        ).toDomain()

        assertTrue(event is NativeSessionEvent.VadSegment)
        event as NativeSessionEvent.VadSegment
        assertEquals(500L, event.startMs)
        assertTrue(event.voiced)
        assertNotNull(event.confidence)
        assertEquals(0.97, event.confidence!!, 1e-9)
    }

    @Test
    fun `diarization_segment wire maps to DiarizationSegment with speaker tag`() {
        val event = NativeSessionEventWire(
            kind = "diarization_segment",
            startMs = 1000L,
            endMs = 3000L,
            speakerTag = 2,
            confidence = 0.85,
        ).toDomain()

        assertTrue(event is NativeSessionEvent.DiarizationSegment)
        event as NativeSessionEvent.DiarizationSegment
        assertEquals(1000L, event.startMs)
        assertEquals(3000L, event.endMs)
        assertEquals(2, event.speakerTag)
        assertNotNull(event.confidence)
    }

    @Test
    fun `tts_chunk wire maps to TtsChunk`() {
        val pcm = byteArrayOf(10, 20, 30)
        val event = NativeSessionEventWire(
            kind = "tts_chunk",
            bytes = pcm,
            sampleRate = 24_000,
        ).toDomain()

        assertTrue(event is NativeSessionEvent.TtsChunk)
        event as NativeSessionEvent.TtsChunk
        assertTrue(pcm.contentEquals(event.pcm))
        assertEquals(24_000, event.sampleRate)
    }

    @Test
    fun `error wire maps to Error with code and message`() {
        val event = NativeSessionEventWire(
            kind = "error",
            message = "INFERENCE_FAILED",
            text = "model returned NaN",
        ).toDomain()

        assertTrue(event is NativeSessionEvent.Error)
        event as NativeSessionEvent.Error
        assertEquals("INFERENCE_FAILED", event.code)
        assertEquals("model returned NaN", event.message)
    }

    @Test
    fun `session_completed wire maps to SessionCompleted with terminal status`() {
        val event = NativeSessionEventWire(
            kind = "session_completed",
            terminalStatusCode = NativeRuntimeStatus.OK.code,
            totalLatencyMs = 123.0,
        ).toDomain()

        assertTrue(event is NativeSessionEvent.SessionCompleted)
        event as NativeSessionEvent.SessionCompleted
        assertEquals(NativeRuntimeStatus.OK, event.terminalStatus)
        assertNotNull(event.totalLatencyMs)
        assertEquals(123.0, event.totalLatencyMs!!, 1e-9)
    }

    @Test
    fun `unknown event kind maps to Unknown`() {
        val event = NativeSessionEventWire(
            kind = "future_event_type_xyz",
            message = "not recognised",
        ).toDomain()

        assertTrue(event is NativeSessionEvent.Unknown)
        event as NativeSessionEvent.Unknown
        assertEquals("future_event_type_xyz", event.kind)
        assertEquals("not recognised", event.message)
    }

    // ── sessionCancel ────────────────────────────────────────────────────────

    @Test
    fun `sessionCancel routes to JNI and returns Success`() {
        val fake = SessionFakeJni()
        val session = openSession(NativeRuntimeBridge(fake))

        val result = session.cancel()

        assertTrue(result is NativeRuntimeResult.Success)
        assertTrue(fake.calls.contains("cancel"))
    }

    @Test
    fun `sessionCancel treats CANCELLED status as success`() {
        val fake = SessionFakeJni(
            cancelWire = NativeRuntimeStatusWire(NativeRuntimeStatus.CANCELLED.code),
        )
        val result = openSession(NativeRuntimeBridge(fake)).cancel()

        assertTrue(result is NativeRuntimeResult.Success)
    }

    @Test
    fun `sessionCancel returns Error when session is closed`() {
        val session = openSession(NativeRuntimeBridge(SessionFakeJni()))
        session.close()

        val result = session.cancel()

        assertTrue(result is NativeRuntimeResult.Error)
        assertEquals(NativeRuntimeStatus.INVALID_INPUT, (result as NativeRuntimeResult.Error).error.status)
    }

    @Test
    fun `sessionCancel propagates non-OK non-CANCELLED status as Error`() {
        val fake = SessionFakeJni(
            cancelWire = NativeRuntimeStatusWire(
                statusCode = NativeRuntimeStatus.INTERNAL.code,
                message = "cancel failed internally",
            ),
        )
        val result = openSession(NativeRuntimeBridge(fake)).cancel()

        assertTrue(result is NativeRuntimeResult.Error)
        assertEquals(NativeRuntimeStatus.INTERNAL, (result as NativeRuntimeResult.Error).error.status)
    }

    // ── sessionClose (best-effort) ────────────────────────────────────────────

    @Test
    fun `sessionClose routes to JNI and is idempotent`() {
        val fake = SessionFakeJni()
        val session = openSession(NativeRuntimeBridge(fake))

        session.close()
        session.close()  // Second call must be a no-op (handle already closed).

        // JNI sessionClose must only be called once.
        assertEquals(1, fake.calls.count { it == "sessionClose" })
    }

    // ── JNI unavailability propagation ───────────────────────────────────────

    @Test
    fun `bridge methods propagate Skipped when library is not present`() {
        val bridge = NativeRuntimeBridge("octomil_jni_library_missing_for_phase4_test")

        // open() must report Skipped — not Error.
        val open = bridge.open()
        assertTrue(open is NativeRuntimeResult.Skipped)
        val skip = (open as NativeRuntimeResult.Skipped).reason
        assertEquals(NativeRuntimeStatus.RUNTIME_UNAVAILABLE, skip.status)
        assertTrue(skip.message.contains("octomil_jni_library_missing_for_phase4_test"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Open a fully-wired session using a model path. */
    private fun openSession(bridge: NativeRuntimeBridge): NativeSession {
        val runtime = bridge.open().successValue<NativeRuntime>()
        val model = runtime.openModel().successValue<NativeModel>()
        return model.openSession(NativeSessionConfig()).successValue()
    }

    /** Unwrap Success or fail the test. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> NativeRuntimeResult<T>.successValue(): T {
        assertTrue("Expected Success but got $this", this is NativeRuntimeResult.Success)
        return (this as NativeRuntimeResult.Success<T>).value
    }
}

// ── Fake JNI implementation for session tests ────────────────────────────────

private class SessionFakeJni(
    private val sessionOpenWire: NativeSessionOpenWire = NativeSessionOpenWire(
        statusCode = NativeRuntimeStatus.OK.code,
        handle = 31L,
    ),
    private val sendAudioWire: NativeRuntimeStatusWire = NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code),
    private val sendTextWire: NativeRuntimeStatusWire = NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code),
    private val sendImageWire: NativeRuntimeStatusWire = NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code),
    private val pollWire: NativeSessionPollWire = NativeSessionPollWire(
        statusCode = NativeRuntimeStatus.OK.code,
        event = NativeSessionEventWire(kind = "session_completed", terminalStatusCode = NativeRuntimeStatus.OK.code),
    ),
    private val cancelWire: NativeRuntimeStatusWire = NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code),
) : NativeRuntimeJni {

    val calls = mutableListOf<String>()

    override fun ensureAvailable(): NativeRuntimeAvailability = NativeRuntimeAvailability.Available

    override fun abiVersion(): NativeRuntimeAbiVersion = NativeRuntimeAbiVersion(0, 10, 0)

    override fun open(config: NativeRuntimeConfig): NativeRuntimeOpenWire {
        calls += "open"
        return NativeRuntimeOpenWire(statusCode = NativeRuntimeStatus.OK.code, handle = 11L, message = null)
    }

    override fun capabilities(handle: Long): NativeRuntimeCapabilitiesWire = NativeRuntimeCapabilitiesWire(
        statusCode = NativeRuntimeStatus.OK.code,
        message = null,
        supportedEngines = arrayOf("fake_session"),
        supportedCapabilities = arrayOf(
            RuntimeCapability.AUDIO_VAD.code,
            RuntimeCapability.AUDIO_DIARIZATION.code,
            RuntimeCapability.AUDIO_SPEAKER_EMBEDDING.code,
            RuntimeCapability.AUDIO_TTS_STREAM.code,
        ),
        supportedArchs = arrayOf("android-arm64"),
        ramTotalBytes = 2048L,
        ramAvailableBytes = 1024L,
        hasAppleSilicon = false,
        hasCuda = false,
        hasMetal = false,
    )

    override fun cacheIntrospect(runtimeHandle: Long, bufferBytes: Int): NativeRuntimeCacheIntrospectWire =
        NativeRuntimeCacheIntrospectWire(statusCode = NativeRuntimeStatus.OK.code, json = "{}")

    override fun modelOpen(runtimeHandle: Long, config: NativeModelConfig): NativeModelOpenWire {
        calls += "modelOpen:${config.modelUri ?: "null"}"
        return NativeModelOpenWire(statusCode = NativeRuntimeStatus.OK.code, handle = 21L)
    }

    override fun modelWarm(modelHandle: Long): NativeRuntimeStatusWire =
        NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)

    override fun modelClose(modelHandle: Long) {
        calls += "modelClose"
    }

    override fun sessionOpen(
        runtimeHandle: Long,
        modelHandle: Long,
        config: NativeSessionConfig,
    ): NativeSessionOpenWire {
        calls += "sessionOpen:${config.capability?.code ?: "null"}:${config.sampleRateIn}"
        return sessionOpenWire
    }

    override fun sessionOpenModelFree(
        runtimeHandle: Long,
        config: NativeSessionConfig,
    ): NativeSessionOpenWire {
        calls += "sessionOpenModelFree:${config.capability?.code ?: "null"}:${config.sampleRateIn}"
        return sessionOpenWire
    }

    override fun sessionSendAudio(sessionHandle: Long, audio: NativeAudioView): NativeRuntimeStatusWire {
        calls += "sendAudio:${audio.samples.size}:${audio.sampleRate}:${audio.channels}"
        return sendAudioWire
    }

    override fun sessionSendText(sessionHandle: Long, text: String): NativeRuntimeStatusWire {
        calls += "sendText:$text"
        return sendTextWire
    }

    override fun sessionSendImage(sessionHandle: Long, image: NativeImageView): NativeRuntimeStatusWire {
        calls += "sendImage:${image.byteLength}:${image.mime.code}"
        return sendImageWire
    }

    override fun sessionPollEvent(sessionHandle: Long, timeoutMs: Int): NativeSessionPollWire {
        calls += "pollEvent:$timeoutMs"
        return pollWire
    }

    override fun sessionCancel(sessionHandle: Long): NativeRuntimeStatusWire {
        calls += "cancel"
        return cancelWire
    }

    override fun sessionClose(sessionHandle: Long) {
        calls += "sessionClose"
    }

    override fun lastError(handle: Long): String? = null

    override fun lastThreadError(): String? = null

    override fun close(handle: Long) {
        calls += "close"
    }
}
