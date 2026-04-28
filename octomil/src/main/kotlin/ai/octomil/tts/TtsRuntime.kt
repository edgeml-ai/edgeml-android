package ai.octomil.tts

import java.io.File

/**
 * Result of a [TtsRuntime.synthesize] call.
 *
 * Mirrors the iOS `TtsResult` and Python `TtsResult` shape so calls
 * crossing the SDK boundary line up.
 */
data class TtsResult(
    val audioBytes: ByteArray,
    val contentType: String,
    val format: String,
    val sampleRate: Int,
    val durationMs: Int,
    val voice: String?,
    val model: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TtsResult) return false
        if (!audioBytes.contentEquals(other.audioBytes)) return false
        if (contentType != other.contentType) return false
        if (format != other.format) return false
        if (sampleRate != other.sampleRate) return false
        if (durationMs != other.durationMs) return false
        if (voice != other.voice) return false
        if (model != other.model) return false
        return true
    }

    override fun hashCode(): Int {
        var result = audioBytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + durationMs
        result = 31 * result + (voice?.hashCode() ?: 0)
        result = 31 * result + model.hashCode()
        return result
    }
}

/**
 * On-device TTS runtime contract. Implemented by the optional
 * sherpa-onnx Kokoro/VITS engine in
 * `octomil-runtime-sherpa-android`, but kept in the `main` source
 * set so the public [ai.octomil.audio.AudioSpeech] facade compiles
 * without the optional dependency on the classpath.
 *
 * Implementations MUST be safe to reuse across calls; the warmup
 * lifecycle relies on a single [TtsRuntime] handle being kept
 * alive between [synthesize] invocations and released exactly once.
 */
interface TtsRuntime {
    /** Logical model name (e.g. ``"kokoro-82m"``). */
    val modelName: String

    /**
     * Synthesize speech from [text]. [voice] / [speed] honor the
     * runtime's voice catalog; unknown voices fall back to the
     * model's default speaker.
     */
    fun synthesize(
        text: String,
        voice: String? = null,
        speed: Float = 1.0f,
    ): TtsResult

    /** Release any native handle held by the runtime. Idempotent. */
    fun release()
}

/**
 * Factory contract registered at startup by the optional sherpa-onnx
 * runtime artifact. Splitting the factory from the runtime lets
 * [TtsRuntimeRegistry] stay in the `main` source set while the
 * concrete implementation lives in `externalRuntimes`.
 */
fun interface TtsRuntimeFactory {
    /**
     * Build a runtime bound to the on-disk artifact directory
     * (the layout PrepareManager + Materializer produced).
     */
    fun create(modelDir: File, modelName: String): TtsRuntime
}
