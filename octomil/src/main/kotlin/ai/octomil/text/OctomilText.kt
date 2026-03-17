package ai.octomil.text

import ai.octomil.ModelResolver
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Temporary text prediction helper. Accessed via `Octomil.text`.
 *
 * Will be replaced by canonical `text.predictions.create(TextPredictionRequest)`
 * in a follow-up PR once contract schemas are defined.
 *
 * Tracks one active prediction model at a time by name.
 * If a different model is requested, the previous is unloaded first.
 * Structured so PR 2 can upgrade to a per-model-name handle map.
 *
 * Thread-safe: all mutable state guarded by [mutex]. Heavy work
 * (model resolution, loading) runs on [Dispatchers.IO].
 */
class OctomilText internal constructor(
    private val contextProvider: () -> Context?,
    private val resolver: ModelResolver = ModelResolver.default(),
) {
    private val mutex = Mutex()
    private var runtime: PredictionRuntime? = null
    private var activeHandle: Long? = null
    private var activeModelName: String? = null
    /** Cached resolved path — avoids repeated file scans on every keystroke. */
    private var resolvedModelPath: String? = null

    /**
     * Predict next words from context text. Loads model on first call.
     * If [modelName] differs from the currently loaded model, unloads
     * the previous model and loads the new one.
     *
     * Model resolution and first-load run on [Dispatchers.IO].
     * Subsequent predictions skip resolution (path is cached).
     *
     * @param modelName Logical model name (e.g., "smollm2-135m").
     * @param text Context text for prediction.
     * @param k Number of raw predictions to fetch (filtered result is typically 3).
     * @return Filtered word suggestions (subword-stripped, deduped, top 3).
     */
    suspend fun predict(modelName: String, text: String, k: Int = 8): List<String> =
        mutex.withLock {
            val ctx = contextProvider() ?: error("Octomil not initialized")

            if (runtime == null) {
                runtime = PredictionRuntimeRegistry.factory?.invoke()
                    ?: error("No PredictionRuntime factory registered")
            }

            // Switch model if a different one is requested
            if (activeModelName != null && activeModelName != modelName) {
                unloadLocked()
            }

            if (activeHandle == null) {
                // Model resolution + load on IO thread — avoids janking UI
                withContext(Dispatchers.IO) {
                    val modelDir = resolver.resolveSync(ctx, modelName)
                        ?: error("Prediction model '$modelName' not found")
                    val modelFile = if (modelDir.isDirectory) {
                        modelDir.listFiles()?.firstOrNull { it.extension == "gguf" }
                            ?: error("No GGUF file in ${modelDir.absolutePath}")
                    } else {
                        modelDir
                    }
                    resolvedModelPath = modelFile.absolutePath
                    activeHandle = runtime!!.loadHandle(modelFile.absolutePath)
                    activeModelName = modelName
                }
            }

            val raw = runtime!!.predictNext(activeHandle!!, text, k)
            TokenSuggestionFilter.process(raw)
        }

    /** Unload current prediction model and free resources. Thread-safe. */
    suspend fun unload() = mutex.withLock { unloadLocked() }

    private suspend fun unloadLocked() {
        activeHandle?.let { runtime?.unloadHandle(it) }
        activeHandle = null
        activeModelName = null
        resolvedModelPath = null
    }
}
