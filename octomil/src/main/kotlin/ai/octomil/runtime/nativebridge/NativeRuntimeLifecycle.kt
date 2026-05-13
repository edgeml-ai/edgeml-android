package ai.octomil.runtime.nativebridge

import ai.octomil.generated.RuntimeCapability

data class NativeModelConfig(
    val modelUri: String? = null,
    val artifactDigest: String? = null,
    val engineHint: String? = null,
    val policyPreset: String? = null,
    val acceleratorPref: Int = 0,
    val ramBudgetBytes: Long = 0L,
    val userData: Long = 0L,
) {
    init {
        require(acceleratorPref >= 0) { "acceleratorPref must be >= 0" }
        require(ramBudgetBytes >= 0L) { "ramBudgetBytes must be >= 0" }
    }
}

data class NativeSessionConfig(
    val capability: RuntimeCapability? = null,
    val modelUri: String? = null,
    val locality: String = "on_device",
    val policyPreset: String? = null,
    val speakerId: String? = null,
    val sampleRateIn: Int = 0,
    val sampleRateOut: Int = 0,
    val priority: Int = 0,
    val userData: Long = 0L,
) {
    init {
        require(locality.isNotBlank()) { "locality must not be blank" }
        require(sampleRateIn >= 0) { "sampleRateIn must be >= 0" }
        require(sampleRateOut >= 0) { "sampleRateOut must be >= 0" }
        require(priority >= 0) { "priority must be >= 0" }
    }
}

data class NativeAudioView(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int = 1,
) {
    init {
        require(sampleRate >= 0) { "sampleRate must be >= 0" }
        require(channels > 0) { "channels must be > 0" }
    }
}

sealed class NativeSessionEvent {
    data class SessionStarted(
        val engine: String?,
        val modelDigest: String?,
        val locality: String?,
        val streamingMode: String?,
    ) : NativeSessionEvent()

    data class AudioChunk(
        val pcm: ByteArray,
        val sampleRate: Int,
    ) : NativeSessionEvent()

    data class TranscriptChunk(
        val text: String,
    ) : NativeSessionEvent()

    data class TranscriptSegment(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val segmentIndex: Int,
        val isFinal: Boolean,
    ) : NativeSessionEvent()

    data class TranscriptFinal(
        val text: String,
        val nSegments: Int,
        val durationMs: Long,
    ) : NativeSessionEvent()

    data class Metric(
        val name: String,
        val value: Double,
    ) : NativeSessionEvent()

    data class EmbeddingVector(
        val values: FloatArray,
        val normalized: Boolean,
    ) : NativeSessionEvent()

    data class VadSegment(
        val startMs: Long,
        val endMs: Long,
        val voiced: Boolean,
        val confidence: Double? = null,
    ) : NativeSessionEvent()

    data class DiarizationSegment(
        val startMs: Long,
        val endMs: Long,
        val speakerTag: Int,
        val confidence: Double? = null,
    ) : NativeSessionEvent()

    data class SpeakerEmbedding(
        val values: FloatArray,
        val speakerTag: Int,
    ) : NativeSessionEvent()

    data class TtsChunk(
        val pcm: ByteArray,
        val sampleRate: Int,
    ) : NativeSessionEvent()

    data class Error(
        val code: String,
        val message: String?,
    ) : NativeSessionEvent()

    data class SessionCompleted(
        val terminalStatus: NativeRuntimeStatus,
        val totalLatencyMs: Double? = null,
    ) : NativeSessionEvent()

    data class Unknown(
        val kind: String,
        val message: String?,
    ) : NativeSessionEvent()
}

data class NativeSessionEventWire(
    val kind: String,
    val message: String? = null,
    val text: String? = null,
    val bytes: ByteArray? = null,
    val floats: FloatArray? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val sampleRate: Int? = null,
    val voiced: Boolean? = null,
    val confidence: Double? = null,
    val speakerTag: Int? = null,
    val normalized: Boolean? = null,
    val metricName: String? = null,
    val metricValue: Double? = null,
    val engine: String? = null,
    val modelDigest: String? = null,
    val locality: String? = null,
    val streamingMode: String? = null,
    val totalLatencyMs: Double? = null,
    val terminalStatusCode: Int? = null,
    val segmentIndex: Int? = null,
    val isFinal: Boolean? = null,
    val nSegments: Int? = null,
    val durationMs: Long? = null,
)

