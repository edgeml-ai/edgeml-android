package ai.octomil.speech

import ai.octomil.ModelResolver
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "OctomilAudio"

/**
 * Public API for on-device speech transcription.
 *
 * Accessed via `Octomil.audio`. Resolves models through [ModelResolver],
 * creates runtimes via [SpeechRuntimeRegistry].
 *
 * ```kotlin
 * val session = Octomil.audio.streamingSession("sherpa-zipformer-en-20m")
 * session.feed(samples)
 * session.transcript.collect { text -> /* live UI */ }
 * val final = session.finalize()
 * session.release()
 * ```
 */
class OctomilAudio internal constructor(
    private val contextProvider: () -> Context?,
    private val resolver: ModelResolver = ModelResolver.default(),
) {
    /**
     * Create a streaming transcription session for the given model.
     *
     * The model is resolved via [ModelResolver.paired] (deployed via `octomil deploy`).
     * The returned [SpeechSession] is ready to receive audio chunks.
     *
     * @param modelName Logical model name (e.g., "sherpa-zipformer-en-20m").
     * @return A [SpeechSession] ready for streaming transcription.
     * @throws IllegalStateException if no speech runtime factory is registered.
     * @throws IllegalStateException if the model cannot be found.
     */
    suspend fun streamingSession(modelName: String): SpeechSession = withContext(Dispatchers.IO) {
        Log.i(TAG, "streamingSession($modelName) on ${Thread.currentThread().name}")
        val context = contextProvider()
            ?: throw IllegalStateException("Octomil not initialized — call Octomil.init(context) first")
        val factory = SpeechRuntimeRegistry.factory
            ?: throw IllegalStateException("No SpeechRuntime factory registered")

        Log.i(TAG, "Resolving model '$modelName'...")
        val modelDir = resolver.resolveSync(context, modelName)
            ?: throw IllegalStateException(
                "Speech model '$modelName' not found. " +
                    "Deploy with: octomil deploy $modelName --phone"
            )

        // The resolver returns a file inside the version dir — we need the parent dir
        // which contains all model files (encoder, decoder, joiner, tokens).
        val dir = if (modelDir.isDirectory) modelDir else modelDir.parentFile
            ?: throw IllegalStateException("Cannot determine model directory for $modelName")

        Log.i(TAG, "Model dir: ${dir.absolutePath}")
        Log.i(TAG, "Files: ${dir.listFiles()?.map { it.name }}")
        Log.i(TAG, "Creating SpeechRuntime...")
        val runtime = factory(dir)
        Log.i(TAG, "Runtime created. Starting session...")
        val session = runtime.startSession()
        Log.i(TAG, "Session started successfully")
        session
    }

    /**
     * Batch transcription convenience: feed all samples at once, return final text.
     *
     * @param modelName Logical model name.
     * @param samples 16kHz mono float samples in [-1, 1].
     * @return Transcribed text.
     */
    suspend fun transcribe(modelName: String, samples: FloatArray): String {
        val session = streamingSession(modelName)
        return try {
            session.feed(samples)
            session.finalize()
        } finally {
            session.release()
        }
    }
}
