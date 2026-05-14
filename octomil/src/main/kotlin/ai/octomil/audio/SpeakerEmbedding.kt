package ai.octomil.audio

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RuntimeCapability
import ai.octomil.runtime.nativebridge.NativeAudioView
import ai.octomil.runtime.nativebridge.NativeModelConfig
import ai.octomil.runtime.nativebridge.NativeRuntimeBridge
import ai.octomil.runtime.nativebridge.NativeRuntimeConfig
import ai.octomil.runtime.nativebridge.NativeRuntimeResult
import ai.octomil.runtime.nativebridge.NativeSessionConfig
import ai.octomil.runtime.nativebridge.NativeSessionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native-runtime speaker-embedding facade — `audio.speaker.embedding`.
 *
 * Opens a model-backed `audio.speaker.embedding` session on the native C
 * runtime (via [NativeRuntimeBridge]), feeds PCM frames, and returns the
 * resulting float embedding vector as a [SpeakerEmbeddingResult].
 *
 * Lifecycle:
 *   `runtime_open → model_open → model_warm → session_open → send_audio →
 *    poll_event (loop) → session_close → model_close → runtime_close`
 *
 * Fail-closed: if the native runtime JNI library or the model artifact is
 * absent this method throws [OctomilException] with
 * [OctomilErrorCode.RUNTIME_UNAVAILABLE] or [OctomilErrorCode.MODEL_NOT_FOUND].
 */
class SpeakerEmbedding internal constructor(
    private val bridge: NativeRuntimeBridge,
    private val runtimeConfig: NativeRuntimeConfig = NativeRuntimeConfig(),
) {
    constructor() : this(NativeRuntimeBridge())

    /**
     * Compute a speaker embedding for the given audio frames.
     *
     * @param samples      PCM float samples (mono or multi-channel, interleaved).
     * @param sampleRate   Audio sample rate in Hz (typically 16000).
     * @param channels     Number of channels (typically 1).
     * @param modelUri     URI of the speaker-embedding model artifact.
     * @param pollTimeoutMs How long to wait per poll before yielding (ms).
     * @return [SpeakerEmbeddingResult] containing the float embedding vector.
     * @throws OctomilException on any native error, unavailable runtime, or
     *   missing model artifact.
     */
    suspend fun embed(
        samples: FloatArray,
        sampleRate: Int = 16000,
        channels: Int = 1,
        modelUri: String? = null,
        pollTimeoutMs: Int = 100,
    ): SpeakerEmbeddingResult = withContext(Dispatchers.IO) {
        val runtime = when (val r = bridge.open(runtimeConfig)) {
            is NativeRuntimeResult.Skipped -> throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                r.reason.message,
                r.reason.cause,
            )
            is NativeRuntimeResult.Error -> throw OctomilException(
                r.error.sdkErrorCode,
                r.error.message,
            )
            is NativeRuntimeResult.Success -> r.value
        }
        runtime.use {
            val model = when (val m = runtime.openModel(NativeModelConfig(modelUri = modelUri))) {
                is NativeRuntimeResult.Skipped -> throw OctomilException(
                    OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    m.reason.message,
                    m.reason.cause,
                )
                is NativeRuntimeResult.Error -> throw OctomilException(
                    m.error.sdkErrorCode,
                    m.error.message,
                )
                is NativeRuntimeResult.Success -> m.value
            }
            model.use {
                // Warm the model — best-effort; ignore NOT_FOUND (warmup is optional).
                model.warm()

                val sessionConfig = NativeSessionConfig(
                    capability = RuntimeCapability.AUDIO_SPEAKER_EMBEDDING,
                    sampleRateIn = sampleRate,
                )
                val session = when (val s = model.openSession(sessionConfig)) {
                    is NativeRuntimeResult.Skipped -> throw OctomilException(
                        OctomilErrorCode.RUNTIME_UNAVAILABLE,
                        s.reason.message,
                        s.reason.cause,
                    )
                    is NativeRuntimeResult.Error -> throw OctomilException(
                        s.error.sdkErrorCode,
                        s.error.message,
                    )
                    is NativeRuntimeResult.Success -> s.value
                }
                session.use {
                    val audioView = NativeAudioView(samples, sampleRate, channels)
                    when (val sent = session.sendAudio(audioView)) {
                        is NativeRuntimeResult.Error -> throw OctomilException(
                            sent.error.sdkErrorCode,
                            sent.error.message,
                        )
                        is NativeRuntimeResult.Skipped -> throw OctomilException(
                            OctomilErrorCode.RUNTIME_UNAVAILABLE,
                            sent.reason.message,
                            sent.reason.cause,
                        )
                        is NativeRuntimeResult.Success -> Unit
                    }

                    var embeddingValues: FloatArray? = null
                    var normalized = false
                    var completed = false

                    while (!completed) {
                        val polled = session.pollEvent(pollTimeoutMs)
                        when (polled) {
                            is NativeRuntimeResult.Error -> throw OctomilException(
                                polled.error.sdkErrorCode,
                                polled.error.message,
                            )
                            is NativeRuntimeResult.Skipped -> throw OctomilException(
                                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                                polled.reason.message,
                                polled.reason.cause,
                            )
                            is NativeRuntimeResult.Success -> {
                                when (val event = polled.value) {
                                    null -> Unit // timeout — continue polling
                                    is NativeSessionEvent.EmbeddingVector -> {
                                        embeddingValues = event.values
                                        normalized = event.normalized
                                    }
                                    is NativeSessionEvent.SessionCompleted -> {
                                        completed = true
                                    }
                                    is NativeSessionEvent.Error -> throw OctomilException(
                                        OctomilErrorCode.INFERENCE_FAILED,
                                        "Speaker embedding error [${event.code}]: ${event.message ?: "no detail"}",
                                    )
                                    is NativeSessionEvent.Metric,
                                    is NativeSessionEvent.SessionStarted -> Unit // informational
                                    else -> Unit // forward-compatible
                                }
                            }
                        }
                    }

                    SpeakerEmbeddingResult(
                        values = embeddingValues ?: FloatArray(0),
                        normalized = normalized,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Public result type
// ---------------------------------------------------------------------------

/**
 * Result of [SpeakerEmbedding.embed].
 *
 * @param values     Float embedding vector. Dimensions match the model's pooled
 *                   output size (model-specific, typically 192–512).
 * @param normalized True when the runtime has already L2-normalised the vector.
 */
data class SpeakerEmbeddingResult(
    val values: FloatArray,
    val normalized: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeakerEmbeddingResult) return false
        if (!values.contentEquals(other.values)) return false
        if (normalized != other.normalized) return false
        return true
    }

    override fun hashCode(): Int {
        var result = values.contentHashCode()
        result = 31 * result + normalized.hashCode()
        return result
    }
}
