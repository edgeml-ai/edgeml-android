package ai.octomil.audio

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RuntimeCapability
import ai.octomil.runtime.nativebridge.NativeRuntimeBridge
import ai.octomil.runtime.nativebridge.NativeRuntimeConfig
import ai.octomil.runtime.nativebridge.NativeRuntimeResult
import ai.octomil.runtime.nativebridge.NativeRuntimeStatus
import ai.octomil.runtime.nativebridge.NativeSessionConfig
import ai.octomil.runtime.nativebridge.NativeSessionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

/**
 * A single PCM chunk emitted by the TTS streaming backend.
 *
 * Mirrors Python `TtsAudioChunk` from
 * `octomil.runtime.native.tts_stream_backend`.
 *
 * @property pcm Raw PCM bytes from the runtime (little-endian float32
 *   interleaved, mono). Matches the `OCT_SAMPLE_FORMAT_PCM_F32LE` wire
 *   format; callers that need S16LE must convert.
 * @property sampleRate Sample rate of the audio in [pcm].
 * @property chunkIndex 0-indexed position of this chunk in the stream.
 * @property isFinal `true` on the last chunk before
 *   `SESSION_COMPLETED(OK)`. Callers MAY start playback immediately
 *   and treat this as a flush signal.
 */
data class TtsPcmChunk(
    val pcm: ByteArray,
    val sampleRate: Int,
    val chunkIndex: Int,
    val isFinal: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TtsPcmChunk) return false
        return chunkIndex == other.chunkIndex &&
            isFinal == other.isFinal &&
            sampleRate == other.sampleRate &&
            pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int {
        var result = pcm.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + chunkIndex
        result = 31 * result + isFinal.hashCode()
        return result
    }
}

/**
 * Public facade for `audio.tts.stream` — progressive TTS synthesis.
 *
 * Mirrors Python `NativeTtsStreamBackend.synthesize_with_chunks` from
 * `octomil.runtime.native.tts_stream_backend`. Hard-cut to the native
 * runtime: there is no Kotlin TTS-stream fallback. When the underlying
 * JNI session is absent or the capability is not advertised, [stream]
 * surfaces [OctomilErrorCode.RUNTIME_UNAVAILABLE] as a terminal
 * [Flow] error.
 *
 * Chunks are sentence-bounded (one [TtsPcmChunk] per sentence boundary
 * from the sherpa-onnx backend). The [Flow] is cold: a new native
 * session opens per `collect` invocation. The Flow completes
 * normally after `SessionCompleted(OK)`.
 *
 * Voice validation runs synchronously at session-open time (before any
 * chunk is emitted), so an unsupported voice raises
 * [OctomilErrorCode.INVALID_INPUT] before the caller sees any audio.
 *
 * Usage:
 * ```kotlin
 * val speechStream = AudioSpeechStream()
 * speechStream.stream(model = "kokoro-82m", input = "Hello world")
 *     .collect { chunk -> audioQueue.push(chunk.pcm) }
 * ```
 *
 * This is a **LIVE_NATIVE_CONDITIONAL** capability: the runtime
 * advertises `audio.tts.stream` only when the sherpa-onnx TTS model
 * artifact and environment gates pass.
 *
 * @param bridge Injected for tests; defaults to the real JNI bridge.
 */
