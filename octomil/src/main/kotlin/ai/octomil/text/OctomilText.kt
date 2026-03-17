package ai.octomil.text

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.ModelCapability
import ai.octomil.manifest.ModelCatalogService
import ai.octomil.runtime.core.RuntimeRequest
import android.content.Context

/**
 * Text API surface — accessed via `Octomil.text`.
 *
 * ## Next-token predictions (canonical API)
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
 * ## Text completion (catalog-based)
 * ```kotlin
 * val result = Octomil.text.predict("Hello, how ar")
 * println(result.text) // "are you"
 * ```
 */
class OctomilText internal constructor(
    private val catalogProvider: () -> ModelCatalogService?,
    private val contextProvider: () -> Context? = { null },
) {
    /**
     * Next-token prediction sub-API.
     *
     * Uses handle-based inference for efficient keyboard prediction.
     * Models are resolved via the catalog (by ID or capability).
     */
    val predictions: OctomilTextPredictions by lazy {
        OctomilTextPredictions(
            catalogProvider = catalogProvider,
            contextProvider = contextProvider,
        )
    }

    /**
     * One-shot text completion for the given context.
     *
     * Resolves the [ModelCapability.KEYBOARD_PREDICTION] runtime from the catalog
     * and generates a text completion. For ranked next-token candidates, use
     * [predictions] instead.
     */
    suspend fun predict(context: String): PredictionResult {
        val catalog = catalogProvider()
            ?: throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "ModelCatalogService not configured. Call Octomil.configure() first.",
            )

        val runtime = catalog.runtimeForCapability(ModelCapability.KEYBOARD_PREDICTION)
            ?: throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "No runtime registered for KEYBOARD_PREDICTION capability.",
            )

        val request = RuntimeRequest(
            prompt = context,
            maxTokens = 15,
            temperature = 0.0f,
        )
        val response = runtime.run(request)
        return PredictionResult(text = response.text)
    }

    /**
     * Create a stateful [OctomilPredictor] that keeps the runtime warm.
     *
     * The caller is responsible for calling [OctomilPredictor.close] when done.
     */
    fun predictor(): OctomilPredictor {
        val catalog = catalogProvider()
            ?: throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "ModelCatalogService not configured. Call Octomil.configure() first.",
            )

        val runtime = catalog.runtimeForCapability(ModelCapability.KEYBOARD_PREDICTION)
            ?: throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "No runtime registered for KEYBOARD_PREDICTION capability.",
            )

        return OctomilPredictor(runtime)
    }
}
