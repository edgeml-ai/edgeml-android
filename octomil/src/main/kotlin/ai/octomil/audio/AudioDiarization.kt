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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Native-runtime diarization facade — `audio.diarization`.
 *
 * Opens a model-backed `audio.diarization` session on the native C runtime
 * (via [NativeRuntimeBridge]), feeds PCM frames, and emits typed
 * [DiarizationEvent] results annotating speaker turns.
 *
 * Lifecycle:
 *   `runtime_open → model_open → model_warm → session_open → send_audio →
 *    poll_event (loop) → session_close → model_close → runtime_close`
 *
 * Fail-closed: if the native runtime JNI library or the diarization model
 * artifact is absent the returned [Flow] terminates with [OctomilException]
 * carrying [OctomilErrorCode.RUNTIME_UNAVAILABLE].
 */
class AudioDiarization internal constructor(
    private val bridge: NativeRuntimeBridge,
    private val runtimeConfig: NativeRuntimeConfig = NativeRuntimeConfig(),
) {
    constructor() : this(NativeRuntimeBridge())

    /**
     * Stream speaker-turn diarization segments for the given audio frames.
     *
     * @param samples       PCM float samples (mono or multi-channel, interleaved).
     * @param sampleRate    Audio sample rate in Hz (typically 16000).
     * @param channels      Number of channels (typically 1).
     * @param modelUri      URI of the diarization model artifact.
     * @param pollTimeoutMs How long to wait per poll before yielding (ms).
     * @return A [Flow] of [DiarizationEvent] ending with
     *   [DiarizationEvent.Completed] or throwing [OctomilException] on error.
     */
    fun diarize(
        samples: FloatArray,
        sampleRate: Int = 16000,
        channels: Int = 1,
        modelUri: String? = null,
        pollTimeoutMs: Int = 100,
    ): Flow<DiarizationEvent> = flow {
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
                // Warm the model — best-effort.
                model.warm()

                val sessionConfig = NativeSessionConfig(
                    capability = RuntimeCapability.AUDIO_DIARIZATION,
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
                                    is NativeSessionEvent.DiarizationSegment ->
                                        emit(
                                            DiarizationEvent.Segment(
                                                startMs = event.startMs,
                                                endMs = event.endMs,
                                                speakerTag = event.speakerTag,
                                                confidence = event.confidence,
                                            ),
                                        )
                                    is NativeSessionEvent.SessionCompleted -> {
                                        emit(DiarizationEvent.Completed(event.terminalStatus.code, event.totalLatencyMs))
                                        completed = true
                                    }
                                    is NativeSessionEvent.Error -> throw OctomilException(
                                        OctomilErrorCode.INFERENCE_FAILED,
                                        "Diarization session error [${event.code}]: ${event.message ?: "no detail"}",
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

/** Events emitted by [AudioDiarization.diarize]. */
sealed class DiarizationEvent {
    /**
     * A single diarized speaker turn.
     *
     * @param startMs    Segment start time in milliseconds (runtime-monotonic).
     * @param endMs      Segment end time in milliseconds.
     * @param speakerTag Numeric speaker identifier assigned by the runtime.
     * @param confidence Segment confidence in [0,1] when available.
     */
    data class Segment(
        val startMs: Long,
        val endMs: Long,
        val speakerTag: Int,
        val confidence: Double?,
    ) : DiarizationEvent()

    /**
     * Session completed; no more events will be emitted.
     *
     * @param terminalStatusCode Native status code — 0 = OK.
     * @param totalLatencyMs     End-to-end latency reported by the runtime.
     */
    data class Completed(
        val terminalStatusCode: Int,
        val totalLatencyMs: Double?,
    ) : DiarizationEvent()
}
