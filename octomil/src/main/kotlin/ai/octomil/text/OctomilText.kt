package ai.octomil.text

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.ModelCapability
import ai.octomil.manifest.ModelCatalogService
import ai.octomil.manifest.ModelRef
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
 * ## Convenience shorthand
 * ```kotlin
 * val suggestions = Octomil.text.predict(prefix = "Hello, how ar")
 * // ["are you", "are", "is"]
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
     * Convenience wrapper around [predictions].create that returns suggestion
     * strings directly.
     *
     * Mirrors the iOS SDK's `text.predict(prefix:model:maxSuggestions:)`.
     *
     * @param prefix The text typed so far.
     * @param model Model reference — by ID or capability.
     * @param maxSuggestions Maximum suggestions to return.
     * @return List of completion suggestion strings.
     */
    suspend fun predict(
        prefix: String,
        model: ModelRef = ModelRef.Capability(ModelCapability.KEYBOARD_PREDICTION),
        maxSuggestions: Int = 3,
    ): List<String> {
        val result = predictions.create(
            TextPredictionRequest(model = model, input = prefix, n = maxSuggestions),
        )
        return result.predictions.map { it.text }
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
