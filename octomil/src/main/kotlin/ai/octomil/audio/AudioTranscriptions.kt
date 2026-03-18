package ai.octomil.audio

import ai.octomil.ModelResolver
import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.speech.SpeechRuntimeRegistry
import android.content.Context
import java.io.File

/**
 * Response format for audio transcription, matching contract v1.5.0.
 */
enum class TranscriptionResponseFormat(val wire: String) {
    TEXT("text"),
    JSON("json"),
    VERBOSE_JSON("verbose_json"),
    SRT("srt"),
    VTT("vtt"),
}

/**
 * Timestamp granularity for transcription segments, matching contract v1.5.0.
 */
enum class TimestampGranularity(val wire: String) {
    WORD("word"),
    SEGMENT("segment"),
}

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
    val confidence: Float? = null,
)

/**
 * Options for audio transcription — contract fields only.
 */
data class TranscriptionOptions(
    /** Hint for the expected language (BCP-47 code). */
    val language: String? = null,
    /** Response format. Default is TEXT. */
    val responseFormat: TranscriptionResponseFormat = TranscriptionResponseFormat.TEXT,
    /** Timestamp granularities to include in the response. */
    val timestampGranularities: List<TimestampGranularity> = emptyList(),
)

/**
 * Sub-resource of [ai.octomil.speech.OctomilAudio] — mirrors the `audio.transcriptions` path.
 *
 * ```kotlin
 * val result = Octomil.audio.transcriptions.create(audioFile, "whisper-small")
 * println(result.text)
 * ```
 */
class AudioTranscriptions internal constructor(
    private val contextProvider: () -> Context?,
    private val resolver: ModelResolver,
) {
    /**
     * Transcribe an audio file using the specified model.
     *
     * Routes through [SpeechRuntimeRegistry] to the speech-specific runtime
     * (e.g. sherpa-onnx), not the text-generation RuntimeRequest path.
     *
     * @param audioFile The audio file to transcribe (WAV, MP3, M4A, etc.).
     * @param model Logical model name (required per contract).
     * @param options Transcription options.
     * @return Transcription result.
     */
    suspend fun create(
        audioFile: File,
        model: String,
        options: TranscriptionOptions = TranscriptionOptions(),
    ): TranscriptionResult {
        require(audioFile.exists()) { "Audio file not found: ${audioFile.absolutePath}" }

        // Reject options the current runtime cannot honor
        validateOptions(options)

        val context = contextProvider()
            ?: throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "Not initialized. Call Octomil.configure() first.",
            )
        val factory = SpeechRuntimeRegistry.factory
            ?: throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "No speech runtime factory registered.",
            )

        // Resolve model by name (required per contract)
        val modelFile = resolver.resolve(context, model)
            ?: throw OctomilException(
                OctomilErrorCode.MODEL_NOT_FOUND,
                "Model '$model' not found.",
            )
        val modelDir = if (modelFile.isDirectory) modelFile else modelFile.parentFile!!

        // Decode audio file to 16kHz mono float samples
        val samples = AudioFileDecoder.decode(audioFile)

        // Create runtime, transcribe, return
        val runtime = factory(modelDir)
        val session = runtime.startSession()
        try {
            session.feed(samples)
            val text = session.finalize()
            return TranscriptionResult(text = text)
        } finally {
            session.release()
            runtime.release()
        }
    }

    /**
     * Reject options the current SpeechRuntime cannot honor.
     *
     * Uses UNSUPPORTED_MODALITY (not INVALID_INPUT) because these are
     * contract-valid values that the local engine doesn't support.
     */
    internal fun validateOptions(options: TranscriptionOptions) {
        if (options.responseFormat != TranscriptionResponseFormat.TEXT &&
            options.responseFormat != TranscriptionResponseFormat.JSON
        ) {
            throw OctomilException(
                OctomilErrorCode.UNSUPPORTED_MODALITY,
                "response_format '${options.responseFormat.wire}' is not supported " +
                    "by the current runtime. Supported: text, json.",
            )
        }
        if (options.timestampGranularities.isNotEmpty()) {
            throw OctomilException(
                OctomilErrorCode.UNSUPPORTED_MODALITY,
                "timestamp_granularities is not supported by the current runtime.",
            )
        }
    }
}
