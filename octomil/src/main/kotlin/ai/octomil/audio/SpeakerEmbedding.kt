package ai.octomil.audio

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RuntimeCapability
import ai.octomil.runtime.nativebridge.NativeAudioView
import ai.octomil.runtime.nativebridge.NativeRuntimeBridge
import ai.octomil.runtime.nativebridge.NativeRuntimeConfig
import ai.octomil.runtime.nativebridge.NativeRuntimeResult
import ai.octomil.runtime.nativebridge.NativeRuntimeStatus
import ai.octomil.runtime.nativebridge.NativeSessionConfig
import ai.octomil.runtime.nativebridge.NativeSessionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Public facade for `audio.speaker.embedding` — speaker verification
 * embeddings.
 *
 * Mirrors Python `octomil.audio.speaker_embedding`
 * (`NativeSpeakerEmbeddingBackend`). Hard-cut to the native runtime:
 * there is no Kotlin speaker-embedding fallback. When the underlying
 * JNI session is absent or the capability is not advertised, [create]
 * surfaces [OctomilErrorCode.RUNTIME_UNAVAILABLE].
 *
 * The returned [FloatArray] is L2-normalized (the runtime provides the
 * normalization). Embedding dimension is model-dependent; do NOT
 * hardcode 512 in downstream comparisons — read it from the returned
 * array's `size`.
 *
 * Usage:
 * ```kotlin
 * val embedding = SpeakerEmbedding()
 * val vec = embedding.create(samples, sampleRate = 16_000)
 * ```
 *
 * This is a **LIVE_NATIVE_CONDITIONAL** capability: the runtime
 * advertises `audio.speaker.embedding` only when the ERes2NetV2 model
 * artifact and environment gates pass.
 *
 * @param bridge Injected for tests; defaults to the real JNI bridge.
 */
