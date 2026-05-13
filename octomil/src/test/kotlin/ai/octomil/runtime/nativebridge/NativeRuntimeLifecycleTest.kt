package ai.octomil.runtime.nativebridge

import ai.octomil.generated.RuntimeCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRuntimeLifecycleTest {
    @Test
    fun `runtime model and session scaffolding route through JNI`() {
        val fake = FakeNativeRuntimeJni()
        val bridge = NativeRuntimeBridge(fake)

        val runtime = bridge.open()
        assertTrue(runtime is NativeRuntimeResult.Success)
        val openedRuntime = (runtime as NativeRuntimeResult.Success).value

        val model = openedRuntime.openModel(
            NativeModelConfig(
                modelUri = "model://demo",
                engineHint = "llama.cpp",
                policyPreset = "local_first",
                acceleratorPref = 3,
                ramBudgetBytes = 1024L,
            ),
        )
        assertTrue(model is NativeRuntimeResult.Success)
        val openedModel = (model as NativeRuntimeResult.Success).value

        val warm = openedModel.warm()
        assertTrue(warm is NativeRuntimeResult.Success)

        val session = openedModel.openSession(
            NativeSessionConfig(
                capability = RuntimeCapability.AUDIO_DIARIZATION,
                modelUri = "model://demo",
                locality = "on_device",
                policyPreset = "local_first",
                speakerId = "speaker-a",
                sampleRateIn = 16000,
                sampleRateOut = 16000,
                priority = 1,
                userData = 42L,
            ),
        )
        assertTrue(session is NativeRuntimeResult.Success)
        val openedSession = (session as NativeRuntimeResult.Success).value

        val text = openedSession.sendText("hello")
        assertTrue(text is NativeRuntimeResult.Success)

        val audio = openedSession.sendAudio(NativeAudioView(floatArrayOf(0.25f, -0.25f), 16000, 1))
        assertTrue(audio is NativeRuntimeResult.Success)

        val event = openedSession.pollEvent()
        assertTrue(event is NativeRuntimeResult.Success)
        val parsedEvent = (event as NativeRuntimeResult.Success).value
        assertTrue(parsedEvent is NativeSessionEvent.DiarizationSegment)
        val diarization = parsedEvent as NativeSessionEvent.DiarizationSegment
        assertEquals(12L, diarization.startMs)
        assertEquals(34L, diarization.endMs)
        assertEquals(7, diarization.speakerTag)
        assertFalse(diarization.confidence == null)

        val cancelled = openedSession.cancel()
        assertTrue(cancelled is NativeRuntimeResult.Success)

        openedSession.close()
        openedModel.close()
        openedRuntime.close()

        assertEquals(
            listOf(
                "open",
                "modelOpen:model://demo",
                "modelWarm",
                "sessionOpen:audio.diarization",
                "sendText:hello",
                "sendAudio:2:16000:1",
                "pollEvent:0",
                "cancel",
                "sessionClose",
                "modelClose",
                "close",
            ),
            fake.calls,
        )
    }

    @Test
    fun `poll event timeout is surfaced as an empty success result`() {
        val fake = FakeNativeRuntimeJni(
            pollWire = NativeSessionPollWire(
                statusCode = NativeRuntimeStatus.TIMEOUT.code,
                message = "no event ready",
                event = null,
            ),
        )
        val bridge = NativeRuntimeBridge(fake)

        val runtime = bridge.open()
        assertTrue(runtime is NativeRuntimeResult.Success)
        val openedRuntime = (runtime as NativeRuntimeResult.Success).value
        val model = (openedRuntime.openModel()).let { it as NativeRuntimeResult.Success }
        val openedModel = model.value
        val session = (openedModel.openSession(
            NativeSessionConfig(capability = RuntimeCapability.CHAT_COMPLETION),
        ) as NativeRuntimeResult.Success).value

        val result = session.pollEvent(25)
        assertTrue(result is NativeRuntimeResult.Success)
        assertTrue((result as NativeRuntimeResult.Success).value == null)
    }

    @Test
    fun `cache introspect routes through runtime cache ABI`() {
        val fake = FakeNativeRuntimeJni()
        val bridge = NativeRuntimeBridge(fake)

        val runtime = bridge.open()
        assertTrue(runtime is NativeRuntimeResult.Success)
        val openedRuntime = (runtime as NativeRuntimeResult.Success).value

        val snapshot = openedRuntime.cacheIntrospect()

        assertTrue(snapshot is NativeRuntimeResult.Success)
        assertEquals("""{"version":1,"is_stub":false,"entries":[]}""", (snapshot as NativeRuntimeResult.Success).value)
        assertTrue(fake.calls.contains("cacheIntrospect:65536"))
    }

    @Test
    fun `stt transcript segment and final events preserve native shape`() {
        val segment = NativeSessionEventWire(
            kind = "transcript_segment",
            text = "hello",
            startMs = 10,
            endMs = 20,
            segmentIndex = 3,
            isFinal = true,
        ).toDomain()
        assertTrue(segment is NativeSessionEvent.TranscriptSegment)
        segment as NativeSessionEvent.TranscriptSegment
        assertEquals("hello", segment.text)
        assertEquals(10L, segment.startMs)
        assertEquals(20L, segment.endMs)
        assertEquals(3, segment.segmentIndex)
        assertTrue(segment.isFinal)

        val final = NativeSessionEventWire(
            kind = "transcript_final",
            text = "hello world",
            nSegments = 4,
            durationMs = 1234,
        ).toDomain()
        assertTrue(final is NativeSessionEvent.TranscriptFinal)
        final as NativeSessionEvent.TranscriptFinal
        assertEquals("hello world", final.text)
        assertEquals(4, final.nSegments)
        assertEquals(1234L, final.durationMs)
    }
}

