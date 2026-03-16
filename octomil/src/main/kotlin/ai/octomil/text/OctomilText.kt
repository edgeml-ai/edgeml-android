package ai.octomil.text

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.ModelCapability
import ai.octomil.manifest.ModelCatalogService
import ai.octomil.runtime.core.RuntimeRequest

/**
 * Text API surface — accessed via `Octomil.text`.
 *
 * ```kotlin
 * // One-shot prediction
 * val result = Octomil.text.predict("Hello, how ar")
 * println(result.text) // "are you"
 *
 * // Stateful predictor (keeps runtime warm)
 * val predictor = Octomil.text.predictor()
 * val r1 = predictor.predict("The quick brown")
 * val r2 = predictor.predict("The quick brown fox")
 * predictor.close()
 * ```
 */
class OctomilText internal constructor(
    private val catalogProvider: () -> ModelCatalogService?,
) {
    /**
     * One-shot text prediction for the given context.
     *
     * Resolves the [ModelCapability.KEYBOARD_PREDICTION] runtime from the catalog.
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