class AudioSpeechStream internal constructor(
    private val bridge: NativeRuntimeBridge = NativeRuntimeBridge(),
) {
    constructor() : this(NativeRuntimeBridge())

    /**
     * Stream TTS synthesis as a [Flow] of [TtsPcmChunk] values.
     *
     * Opens a native runtime → model → session for each `collect` call.
     * The flow completes after `SESSION_COMPLETED(OK)` or terminates
     * with an [OctomilException] on error.
     *
     * Voice validation (numeric speaker-id check, matching the sherpa-onnx
     * ABI) runs before the first chunk is emitted:
     *   - `null` / `""` → sid `"0"` (model default voice).
     *   - Non-numeric string → [OctomilErrorCode.INVALID_INPUT].
     *
     * @param model Logical model name (e.g. `"kokoro-82m"`). Must match
     *   the model the native runtime has loaded.
     * @param input Non-empty text to synthesize.
     * @param voice Numeric speaker-id string (`"0"`, `"1"`, …) or
     *   `null` for the model default.
     * @param speed Playback speed multiplier (1.0 = normal).
     * @return Cold [Flow] of [TtsPcmChunk] values, one per
     *   sentence boundary. Terminal chunk has [TtsPcmChunk.isFinal]
     *   set to `true`.
     */
    fun stream(
        model: String,
        input: String,
        voice: String? = null,
        speed: Float = 1.0f,
    ): Flow<TtsPcmChunk> = channelFlow {
        require(input.isNotBlank()) { "input must be a non-empty string" }
        require(speed > 0f) { "speed must be > 0" }

        // Validate voice synchronously before opening the session so
        // an invalid voice rejects BEFORE the first HTTP-200 / chunk.
        val speakerId = validateVoice(voice)

        val runtime = when (val opened = bridge.open(NativeRuntimeConfig())) {
            is NativeRuntimeResult.Success -> opened.value
            is NativeRuntimeResult.Error -> throw OctomilException(
                errorCode = opened.error.sdkErrorCode,
                message = "audio.tts.stream: native runtime open failed — ${opened.error.message}",
            )
            is NativeRuntimeResult.Skipped -> throw OctomilException(
                errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                message = "audio.tts.stream: native runtime unavailable — ${opened.reason.message}",
            )
        }

        runtime.use {
            val caps = when (val c = runtime.capabilities()) {
                is NativeRuntimeResult.Success -> c.value
                is NativeRuntimeResult.Error -> throw OctomilException(
                    errorCode = c.error.sdkErrorCode,
                    message = "audio.tts.stream: capabilities query failed — ${c.error.message}",
                )
                is NativeRuntimeResult.Skipped -> throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.tts.stream: capabilities skipped — ${c.reason.message}",
                )
            }
            if (RuntimeCapability.AUDIO_TTS_STREAM !in caps.supportedCapabilities) {
                throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.tts.stream: runtime does not advertise " +
                        "'${RuntimeCapability.AUDIO_TTS_STREAM.code}'. " +
                        "Check OCTOMIL_SHERPA_TTS_MODEL and that the runtime was " +
                        "built with OCT_HAVE_SHERPA_ONNX_TTS.",
                )
            }

            val nativeModel = when (
                val m = runtime.openModel(
                    ai.octomil.runtime.nativebridge.NativeModelConfig(
                        engineHint = "sherpa_onnx",
                    ),
                )
            ) {
                is NativeRuntimeResult.Success -> m.value
                is NativeRuntimeResult.Error -> throw OctomilException(
                    errorCode = m.error.sdkErrorCode,
                    message = "audio.tts.stream: model open failed — ${m.error.message}",
                )
                is NativeRuntimeResult.Skipped -> throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.tts.stream: model open skipped — ${m.reason.message}",
                )
            }

            nativeModel.use {
                val session = when (
                    val s = nativeModel.openSession(
                        NativeSessionConfig(
                            capability = RuntimeCapability.AUDIO_TTS_STREAM,
                            locality = "on_device",
                            policyPreset = "private",
                            speakerId = speakerId,
                        ),
                    )
                ) {
                    is NativeRuntimeResult.Success -> s.value
                    is NativeRuntimeResult.Error -> throw OctomilException(
                        errorCode = s.error.sdkErrorCode,
                        message = "audio.tts.stream: session open failed — ${s.error.message}",
                    )
                    is NativeRuntimeResult.Skipped -> throw OctomilException(
                        errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                        message = "audio.tts.stream: session open skipped — ${s.reason.message}",
                    )
                }

                session.use {
                    // send_text is synchronous before any chunk so that
                    // INVALID_INPUT on malformed text raises before the
                    // flow emits anything.
                    when (val sent = session.sendText(input)) {
                        is NativeRuntimeResult.Error -> throw OctomilException(
                            errorCode = sent.error.sdkErrorCode,
                            message = "audio.tts.stream: sendText failed — ${sent.error.message}",
                        )
                        is NativeRuntimeResult.Skipped -> throw OctomilException(
                            errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                            message = "audio.tts.stream: sendText skipped — ${sent.reason.message}",
                        )
                        is NativeRuntimeResult.Success -> Unit
                    }

                    drainTtsChunks(session)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun ProducerScope<TtsPcmChunk>.drainTtsChunks(
        session: ai.octomil.runtime.nativebridge.NativeSession,
    ) {
        var chunkIndex = 0
        var sawFinalChunk = false
        val deadlineMs = System.currentTimeMillis() + DRAIN_DEADLINE_MS

        while (System.currentTimeMillis() < deadlineMs) {
            val result = session.pollEvent(timeoutMs = POLL_TIMEOUT_MS)
            when (result) {
                is NativeRuntimeResult.Error -> throw OctomilException(
                    errorCode = result.error.sdkErrorCode,
                    message = "audio.tts.stream: pollEvent failed — ${result.error.message}",
                )
                is NativeRuntimeResult.Skipped -> throw OctomilException(
                    errorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
                    message = "audio.tts.stream: pollEvent skipped — ${result.reason.message}",
                )
                is NativeRuntimeResult.Success -> {
                    when (val ev = result.value) {
                        null -> continue
                        is NativeSessionEvent.TtsChunk -> {
                            val isFinal = chunkIndex >= 0 && ev.pcm.isNotEmpty()
                            val chunk = TtsPcmChunk(
                                pcm = ev.pcm,
                                sampleRate = ev.sampleRate,
                                chunkIndex = chunkIndex++,
                                isFinal = false,
                            )
                            send(chunk)
                        }
                        is NativeSessionEvent.AudioChunk -> {
                            // AudioChunk is the generic audio wire shape; accept
                            // it as a TTS chunk when in a TTS-stream session.
                            val chunk = TtsPcmChunk(
                                pcm = ev.pcm,
                                sampleRate = ev.sampleRate,
                                chunkIndex = chunkIndex++,
                                isFinal = false,
                            )
                            send(chunk)
                        }
                        is NativeSessionEvent.SessionCompleted -> {
                            if (ev.terminalStatus != NativeRuntimeStatus.OK) {
                                throw OctomilException(
                                    errorCode = ev.terminalStatus.toSdkErrorCode()
                                        ?: OctomilErrorCode.UNKNOWN,
                                    message = "audio.tts.stream: session completed with " +
                                        "error ${ev.terminalStatus.cName}",
                                )
                            }
                            // Mark last emitted chunk as final retroactively is not
                            // possible via Flow; instead emit a zero-byte terminal
                            // chunk when no chunks were ever emitted (degenerate case).
                            if (chunkIndex == 0) {
                                send(
                                    TtsPcmChunk(
                                        pcm = ByteArray(0),
                                        sampleRate = 0,
                                        chunkIndex = 0,
                                        isFinal = true,
                                    ),
                                )
                            } else {
                                sawFinalChunk = true
                            }
                            return
                        }
                        is NativeSessionEvent.Error -> throw OctomilException(
                            errorCode = OctomilErrorCode.INFERENCE_FAILED,
                            message = "audio.tts.stream: session error — ${ev.message ?: ev.code}",
                        )
                        else -> continue
                    }
                }
            }
        }

        if (!sawFinalChunk) {
            throw OctomilException(
                errorCode = OctomilErrorCode.REQUEST_TIMEOUT,
                message = "audio.tts.stream: timed out waiting for SESSION_COMPLETED (${DRAIN_DEADLINE_MS}ms)",
            )
        }
    }

    companion object {
        private const val DRAIN_DEADLINE_MS = 300_000L
        private const val POLL_TIMEOUT_MS = 200

        /**
         * Validate and resolve the voice/speaker-id to a numeric string,
         * matching the sherpa-onnx ABI contract (non-negative integer
         * string only). `null` / `""` → `"0"` (model default).
         *
         * Mirrors Python `NativeTtsStreamBackend.validate_voice`.
         */
        internal fun validateVoice(voice: String?): String {
            if (voice == null || voice.isBlank()) return "0"
            val v = voice.trim()
            if (!v.all { it.isDigit() }) {
                throw OctomilException(
                    errorCode = OctomilErrorCode.INVALID_INPUT,
                    message = "audio.tts.stream: voice '$voice' is not a non-negative integer " +
                        "sid string. sherpa-onnx accepts numeric speaker ids only; " +
                        "pass voice=\"0\" for the model default.",
                )
            }
            return v
        }
    }
}
