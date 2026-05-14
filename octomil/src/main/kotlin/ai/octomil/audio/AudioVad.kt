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
 * A single voice-activity transition emitted by the VAD engine.
 *
 * Mirrors Python `VadTransition` from
 * `octomil.runtime.native.vad_backend`.
 *
 * @property kind `"speech_start"` or `"speech_end"`.
 * @property timestampMs Position in the audio stream (milliseconds).
 * @property confidence Detection confidence in [0, 1], when provided by
 *   the runtime. `null` when the engine does not emit confidence values.
 */
data class VadTransition(
    val kind: String,
    val timestampMs: Long,
    val confidence: Double? = null,
)

/**
 * Public facade for `audio.vad` — voice-activity detection.
 *
 * Mirrors Python `octomil.audio.vad` (`NativeVadBackend`). Hard-cut to
 * the native runtime: there is no Kotlin VAD fallback. When the
 * underlying JNI session is absent or the capability is not advertised,
 * [detect] surfaces [OctomilErrorCode.RUNTIME_UNAVAILABLE].
 *
 * Usage:
 * ```kotlin
 * val vad = AudioVad()            // or injected via Octomil.audio.vad
 * val transitions = vad.detect(samples, sampleRate = 16_000)
 * for (t in transitions) {
 *     println("${t.kind} @ ${t.timestampMs} ms")
 * }
 * ```
 *
 * This is a **LIVE_NATIVE_CONDITIONAL** capability: the runtime
 * advertises `audio.vad` only when the Silero VAD model artifact and
 * environment gates pass. Until the JNI session-open path for VAD is
 * wired, every call returns `RUNTIME_UNAVAILABLE` deterministically.
 *
 * @param bridge Injected for tests; defaults to the real JNI bridge.
 */
