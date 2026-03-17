package ai.octomil.text

import ai.octomil.ModelResolver
import ai.octomil.chat.LLMRuntime
import ai.octomil.chat.LLMRuntimeRegistry
import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.ModelCapability
import ai.octomil.manifest.ModelCatalogService
import ai.octomil.manifest.ModelRef
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Canonical API for next-token text predictions.
 *
 * Accessed via `Octomil.text.predictions`:
 * ```kotlin
 * val result = Octomil.text.predictions.create(
 *     TextPredictionRequest(
 *         model = ModelRef.Capability(ModelCapability.KEYBOARD_PREDICTION),
 *         input = "Hello, how ar",
 *     )
 * )
 * result.predictions.forEach { println("${it.text} (${it.score})") }
 * ```
 *
 * Uses handle-based prediction for efficient next-token inference.
 * Handles are loaded lazily and cached per model.
 */
class OctomilTextPredictions internal constructor(
    private val catalogProvider: () -> ModelCatalogService?,
    private val contextProvider: () -> Context?,
) {
    private val mutex = Mutex()
    private val handles = mutableMapOf<String, HandleEntry>()

    private class HandleEntry(
        val runtime: LLMRuntime,
        val handle: Long,
    )

    /**
     * Predict the next tokens for the given input context.
     *
     * Resolves the model from the catalog (by ID or capability), loads a
     * prediction handle if needed, and returns filtered candidates.
     *
     * @throws OctomilException with [OctomilErrorCode.MODEL_NOT_FOUND] if the model cannot be resolved.
     * @throws OctomilException with [OctomilErrorCode.RUNTIME_UNAVAILABLE] if no prediction runtime is available.
     */
    suspend fun create(request: TextPredictionRequest): TextPredictionResult {
        val modelId = resolveModelId(request.model)

        val t0 = System.currentTimeMillis()

        val entry = mutex.withLock { getOrCreateHandle(modelId) }
        val raw = entry.runtime.predictNext(entry.handle, request.input, RAW_CANDIDATES)
        val elapsed = System.currentTimeMillis() - t0

        val filtered = TokenSuggestionFilter.processWithScore(raw).take(request.n)

        return TextPredictionResult(
            predictions = filtered.map { (text, score) ->
                TextPredictionCandidate(text = text, score = score)
            },
            model = modelId,
            latencyMs = elapsed,
        )
    }

    /**
     * Unload the prediction handle for a specific model.
     */
    suspend fun unload(modelName: String) {
        mutex.withLock {
            handles.remove(modelName)?.let { entry ->
                entry.runtime.unloadPredictionHandle(entry.handle)
                Timber.d("Unloaded prediction handle for: $modelName")
            }
        }
    }

    /**
     * Unload all prediction handles.
     */
    suspend fun unloadAll() {
        mutex.withLock {
            for ((name, entry) in handles) {
                entry.runtime.unloadPredictionHandle(entry.handle)
                Timber.d("Unloaded prediction handle for: $name")
            }
            handles.clear()
        }
    }

    private fun resolveModelId(ref: ModelRef): String {
        return when (ref) {
            is ModelRef.Id -> ref.value
            is ModelRef.Capability -> {
                val catalog = catalogProvider()
                    ?: throw OctomilException(
                        OctomilErrorCode.RUNTIME_UNAVAILABLE,
                        "ModelCatalogService not configured. Call Octomil.configure() first.",
                    )
                catalog.modelIdForCapability(ref.value)
                    ?: throw OctomilException(
                        OctomilErrorCode.MODEL_NOT_FOUND,
                        "No model configured for capability '${ref.value.code}'. " +
                            "Add an AppModelEntry with this capability to your AppManifest.",
                    )
            }
        }
    }

    private suspend fun getOrCreateHandle(modelId: String): HandleEntry {
        handles[modelId]?.let { return it }

        // Resolve model file and create a prediction-capable LLMRuntime
        val context = contextProvider()
            ?: throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "Context not available. Call Octomil.init() or Octomil.configure() first.",
            )

        val modelFile = ModelResolver.paired().resolveSync(context, modelId)
            ?: ModelResolver.default().resolveSync(context, modelId)
            ?: throw OctomilException(
                OctomilErrorCode.MODEL_NOT_FOUND,
                "Model '$modelId' not found on device.",
            )

        val runtime = LLMRuntimeRegistry.factory?.invoke(modelFile)
            ?: throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "No LLMRuntime factory registered. Set LLMRuntimeRegistry.factory in Application.onCreate().",
            )

        if (!runtime.supportsPrediction()) {
            throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "LLMRuntime for '$modelId' does not support next-token prediction.",
            )
        }

        val handle = runtime.loadPredictionHandle(modelFile.absolutePath)
        if (handle < 0) {
            throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "Failed to load prediction handle for '$modelId'.",
            )
        }

        val entry = HandleEntry(runtime, handle)
        handles[modelId] = entry
        Timber.d("Loaded prediction handle for: $modelId (handle=$handle)")
        return entry
    }

    companion object {
        /** Number of raw candidates to fetch before filtering. */
        private const val RAW_CANDIDATES = 8
    }
}