private class FakeNativeRuntimeJni(
    private val pollWire: NativeSessionPollWire = NativeSessionPollWire(
        statusCode = NativeRuntimeStatus.OK.code,
        event = NativeSessionEventWire(
            kind = "diarization_segment",
            startMs = 12L,
            endMs = 34L,
            speakerTag = 7,
            confidence = 0.91,
        ),
    ),
) : NativeRuntimeJni {
    val calls = mutableListOf<String>()

    override fun ensureAvailable(): NativeRuntimeAvailability = NativeRuntimeAvailability.Available

    override fun abiVersion(): NativeRuntimeAbiVersion = NativeRuntimeAbiVersion(0, 10, 0)

    override fun open(config: NativeRuntimeConfig): NativeRuntimeOpenWire {
        calls += "open"
        return NativeRuntimeOpenWire(
            statusCode = NativeRuntimeStatus.OK.code,
            handle = 11L,
            message = null,
        )
    }

    override fun capabilities(handle: Long): NativeRuntimeCapabilitiesWire = NativeRuntimeCapabilitiesWire(
        statusCode = NativeRuntimeStatus.OK.code,
        message = null,
        supportedEngines = arrayOf("fake"),
        supportedCapabilities = arrayOf(
            RuntimeCapability.AUDIO_DIARIZATION.code,
            RuntimeCapability.CHAT_COMPLETION.code,
        ),
        supportedArchs = arrayOf("android-arm64"),
        ramTotalBytes = 1024L,
        ramAvailableBytes = 512L,
        hasAppleSilicon = false,
        hasCuda = false,
        hasMetal = false,
    )

    override fun cacheIntrospect(runtimeHandle: Long, bufferBytes: Int): NativeRuntimeCacheIntrospectWire =
        NativeRuntimeCacheIntrospectWire(
            statusCode = NativeRuntimeStatus.OK.code,
            json = """{"version":1,"is_stub":false,"entries":[]}""",
        ).also { calls += "cacheIntrospect:$bufferBytes" }

    override fun modelOpen(runtimeHandle: Long, config: NativeModelConfig): NativeModelOpenWire {
        calls += "modelOpen:${config.modelUri ?: "null"}"
        return NativeModelOpenWire(
            statusCode = NativeRuntimeStatus.OK.code,
            handle = 21L,
            message = null,
        )
    }

    override fun modelWarm(modelHandle: Long): NativeRuntimeStatusWire {
        calls += "modelWarm"
        return NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)
    }

    override fun modelClose(modelHandle: Long) {
        calls += "modelClose"
    }

    override fun sessionOpen(runtimeHandle: Long, modelHandle: Long, config: NativeSessionConfig): NativeSessionOpenWire {
        calls += "sessionOpen:${config.capability?.code ?: "null"}"
        return NativeSessionOpenWire(
            statusCode = NativeRuntimeStatus.OK.code,
            handle = 31L,
            message = null,
        )
    }

    override fun sessionSendAudio(sessionHandle: Long, audio: NativeAudioView): NativeRuntimeStatusWire {
        calls += "sendAudio:${audio.samples.size}:${audio.sampleRate}:${audio.channels}"
        return NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)
    }

    override fun sessionSendText(sessionHandle: Long, text: String): NativeRuntimeStatusWire {
        calls += "sendText:$text"
        return NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)
    }

    override fun sessionPollEvent(sessionHandle: Long, timeoutMs: Int): NativeSessionPollWire {
        calls += "pollEvent:$timeoutMs"
        return pollWire
    }

    override fun sessionCancel(sessionHandle: Long): NativeRuntimeStatusWire {
        calls += "cancel"
        return NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)
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