class AudioVad internal constructor(
    private val bridge: NativeRuntimeBridge = NativeRuntimeBridge(),
) {
    constructor() : this(NativeRuntimeBridge())

    /**
     * Run VAD on [audio] (mono PCM-f32, [sampleRate] Hz) and return
     * all speech/silence transitions detected in the clip.
     *
     * Opens a native runtime → model → session, feeds [audio], drains
     * [NativeSessionEvent.VadSegment] events until the session
     * completes, then closes everything.
     *
     * @param audio Mono PCM float32 samples.
     * @param sampleRate Sample rate in Hz (typically 16 000).
     * @return List of [VadTransition] objects in chronological order.
     * @throws OctomilException [OctomilErrorCode.RUNTIME_UNAVAILABLE] when
     *   the native runtime is absent or does not advertise `audio.vad`.
     */
    suspend fun detect(
        audio: FloatArray,
        sampleRate: Int,
    ): List<VadTransition> = withContext(Dispatchers.IO) {
        require(audio.isNotEmpty()) { "audio must not be empty" }
        require(sampleRate > 0) { "sampleRate must be > 0" }

        val runtime = when (val opened = bridge.open(NativeRuntimeConfig())) {
            is NativeRuntimeResult.Success -> opened.value
            is NativeRuntimeResult.Error -> throw OctomilException(
                errorCode = opened.error.sdkErrorCode,
                message = "audio.vad: native runtime open failed — ${opened.error.message}",
            )
            is NativeRuntimeResult.Skipped -> throw OctomilException(
                errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                message = "audio.vad: native runtime unavailable — ${opened.reason.message}",
            )
        }

        runtime.use {
            // Capability-honesty check: refuse loudly if the runtime does
            // not advertise audio.vad, rather than silently falling through.
            val caps = when (val c = runtime.capabilities()) {
                is NativeRuntimeResult.Success -> c.value
                is NativeRuntimeResult.Error -> throw OctomilException(
                    errorCode = c.error.sdkErrorCode,
                    message = "audio.vad: capabilities query failed — ${c.error.message}",
                )
                is NativeRuntimeResult.Skipped -> throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.vad: capabilities skipped — ${c.reason.message}",
                )
            }
            if (RuntimeCapability.AUDIO_VAD !in caps.supportedCapabilities) {
                throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.vad: runtime does not advertise '${RuntimeCapability.AUDIO_VAD.code}'. " +
                        "Ensure the native runtime was built with Silero VAD support " +
                        "and OCTOMIL_SILERO_VAD_MODEL is set.",
                )
            }

            // audio.vad is model-free per Python truth + runtime conformance:
            // silero loads its weights at runtime_open, no oct_model_t. Use
            // openSessionModelFree to skip the model_open dance.
            val session = when (
                val s = runtime.openSessionModelFree(
                    NativeSessionConfig(
                        capability = RuntimeCapability.AUDIO_VAD,
                        sampleRateIn = sampleRate,
                    ),
                )
            ) {
                is NativeRuntimeResult.Success -> s.value
                is NativeRuntimeResult.Error -> throw OctomilException(
                    errorCode = s.error.sdkErrorCode,
                    message = "audio.vad: session open failed — ${s.error.message}",
                )
                is NativeRuntimeResult.Skipped -> throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.vad: session open skipped — ${s.reason.message}",
                )
            }

            session.use {
                when (val sent = session.sendAudio(NativeAudioView(audio, sampleRate))) {
                    is NativeRuntimeResult.Error -> throw OctomilException(
                        errorCode = sent.error.sdkErrorCode,
                        message = "audio.vad: sendAudio failed — ${sent.error.message}",
                    )
                    is NativeRuntimeResult.Skipped -> throw OctomilException(
                        errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                        message = "audio.vad: sendAudio skipped — ${sent.reason.message}",
                    )
                    is NativeRuntimeResult.Success -> Unit
                }

                drainVadEvents(session)
            }
        }
    }

    internal fun drainVadEvents(
        session: ai.octomil.runtime.nativebridge.NativeSession,
        drainDeadlineMs: Long = DRAIN_DEADLINE_MS,
    ): List<VadTransition> {
        val transitions = mutableListOf<VadTransition>()
        val deadlineMs = System.currentTimeMillis() + drainDeadlineMs
        var sawCompleted = false

        while (System.currentTimeMillis() < deadlineMs) {
            val result = session.pollEvent(timeoutMs = POLL_TIMEOUT_MS)
            when (result) {
                is NativeRuntimeResult.Error -> throw OctomilException(
                    errorCode = result.error.sdkErrorCode,
                    message = "audio.vad: pollEvent failed — ${result.error.message}",
                )
                is NativeRuntimeResult.Skipped -> throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.vad: pollEvent skipped — ${result.reason.message}",
                )
                is NativeRuntimeResult.Success -> {
                    when (val ev = result.value) {
                        null -> continue
                        is NativeSessionEvent.VadSegment -> {
                            transitions += VadTransition(
                                kind = if (ev.voiced) "speech_start" else "speech_end",
                                timestampMs = ev.startMs,
                                confidence = ev.confidence,
                            )
                        }
                        is NativeSessionEvent.SessionCompleted -> {
                            if (ev.terminalStatus != NativeRuntimeStatus.OK) {
                                throw OctomilException(
                                    errorCode = ev.terminalStatus.toSdkErrorCode()
                                        ?: OctomilErrorCode.UNKNOWN,
                                    message = "audio.vad: session completed with error ${ev.terminalStatus.cName}",
                                )
                            }
                            sawCompleted = true
                            break
                        }
                        is NativeSessionEvent.Error -> throw OctomilException(
                            errorCode = OctomilErrorCode.INFERENCE_FAILED,
                            message = "audio.vad: session error — ${ev.message ?: ev.code}",
                        )
                        else -> continue
                    }
                }
            }
        }

        if (!sawCompleted) {
            throw OctomilException(
                errorCode = OctomilErrorCode.REQUEST_TIMEOUT,
                message = "audio.vad: timed out waiting for SESSION_COMPLETED (${drainDeadlineMs}ms)",
            )
        }

        return transitions
    }

    companion object {
        private const val DRAIN_DEADLINE_MS = 300_000L
        private const val POLL_TIMEOUT_MS = 200
    }
}
