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
 * A single speaker-labelled segment from the diarization pipeline.
 *
 * Mirrors Python `DiarizationSegment` from
 * `octomil.runtime.native.diarization_backend`.
 *
 * @property startMs Segment start (milliseconds from audio start).
 * @property endMs Segment end (milliseconds from audio start).
 * @property speakerTag Integer speaker label (0-indexed per utterance).
 * @property confidence Segment confidence in [0, 1], when provided by
 *   the runtime. `null` when the engine does not emit confidence values.
 */
data class DiarizationSegment(
    val startMs: Long,
    val endMs: Long,
    val speakerTag: Int,
    val confidence: Double? = null,
)

/**
 * Public facade for `audio.diarization` — speaker diarization.
 *
 * Mirrors Python `octomil.audio.diarization` (`NativeDiarizationBackend`).
 * Hard-cut to the native runtime: there is no Kotlin diarization
 * fallback. When the underlying JNI session is absent or the capability
 * is not advertised, [create] surfaces
 * [OctomilErrorCode.RUNTIME_UNAVAILABLE].
 *
 * Usage:
 * ```kotlin
 * val diarization = AudioDiarization()
 * val segments = diarization.create(samples, sampleRate = 16_000)
 * for (seg in segments) {
 *     println("Speaker ${seg.speakerTag}: ${seg.startMs}–${seg.endMs} ms")
 * }
 * ```
 *
 * This is a **LIVE_NATIVE_CONDITIONAL** capability: the runtime
 * advertises `audio.diarization` only when the pyannote segmentation
 * + speaker-embedding pipeline artifacts and environment gates pass.
 *
 * @param bridge Injected for tests; defaults to the real JNI bridge.
 */
