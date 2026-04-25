package ai.octomil.tts

import ai.octomil.runtime.planner.DeviceRuntimeProfileCollector
import ai.octomil.runtime.planner.InstalledRuntime
import ai.octomil.runtime.planner.modelCapable
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

private const val TAG = "SherpaTtsRuntime"

/**
 * sherpa-onnx model family. Selects which OfflineTtsModelConfig
 * variant to load and which voice catalog to consult.
 *
 * Mirrors the Python `_SHERPA_TTS_MODELS` (family, default_voice)
 * mapping and the iOS `SherpaTtsFamily` enum so cross-SDK callers see
 * consistent voice ids.
 */
internal enum class SherpaTtsFamily {
    KOKORO,
    VITS,
    ;

    companion object {
        fun forModelName(modelName: String): SherpaTtsFamily? {
            val lower = modelName.lowercase()
            return when {
                lower.startsWith("kokoro-") -> KOKORO
                lower.startsWith("piper-") || lower.startsWith("vits-") -> VITS
                else -> null
            }
        }

        /**
         * Kokoro v0.19+ voice catalog. Index == speaker id in the
         * bundled voices.bin. Mirrors octomil-python `_KOKORO_VOICES`
         * and octomil-ios `SherpaTtsFamily.kokoroVoices`.
         */
        val KOKORO_VOICES: List<String> = listOf(
            "af_alloy", "af_aoede", "af_bella", "af_heart",
            "af_jessica", "af_kore", "af_nicole", "af_nova",
            "af_river", "af_sarah", "af_sky",
            "am_adam", "am_echo", "am_eric", "am_fenrir",
            "am_liam", "am_michael", "am_onyx", "am_puck", "am_santa",
            "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
            "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
        )
    }

    fun speakerId(voice: String?): Int {
        if (voice.isNullOrBlank()) return 0
        return when (this) {
            KOKORO -> KOKORO_VOICES.indexOf(voice.lowercase()).takeIf { it >= 0 } ?: 0
            VITS -> 0
        }
    }
}

/** Result of a [SherpaTtsRuntime.synthesize] call. */
data class SherpaTtsResult(
    val audioBytes: ByteArray,
    val contentType: String,
    val format: String,
    val sampleRate: Int,
    val durationMs: Int,
    val voice: String?,
    val model: String,
)

class SherpaTtsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Optional on-device TTS runtime backed by sherpa-onnx (Kokoro / VITS).
 *
 * Only compiled when the optional `octomil-runtime-sherpa-android`
 * artifact is on the classpath (gated by
 * `octomil.includeExternalRuntimes` in the consuming Gradle build).
 *
 * @param modelDir Directory containing model files. For Kokoro: model.onnx,
 *   voices.bin, tokens.txt, espeak-ng-data/. For VITS/Piper: model.onnx,
 *   tokens.txt, espeak-ng-data/.
 */
class SherpaTtsRuntime(
    private val modelDir: File,
    modelName: String? = null,
) {
    val modelName: String = modelName ?: modelDir.name
    private val family: SherpaTtsFamily = SherpaTtsFamily.forModelName(this.modelName)
        ?: throw SherpaTtsException("Unsupported sherpa-onnx TTS model family: ${this.modelName}")
    private val tts: OfflineTts

    init {
        Log.i(TAG, "Building config for $modelName from ${modelDir.absolutePath} (family=$family)")
        tts = OfflineTts(config = buildConfig())
        registerModelEvidence()
        Log.i(TAG, "Initialized sherpa-onnx TTS for ${this.modelName} (sampleRate=${tts.sampleRate})")
    }

    /**
     * Synthesize speech from text.
     *
     * @param text Non-empty input text.
     * @param voice Optional voice id (Kokoro: e.g. "af_bella"). Falls back to
     *   the model's default speaker when absent or unknown.
     * @param speed Multiplier; 1.0 default. Must be positive.
     * @return [SherpaTtsResult] carrying a 16-bit PCM mono WAV plus metadata.
     */
    fun synthesize(
        text: String,
        voice: String? = null,
        speed: Float = 1.0f,
    ): SherpaTtsResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            throw SherpaTtsException("`text` must be a non-empty string.")
        }
        if (speed <= 0f) {
            throw SherpaTtsException("`speed` must be positive.")
        }

        val sid = family.speakerId(voice)
        val audio = tts.generate(text, sid = sid, speed = speed)
        val sampleRate = audio.sampleRate
        val samples = audio.samples
        val wav = wavWrap(samples, sampleRate)
        val durationMs = if (sampleRate > 0) (1000L * samples.size / sampleRate).toInt() else 0

        return SherpaTtsResult(
            audioBytes = wav,
            contentType = "audio/wav",
            format = "wav",
            sampleRate = sampleRate,
            durationMs = durationMs,
            voice = voice,
            model = modelName,
        )
    }

    /** Release the underlying sherpa-onnx handle. Idempotent. */
    fun release() {
        try {
            tts.release()
        } catch (e: Throwable) {
            Log.d(TAG, "Failed to release TTS (non-fatal): ${e.message}")
        }
    }

    private fun buildConfig(): OfflineTtsConfig {
        val modelOnnx = File(modelDir, "model.onnx").absolutePath
        val tokens = File(modelDir, "tokens.txt").absolutePath
        val dataDir = File(modelDir, "espeak-ng-data").absolutePath

        val modelConfig = when (family) {
            SherpaTtsFamily.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = modelOnnx,
                    voices = File(modelDir, "voices.bin").absolutePath,
                    tokens = tokens,
                    dataDir = dataDir,
                ),
                numThreads = 2,
                provider = "cpu",
            )
            SherpaTtsFamily.VITS -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelOnnx,
                    tokens = tokens,
                    dataDir = dataDir,
                ),
                numThreads = 2,
                provider = "cpu",
            )
        }
        return OfflineTtsConfig(model = modelConfig)
    }

    private fun registerModelEvidence() {
        try {
            DeviceRuntimeProfileCollector.registerEvidence(
                InstalledRuntime.modelCapable(
                    engine = "sherpa-onnx",
                    model = modelName,
                    capability = "tts",
                    artifactFormat = "onnx",
                ),
            )
        } catch (e: Exception) {
            Log.d(TAG, "Failed to register model evidence (non-fatal): ${e.message}")
        }
    }

    private fun wavWrap(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcmBytes = ByteArray(samples.size * 2)
        var idx = 0
        for (s in samples) {
            val clipped = s.coerceIn(-1.0f, 1.0f)
            val v = (clipped * 32767.0f).toInt().toShort()
            pcmBytes[idx++] = (v.toInt() and 0xFF).toByte()
            pcmBytes[idx++] = ((v.toInt() shr 8) and 0xFF).toByte()
        }

        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { out ->
            // RIFF header (little-endian writes via manual bytes).
            out.writeBytes("RIFF")
            writeIntLE(out, 36 + pcmBytes.size)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            writeIntLE(out, 16)            // PCM chunk size
            writeShortLE(out, 1)           // PCM
            writeShortLE(out, 1)           // mono
            writeIntLE(out, sampleRate)
            writeIntLE(out, sampleRate * 2) // byte rate
            writeShortLE(out, 2)           // block align
            writeShortLE(out, 16)          // bits per sample
            out.writeBytes("data")
            writeIntLE(out, pcmBytes.size)
        }
        return header.toByteArray() + pcmBytes
    }

    private fun writeIntLE(out: DataOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(out: DataOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }
}
