package ai.octomil.audio

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.ModelCapability
import ai.octomil.manifest.ModelCatalogService
import ai.octomil.runtime.core.RuntimeRequest
import java.io.File

/**
 * Result of an audio transcription request.
 */
data class TranscriptionResult(
    /** Full transcribed text. */
    val text: String,
    /** Detected language code (e.g. "en", "es"), if available. */
    val language: String? = null,
    /** Total audio duration in milliseconds. */
    val durationMs: Long? = null,
    /** Per-segment timestamps and text, when available. */
    val segments: List<TranscriptionSegment> = emptyList(),
)

/**
 * A single timed segment within a transcription.
 */
data class TranscriptionSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Options for audio transcription.
 */
data class TranscriptionOptions(
    /** Hint for the expected language (ISO 639-1 code). */
    val language: String? = null,
    /** An optional text prompt to guide the transcription. */
    val prompt: String? = null,
    /** Temperature for sampling. Lower = more deterministic. */
    val temperature: Float = 0.0f,
)

/**
 * Sub-resource of [OctomilAudio] — mirrors the `audio.transcriptions` path.
 *
 * ```kotlin
 * val result = octomil.audio.transcriptions.create(audioFile)
 * println(result.text)
 * ```
 */
class AudioTranscriptions internal constructor(
    private val catalogProvider: () -> ModelCatalogService?,
) {
    /**
     * Transcribe an audio file.
     *
     * Resolves the runtime via [ModelCatalogService] for the
     * [ModelCapability.TRANSCRIPTION] capability.
     *
     * @param audioFile The audio file to transcribe (WAV, MP3, M4A, etc.).
     * @param options Transcription options.
     * @return Transcription result.
     */
    suspend fun create(
        audioFile: File,
        options: TranscriptionOptions = TranscriptionOptions(),
    ): TranscriptionResult {
        require(audioFile.exists()) { "Audio file not found: ${audioFile.absolutePath}" }

        val catalog = catalogProvider()
            ?: throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "ModelCatalogService not configured. Call Octomil.configure() first.",
            )

        val runtime = catalog.runtimeForCapability(ModelCapability.TRANSCRIPTION)
            ?: throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "No runtime registered for TRANSCRIPTION capability. " +
                    "Add a transcription model to your AppManifest.",
            )

        // Build a runtime request with the audio file path as the prompt.
        // The underlying transcription runtime (e.g. Whisper) interprets this
        // as the input audio path rather than text.
        val request = RuntimeRequest(
            prompt = audioFile.absolutePath,
            temperature = options.temperature,
            maxTokens = Int.MAX_VALUE,
        )

        val response = runtime.run(request)
        return TranscriptionResult(
            text = response.text,
        )
    }
}
