package ai.octomil.audio

import ai.octomil.ModelResolver
import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RoutingPolicy
import ai.octomil.prepare.PrepareArtifactPlan
import ai.octomil.prepare.PrepareCandidate
import ai.octomil.prepare.PrepareManager
import ai.octomil.prepare.PrepareMode
import ai.octomil.prepare.PrepareOutcome
import ai.octomil.runtime.routing.ModelRefParser
import ai.octomil.runtime.routing.ParsedModelRef
import ai.octomil.speech.SpeechRuntime
import ai.octomil.speech.SpeechRuntimeRegistry
import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

/** Outcome of [AudioTranscriptions.warmup]. */
data class TranscriptionWarmupOutcome(
    val artifactDir: File,
    val cached: Boolean,
    val runtimeReused: Boolean,
    val model: String,
)

/** Internal resolution view for transcription prepare/warmup. */
internal data class TranscriptionResolution(
    val canonicalModelId: String,
    val warmupKey: String,
    val candidate: PrepareCandidate,
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
    private val prepareManager: PrepareManager = PrepareManager(),
) {
    private val warmedMutex = Mutex()
    private var warmedRuntimeKey: String? = null
    private var warmedRuntime: SpeechRuntime? = null

    /**
     * Materialize the STT model's on-disk artifact via
     * [PrepareManager]. The candidate is built from the planner-
     * supplied download metadata; an `app=` argument or `@app/`
     * model ref scopes the artifact directory under that app's
     * identity so private models cannot fall back to public
     * artifacts. Identity is gated up front: a mismatch between
     * `@app/` and `app=` is a hard error, never silent.
     */
    suspend fun prepare(
        model: String,
        digest: String,
        downloadUrl: String,
        relativePath: String? = null,
        sizeBytes: Long? = null,
        app: String? = null,
        policy: RoutingPolicy? = null,
    ): PrepareOutcome = withContext(Dispatchers.IO) {
        val resolution = resolveTranscriptionCandidate(
            model = model,
            app = app,
            policy = policy,
            digest = digest,
            downloadUrl = downloadUrl,
            relativePath = relativePath,
            sizeBytes = sizeBytes,
        )
        prepareManager.prepare(resolution.candidate, mode = PrepareMode.EXPLICIT)
    }

    /**
     * Prepare AND load the [SpeechRuntime] backend so the next
     * [create] call reuses the warmed handle. Mirrors
     * [ai.octomil.audio.AudioSpeech.warmup].
     */
    suspend fun warmup(
        model: String,
        digest: String,
        downloadUrl: String,
        relativePath: String? = null,
        sizeBytes: Long? = null,
        app: String? = null,
        policy: RoutingPolicy? = null,
    ): TranscriptionWarmupOutcome = withContext(Dispatchers.IO) {
        val resolution = resolveTranscriptionCandidate(
            model = model,
            app = app,
            policy = policy,
            digest = digest,
            downloadUrl = downloadUrl,
            relativePath = relativePath,
            sizeBytes = sizeBytes,
        )
        val outcome = prepareManager.prepare(resolution.candidate, mode = PrepareMode.EXPLICIT)
        val factory = SpeechRuntimeRegistry.factory
            ?: throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "No speech runtime factory registered.",
            )
        warmedMutex.withLock {
            val key = resolution.warmupKey
            if (warmedRuntime != null && warmedRuntimeKey == key) {
                return@withLock TranscriptionWarmupOutcome(
                    artifactDir = outcome.artifactDir,
                    cached = outcome.cached,
                    runtimeReused = true,
                    model = resolution.canonicalModelId,
                )
            }
            warmedRuntime?.runCatching { release() }
            val runtime = factory(outcome.artifactDir)
            warmedRuntime = runtime
            warmedRuntimeKey = key
            TranscriptionWarmupOutcome(
                artifactDir = outcome.artifactDir,
                cached = outcome.cached,
                runtimeReused = false,
                model = resolution.canonicalModelId,
            )
        }
    }

    /** Drop any warmed runtime handle. Idempotent. */
    suspend fun release() {
        warmedMutex.withLock {
            warmedRuntime?.runCatching { release() }
            warmedRuntime = null
            warmedRuntimeKey = null
        }
    }

    internal fun resolveTranscriptionCandidate(
        model: String,
        app: String?,
        policy: RoutingPolicy?,
        digest: String,
        downloadUrl: String,
        relativePath: String?,
        sizeBytes: Long?,
    ): TranscriptionResolution {
        require(model.isNotBlank()) { "model must be a non-empty string." }
        val parsed = ModelRefParser.parse(model)
        val (canonicalModelId, parsedAppSlug) = when (parsed) {
            is ParsedModelRef.AppRef -> parsed.capability to parsed.slug
            is ParsedModelRef.ModelRef -> parsed.model to null
            is ParsedModelRef.UnknownRef -> parsed.raw to null
            is ParsedModelRef.AliasRef -> parsed.alias to null
            is ParsedModelRef.CapabilityRef -> parsed.capability to null
            is ParsedModelRef.DeploymentRef -> parsed.deploymentId to null
            is ParsedModelRef.ExperimentRef -> parsed.experimentId to null
            is ParsedModelRef.DefaultRef -> "" to null
        }
        if (canonicalModelId.isBlank()) {
            throw OctomilException(
                OctomilErrorCode.INVALID_INPUT,
                "Could not resolve transcription model id from \"$model\".",
            )
        }
        if (parsedAppSlug != null && app != null && parsedAppSlug != app) {
            throw OctomilException(
                OctomilErrorCode.INVALID_INPUT,
                "@app/ ref \"$parsedAppSlug\" does not match app= argument \"$app\".",
            )
        }
        val effectiveApp = parsedAppSlug ?: app
        if (policy == RoutingPolicy.CLOUD_ONLY) {
            throw OctomilException(
                OctomilErrorCode.UNSUPPORTED_MODALITY,
                "policy=cloud_only is not supported by client.audio.transcriptions; " +
                    "use OctomilHosted for hosted STT.",
            )
        }
        val artifactId = if (effectiveApp != null) {
            "@app/$effectiveApp/$canonicalModelId"
        } else {
            canonicalModelId
        }
        val candidate = PrepareCandidate(
            locality = "local",
            engine = "sherpa-onnx",
            artifact = PrepareArtifactPlan(
                modelId = canonicalModelId,
                artifactId = artifactId,
                digest = digest,
                sizeBytes = sizeBytes,
                requiredFiles = relativePath?.let { listOf(it) } ?: emptyList(),
                downloadUrls = listOf(
                    ai.octomil.prepare.DownloadEndpoint(url = downloadUrl),
                ),
            ),
        )
        return TranscriptionResolution(
            canonicalModelId = canonicalModelId,
            warmupKey = artifactId,
            candidate = candidate,
        )
    }
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