class AudioDiarization internal constructor(
    private val bridge: NativeRuntimeBridge = NativeRuntimeBridge(),
) {
    constructor() : this(NativeRuntimeBridge())

    /**
     * Run speaker diarization on [audio] (mono PCM-f32, [sampleRate] Hz)
     * and return all speaker-labelled segments.
     *
     * Opens a native runtime → model → session, feeds [audio], drains
     * [NativeSessionEvent.DiarizationSegment] events until the session
     * completes, then closes everything.
     *
     * @param audio Mono PCM float32 samples.
     * @param sampleRate Sample rate in Hz (typically 16 000).
     * @return List of [DiarizationSegment] in chronological order.
     * @throws OctomilException [OctomilErrorCode.RUNTIME_UNAVAILABLE] when
     *   the native runtime is absent or does not advertise
     *   `audio.diarization`.
     */
    suspend fun create(
        audio: FloatArray,
        sampleRate: Int,
    ): List<DiarizationSegment> = withContext(Dispatchers.IO) {
        require(audio.isNotEmpty()) { "audio must not be empty" }
        require(sampleRate > 0) { "sampleRate must be > 0" }

        val runtime = when (val opened = bridge.open(NativeRuntimeConfig())) {
            is NativeRuntimeResult.Success -> opened.value
            is NativeRuntimeResult.Error -> throw OctomilException(
                errorCode = opened.error.sdkErrorCode,
                message = "audio.diarization: native runtime open failed — ${opened.error.message}",
            )
            is NativeRuntimeResult.Skipped -> throw OctomilException(
                errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                message = "audio.diarization: native runtime unavailable — ${opened.reason.message}",
            )
        }

        runtime.use {
            val caps = when (val c = runtime.capabilities()) {
                is NativeRuntimeResult.Success -> c.value
                is NativeRuntimeResult.Error -> throw OctomilException(
                    errorCode = c.error.sdkErrorCode,
                    message = "audio.diarization: capabilities query failed — ${c.error.message}",
                )
                is NativeRuntimeResult.Skipped -> throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.diarization: capabilities skipped — ${c.reason.message}",
                )
            }
            if (RuntimeCapability.AUDIO_DIARIZATION !in caps.supportedCapabilities) {
                throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.diarization: runtime does not advertise " +
                        "'${RuntimeCapability.AUDIO_DIARIZATION.code}'. " +
                        "Ensure the native runtime was built with the pyannote + " +
                        "speaker-embedding pipeline artifacts.",
                )
            }

            // audio.diarization is model-free per Python truth: the sherpa-onnx
            // diarization adapter loads its segmentation + speaker-embedding
            // artifacts at runtime_open, no oct_model_t. Use
            // openSessionModelFree to skip the model_open dance.
            val session = when (
                val s = runtime.openSessionModelFree(
                    NativeSessionConfig(
                        capability = RuntimeCapability.AUDIO_DIARIZATION,
                        sampleRateIn = sampleRate,
                    ),
                )
            ) {
                is NativeRuntimeResult.Success -> s.value
                is NativeRuntimeResult.Error -> throw OctomilException(
                    errorCode = s.error.sdkErrorCode,
                    message = "audio.diarization: session open failed — ${s.error.message}",
                )
                is NativeRuntimeResult.Skipped -> throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.diarization: session open skipped — ${s.reason.message}",
                )
            }

            session.use {
                when (val sent = session.sendAudio(NativeAudioView(audio, sampleRate))) {
                    is NativeRuntimeResult.Error -> throw OctomilException(
                        errorCode = sent.error.sdkErrorCode,
                        message = "audio.diarization: sendAudio failed — ${sent.error.message}",
                    )
                    is NativeRuntimeResult.Skipped -> throw OctomilException(
                        errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                        message = "audio.diarization: sendAudio skipped — ${sent.reason.message}",
                    )
                    is NativeRuntimeResult.Success -> Unit
                }

                drainDiarizationEvents(session)
            }
        }
    }

    internal fun drainDiarizationEvents(
        session: ai.octomil.runtime.nativebridge.NativeSession,
        drainDeadlineMs: Long = DRAIN_DEADLINE_MS,
    ): List<DiarizationSegment> {
        val segments = mutableListOf<DiarizationSegment>()
        val deadlineMs = System.currentTimeMillis() + drainDeadlineMs
        var sawCompleted = false

        while (System.currentTimeMillis() < deadlineMs) {
            val result = session.pollEvent(timeoutMs = POLL_TIMEOUT_MS)
            when (result) {
                is NativeRuntimeResult.Error -> throw OctomilException(
                    errorCode = result.error.sdkErrorCode,
                    message = "audio.diarization: pollEvent failed — ${result.error.message}",
                )
                is NativeRuntimeResult.Skipped -> throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.diarization: pollEvent skipped — ${result.reason.message}",
                )
                is NativeRuntimeResult.Success -> {
                    when (val ev = result.value) {
                        null -> continue
                        is NativeSessionEvent.DiarizationSegment -> {
                            segments += DiarizationSegment(
                                startMs = ev.startMs,
                                endMs = ev.endMs,
                                speakerTag = ev.speakerTag,
                                confidence = ev.confidence,
                            )
                        }
                        is NativeSessionEvent.SessionCompleted -> {
                            if (ev.terminalStatus != NativeRuntimeStatus.OK) {
                                throw OctomilException(
                                    errorCode = ev.terminalStatus.toSdkErrorCode()
                                        ?: OctomilErrorCode.UNKNOWN,
                                    message = "audio.diarization: session completed with " +
                                        "error ${ev.terminalStatus.cName}",
                                )
                            }
                            sawCompleted = true
                            break
                        }
                        is NativeSessionEvent.Error -> throw OctomilException(
                            errorCode = OctomilErrorCode.INFERENCE_FAILED,
                            message = "audio.diarization: session error — ${ev.message ?: ev.code}",
                        )
                        else -> continue
                    }
                }
            }
        }

        if (!sawCompleted) {
            throw OctomilException(
                errorCode = OctomilErrorCode.REQUEST_TIMEOUT,
                message = "audio.diarization: timed out waiting for SESSION_COMPLETED (${drainDeadlineMs}ms)",
            )
        }

        return segments
    }

    companion object {
        private const val DRAIN_DEADLINE_MS = 300_000L
        private const val POLL_TIMEOUT_MS = 200
    }
}
