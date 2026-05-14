package ai.octomil.audio

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RuntimeCapability
import ai.octomil.runtime.nativebridge.NativeModelConfig
import ai.octomil.runtime.nativebridge.NativeRuntimeBridge
import ai.octomil.runtime.nativebridge.NativeRuntimeConfig
import ai.octomil.runtime.nativebridge.NativeRuntimeResult
import ai.octomil.runtime.nativebridge.NativeSessionConfig
import ai.octomil.runtime.nativebridge.NativeSessionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Native-runtime streaming TTS facade — `audio.tts.stream`.
 *
 * Opens a model-backed `audio.tts.stream` session on the native C runtime
 * (via [NativeRuntimeBridge]), sends text input, and streams synthesized PCM
 * audio chunks as [TtsStreamEvent] objects.
 *
 * Lifecycle:
 *   `runtime_open → model_open → model_warm → session_open → send_text →
 *    poll_event (loop) → session_close → model_close → runtime_close`
 *
 * Fail-closed: if the native runtime JNI library or the TTS model artifact
 * is absent the returned [Flow] terminates with [OctomilException] carrying
 * [OctomilErrorCode.RUNTIME_UNAVAILABLE] or [OctomilErrorCode.MODEL_NOT_FOUND].
 *
 * This facade covers only the native-runtime streaming path. For the
 * legacy batch TTS path backed by sherpa-onnx, use [AudioSpeech].
 */
class AudioSpeechStream internal constructor(
    private val bridge: NativeRuntimeBridge,
    private val runtimeConfig: NativeRuntimeConfig = NativeRuntimeConfig(),
) {
    constructor() : this(NativeRuntimeBridge())

    /**
     * Synthesize [text] and stream the resulting PCM audio progressively.
     *
     * Each [TtsStreamEvent.AudioChunk] carries a [ByteArray] of raw PCM
     * bytes at the [TtsStreamEvent.AudioChunk.sampleRate] reported by the
     * runtime. The stream ends with [TtsStreamEvent.Completed].
     *
     * @param text          Input text to synthesize.
     * @param modelUri      URI of the TTS model artifact.
     * @param sampleRateOut Requested output sample rate in Hz (0 = runtime default).
     * @param pollTimeoutMs How long to wait per poll before yielding (ms).
     * @return A [Flow] of [TtsStreamEvent] ending with [TtsStreamEvent.Completed]
     *   or throwing [OctomilException] on error.
     */
    fun stream(
        text: String,
        modelUri: String? = null,
        sampleRateOut: Int = 0,
        pollTimeoutMs: Int = 100,
    ): Flow<TtsStreamEvent> = flow {
        require(text.isNotEmpty()) { "text must not be empty" }

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
                model.warm()

                val sessionConfig = NativeSessionConfig(
                    capability = RuntimeCapability.AUDIO_TTS_STREAM,
                    sampleRateOut = sampleRateOut,
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
                    when (val sent = session.sendText(text)) {
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
                                    is NativeSessionEvent.TtsChunk ->
                                        emit(
                                            TtsStreamEvent.AudioChunk(
                                                pcm = event.pcm,
                                                sampleRate = event.sampleRate,
                                            ),
                                        )
                                    is NativeSessionEvent.SessionCompleted -> {
                                        emit(TtsStreamEvent.Completed(event.terminalStatus.code, event.totalLatencyMs))
                                        completed = true
                                    }
                                    is NativeSessionEvent.Error -> throw OctomilException(
                                        OctomilErrorCode.INFERENCE_FAILED,
                                        "TTS stream error [${event.code}]: ${event.message ?: "no detail"}",
                                    )
                                    is NativeSessionEvent.Metric,
                                    is NativeSessionEvent.SessionStarted -> Unit // informational
                                    else -> Unit // forward-compatible
                                }
                            }
                        }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}

// ---------------------------------------------------------------------------
// Public result types
// ---------------------------------------------------------------------------

/** Events emitted by [AudioSpeechStream.stream]. */
sealed class TtsStreamEvent {
    /**
     * A progressive PCM audio chunk.
     *
     * @param pcm        Raw PCM bytes (runtime-owned buffer copied into a JVM
     *                   [ByteArray] at poll time — caller owns this buffer).
     * @param sampleRate Sample rate of the PCM data in Hz.
     */
    data class AudioChunk(
        val pcm: ByteArray,
        val sampleRate: Int,
    ) : TtsStreamEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AudioChunk) return false
            if (!pcm.contentEquals(other.pcm)) return false
            if (sampleRate != other.sampleRate) return false
            return true
        }

        override fun hashCode(): Int {
            var result = pcm.contentHashCode()
            result = 31 * result + sampleRate
            return result
        }
    }

    /**
     * Session completed; no more events will be emitted.
     *
     * @param terminalStatusCode Native status code — 0 = OK.
     * @param totalLatencyMs     End-to-end latency reported by the runtime.
     */
    data class Completed(
        val terminalStatusCode: Int,
        val totalLatencyMs: Double?,
    ) : TtsStreamEvent()
}