internal fun NativeSessionEventWire.toDomain(): NativeSessionEvent {
    val normalizedKind = kind.trim().lowercase().removePrefix("oct_event_")
    return when (normalizedKind) {
        "session_started" -> NativeSessionEvent.SessionStarted(
            engine = engine,
            modelDigest = modelDigest,
            locality = locality,
            streamingMode = streamingMode,
        )
        "audio_chunk" -> NativeSessionEvent.AudioChunk(
            pcm = bytes ?: ByteArray(0),
            sampleRate = sampleRate ?: 0,
        )
        "transcript_chunk" -> NativeSessionEvent.TranscriptChunk(
            text = text ?: "",
        )
        "transcript_segment" -> NativeSessionEvent.TranscriptSegment(
            text = text ?: "",
            startMs = startMs ?: 0L,
            endMs = endMs ?: 0L,
            segmentIndex = segmentIndex ?: 0,
            isFinal = isFinal ?: false,
        )
        "transcript_final" -> NativeSessionEvent.TranscriptFinal(
            text = text ?: "",
            nSegments = nSegments ?: 0,
            durationMs = durationMs ?: 0L,
        )
        "metric" -> NativeSessionEvent.Metric(
            name = metricName ?: "",
            value = metricValue ?: 0.0,
        )
        "embedding_vector" -> NativeSessionEvent.EmbeddingVector(
            values = floats ?: FloatArray(0),
            normalized = normalized ?: false,
        )
        "vad_segment" -> NativeSessionEvent.VadSegment(
            startMs = startMs ?: 0L,
            endMs = endMs ?: 0L,
            voiced = voiced ?: false,
            confidence = confidence,
        )
        "diarization_segment" -> NativeSessionEvent.DiarizationSegment(
            startMs = startMs ?: 0L,
            endMs = endMs ?: 0L,
            speakerTag = speakerTag ?: 0,
            confidence = confidence,
        )
        "speaker_embedding" -> NativeSessionEvent.SpeakerEmbedding(
            values = floats ?: FloatArray(0),
            speakerTag = speakerTag ?: 0,
        )
        "tts_chunk" -> NativeSessionEvent.TtsChunk(
            pcm = bytes ?: ByteArray(0),
            sampleRate = sampleRate ?: 0,
        )
        "error" -> NativeSessionEvent.Error(
            code = message ?: "",
            message = text,
        )
        "session_completed" -> NativeSessionEvent.SessionCompleted(
            terminalStatus = terminalStatusCode?.let(NativeRuntimeStatus::fromCode) ?: NativeRuntimeStatus.UNKNOWN,
            totalLatencyMs = totalLatencyMs,
        )
        else -> NativeSessionEvent.Unknown(
            kind = kind,
            message = message,
        )
    }
}

class NativeModel internal constructor(
    private val bridge: NativeRuntimeBridge,
    private val runtimeHandle: Long,
    val handle: Long,
) : AutoCloseable {
    private var closed = false

    fun warm(): NativeRuntimeResult<Unit> {
        if (closed) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native model handle is already closed",
                ),
            )
        }

        return bridge.modelWarm(handle)
    }

    fun openSession(config: NativeSessionConfig): NativeRuntimeResult<NativeSession> {
        if (closed) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native model handle is already closed",
                ),
            )
        }

        return bridge.openSession(runtimeHandle, handle, config)
    }

    override fun close() {
        if (!closed) {
            closed = true
            bridge.closeModel(handle)
        }
    }
}

class NativeSession internal constructor(
    private val bridge: NativeRuntimeBridge,
    val handle: Long,
) : AutoCloseable {
    private var closed = false

    fun sendAudio(audio: NativeAudioView): NativeRuntimeResult<Unit> {
        if (closed) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native session handle is already closed",
                ),
            )
        }

        return bridge.sessionSendAudio(handle, audio)
    }

    fun sendText(text: String): NativeRuntimeResult<Unit> {
        if (closed) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native session handle is already closed",
                ),
            )
        }

        return bridge.sessionSendText(handle, text)
    }

    fun pollEvent(timeoutMs: Int = 0): NativeRuntimeResult<NativeSessionEvent?> {
        if (closed) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native session handle is already closed",
                ),
            )
        }

        return bridge.sessionPollEvent(handle, timeoutMs)
    }

    fun cancel(): NativeRuntimeResult<Unit> {
        if (closed) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native session handle is already closed",
                ),
            )
        }

        return bridge.sessionCancel(handle)
    }

    override fun close() {
        if (!closed) {
            closed = true
            bridge.closeSession(handle)
        }
    }
}
