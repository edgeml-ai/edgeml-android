package ai.octomil.audio

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RuntimeCapability
import ai.octomil.runtime.nativebridge.NativeAudioView
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
 * Native-runtime VAD facade — `audio.vad`.
 *
 * Opens an `audio.vad` session on the native C runtime (via
 * [NativeRuntimeBridge]), feeds PCM frames, and emits typed
 * [VadEvent] results. No model artifact is required; the runtime
 * uses a bundled silero-VAD weight.
 *
 * Lifecycle (model-free path):
 *   `runtime_open → session_open → send_audio → poll_event (loop) →
 *    session_close → runtime_close`
 *
 * Fail-closed: if the native runtime JNI library is absent the
 * returned [Flow] terminates with [OctomilException] carrying
 * [OctomilErrorCode.RUNTIME_UNAVAILABLE].
 */
class AudioVad internal constructor(
    private val bridge: NativeRuntimeBridge,
    private val runtimeConfig: NativeRuntimeConfig = NativeRuntimeConfig(),
) {
    constructor() : this(NativeRuntimeBridge())

    /**
     * Stream VAD transitions for the given audio frames.
     *
     * @param samples       PCM float samples (mono or multi-channel, interleaved).
     * @param sampleRate    Audio sample rate in Hz (typically 16000).
     * @param channels      Number of channels (typically 1).
     * @param pollTimeoutMs How long to wait per poll before yielding (ms).
     * @return A [Flow] of [VadEvent] ending with [VadEvent.Completed] or
     *   throwing [OctomilException] on error.
     */
    fun detect(
        samples: FloatArray,
        sampleRate: Int = 16000,
        channels: Int = 1,
        pollTimeoutMs: Int = 100,
    ): Flow<VadEvent> = flow {
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
            val sessionConfig = NativeSessionConfig(
                capability = RuntimeCapability.AUDIO_VAD,
                sampleRateIn = sampleRate,
            )
            // VAD is model-free — pass NULL oct_model_t* to the runtime.
            val session = when (val s = runtime.openSessionModelFree(sessionConfig)) {
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
                                is NativeSessionEvent.VadSegment ->
                                    emit(
                                        VadEvent.Transition(
                                            startMs = event.startMs,
                                            endMs = event.endMs,
                                            voiced = event.voiced,
                                            confidence = event.confidence,
                                        ),
                                    )
                                is NativeSessionEvent.SessionCompleted -> {
                                    emit(VadEvent.Completed(event.terminalStatus.code, event.totalLatencyMs))
                                    completed = true
                                }
                                is NativeSessionEvent.Error -> throw OctomilException(
                                    OctomilErrorCode.INFERENCE_FAILED,
                                    "VAD session error [${event.code}]: ${event.message ?: "no detail"}",
                                )
                                is NativeSessionEvent.Metric,
                                is NativeSessionEvent.SessionStarted -> Unit // informational
                                else -> Unit // forward-compatible: unknown event types are ignored
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

/** Events emitted by [AudioVad.detect]. */
sealed class VadEvent {
    /**
     * A VAD speech/silence transition.
     *
     * @param startMs    Start of the segment in milliseconds (runtime-monotonic).
     * @param endMs      End of the segment in milliseconds.
     * @param voiced     True when the segment contains speech; false for silence.
     * @param confidence Confidence in [0,1] when available.
     */
    data class Transition(
        val startMs: Long,
        val endMs: Long,
        val voiced: Boolean,
        val confidence: Double?,
    ) : VadEvent()

    /**
     * Session completed; no more events will be emitted.
     *
     * @param terminalStatusCode Native status code — 0 = OK.
     * @param totalLatencyMs     End-to-end latency reported by the runtime.
     */
    data class Completed(
        val terminalStatusCode: Int,
        val totalLatencyMs: Double?,
    ) : VadEvent()
}