class SpeakerEmbedding internal constructor(
    private val bridge: NativeRuntimeBridge = NativeRuntimeBridge(),
) {
    constructor() : this(NativeRuntimeBridge())

    /**
     * Compute a speaker embedding for [audio] (mono PCM-f32,
     * [sampleRate] Hz).
     *
     * Opens a native runtime → model → session, feeds [audio], drains
     * the [NativeSessionEvent.SpeakerEmbedding] event, then closes
     * everything.
     *
     * @param audio Mono PCM float32 samples.
     * @param sampleRate Sample rate in Hz (typically 16 000).
     * @return L2-normalized speaker embedding as a [FloatArray].
     * @throws OctomilException [OctomilErrorCode.RUNTIME_UNAVAILABLE] when
     *   the native runtime is absent or does not advertise
     *   `audio.speaker.embedding`.
     * @throws OctomilException [OctomilErrorCode.INFERENCE_FAILED] when the
     *   session completes without yielding an embedding vector.
     */
    suspend fun create(
        audio: FloatArray,
        sampleRate: Int,
    ): FloatArray = withContext(Dispatchers.IO) {
        require(audio.isNotEmpty()) { "audio must not be empty" }
        require(sampleRate > 0) { "sampleRate must be > 0" }

        val runtime = when (val opened = bridge.open(NativeRuntimeConfig())) {
            is NativeRuntimeResult.Success -> opened.value
            is NativeRuntimeResult.Error -> throw OctomilException(
                errorCode = opened.error.sdkErrorCode,
                message = "audio.speaker.embedding: native runtime open failed — ${opened.error.message}",
            )
            is NativeRuntimeResult.Skipped -> throw OctomilException(
                errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                message = "audio.speaker.embedding: native runtime unavailable — ${opened.reason.message}",
            )
        }

        runtime.use {
            val caps = when (val c = runtime.capabilities()) {
                is NativeRuntimeResult.Success -> c.value
                is NativeRuntimeResult.Error -> throw OctomilException(
                    errorCode = c.error.sdkErrorCode,
                    message = "audio.speaker.embedding: capabilities query failed — ${c.error.message}",
                )
                is NativeRuntimeResult.Skipped -> throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.speaker.embedding: capabilities skipped — ${c.reason.message}",
                )
            }
            if (RuntimeCapability.AUDIO_SPEAKER_EMBEDDING !in caps.supportedCapabilities) {
                throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.speaker.embedding: runtime does not advertise " +
                        "'${RuntimeCapability.AUDIO_SPEAKER_EMBEDDING.code}'. " +
                        "Ensure the native runtime was built with the ERes2NetV2 engine " +
                        "and OCTOMIL_SHERPA_SPEAKER_MODEL is set.",
                )
            }

            val model = when (val m = runtime.openModel()) {
                is NativeRuntimeResult.Success -> m.value
                is NativeRuntimeResult.Error -> throw OctomilException(
                    errorCode = m.error.sdkErrorCode,
                    message = "audio.speaker.embedding: model open failed — ${m.error.message}",
                )
                is NativeRuntimeResult.Skipped -> throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.speaker.embedding: model open skipped — ${m.reason.message}",
                )
            }

            model.use {
                val session = when (
                    val s = model.openSession(
                        NativeSessionConfig(
                            capability = RuntimeCapability.AUDIO_SPEAKER_EMBEDDING,
                            sampleRateIn = sampleRate,
                        ),
                    )
                ) {
                    is NativeRuntimeResult.Success -> s.value
                    is NativeRuntimeResult.Error -> throw OctomilException(
                        errorCode = s.error.sdkErrorCode,
                        message = "audio.speaker.embedding: session open failed — ${s.error.message}",
                    )
                    is NativeRuntimeResult.Skipped -> throw OctomilException(
                        errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                        message = "audio.speaker.embedding: session open skipped — ${s.reason.message}",
                    )
                }

                session.use {
                    when (val sent = session.sendAudio(NativeAudioView(audio, sampleRate))) {
                        is NativeRuntimeResult.Error -> throw OctomilException(
                            errorCode = sent.error.sdkErrorCode,
                            message = "audio.speaker.embedding: sendAudio failed — ${sent.error.message}",
                        )
                        is NativeRuntimeResult.Skipped -> throw OctomilException(
                            errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                            message = "audio.speaker.embedding: sendAudio skipped — ${sent.reason.message}",
                        )
                        is NativeRuntimeResult.Success -> Unit
                    }

                    drainEmbeddingEvents(session)
                }
            }
        }
    }

    internal fun drainEmbeddingEvents(
        session: ai.octomil.runtime.nativebridge.NativeSession,
        drainDeadlineMs: Long = DRAIN_DEADLINE_MS,
    ): FloatArray {
        var embeddingVector: FloatArray? = null
        val deadlineMs = System.currentTimeMillis() + drainDeadlineMs
        var sawCompleted = false

        while (System.currentTimeMillis() < deadlineMs) {
            val result = session.pollEvent(timeoutMs = POLL_TIMEOUT_MS)
            when (result) {
                is NativeRuntimeResult.Error -> throw OctomilException(
                    errorCode = result.error.sdkErrorCode,
                    message = "audio.speaker.embedding: pollEvent failed — ${result.error.message}",
                )
                is NativeRuntimeResult.Skipped -> throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.speaker.embedding: pollEvent skipped — ${result.reason.message}",
                )
                is NativeRuntimeResult.Success -> {
                    when (val ev = result.value) {
                        null -> continue
                        is NativeSessionEvent.SpeakerEmbedding -> {
                            embeddingVector = ev.values
                        }
                        is NativeSessionEvent.EmbeddingVector -> {
                            // Accept EmbeddingVector as an alternative wire shape.
                            embeddingVector = ev.values
                        }
                        is NativeSessionEvent.SessionCompleted -> {
                            if (ev.terminalStatus != NativeRuntimeStatus.OK) {
                                throw OctomilException(
                                    errorCode = ev.terminalStatus.toSdkErrorCode()
                                        ?: OctomilErrorCode.UNKNOWN,
                                    message = "audio.speaker.embedding: session completed with " +
                                        "error ${ev.terminalStatus.cName}",
                                )
                            }
                            sawCompleted = true
                            break
                        }
                        is NativeSessionEvent.Error -> throw OctomilException(
                            errorCode = OctomilErrorCode.INFERENCE_FAILED,
                            message = "audio.speaker.embedding: session error — ${ev.message ?: ev.code}",
                        )
                        else -> continue
                    }
                }
            }
        }

        if (!sawCompleted) {
            throw OctomilException(
                errorCode = OctomilErrorCode.REQUEST_TIMEOUT,
                message = "audio.speaker.embedding: timed out waiting for SESSION_COMPLETED (${drainDeadlineMs}ms)",
            )
        }

        return embeddingVector
            ?: throw OctomilException(
                errorCode = OctomilErrorCode.INFERENCE_FAILED,
                message = "audio.speaker.embedding: session completed without yielding an embedding vector",
            )
    }

    companion object {
        private const val DRAIN_DEADLINE_MS = 300_000L
        private const val POLL_TIMEOUT_MS = 200
    }
}
