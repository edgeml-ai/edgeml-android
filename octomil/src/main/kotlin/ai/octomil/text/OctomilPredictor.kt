package ai.octomil.text

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.MessageRole
import ai.octomil.generated.ModelCapability
import ai.octomil.manifest.ModelCatalogService
import ai.octomil.runtime.core.GenerationConfig
import ai.octomil.runtime.core.ModelRuntime
import ai.octomil.runtime.core.RuntimeContentPart
import ai.octomil.runtime.core.RuntimeMessage
import ai.octomil.runtime.core.RuntimeRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Result of a text prediction request.
 */
data class PredictionResult(
    /** The predicted text (next word/phrase). */
    val text: String,
    /** Ranked prediction candidates, if available. */
    val candidates: List<PredictionCandidate> = emptyList(),
)

/**
 * A single prediction candidate with confidence score.
 */
data class PredictionCandidate(
    val text: String,
    val confidence: Float,
)

/**
 * Stateful predictor for keyboard-style text prediction.
 *
 * Keeps a warm reference to the runtime so repeated predictions avoid
 * re-resolving the capability. Create via [OctomilText.predictor]:
 *
 * ```kotlin
 * val predictor = Octomil.text.predictor()
 * val result = predictor.predict("Hello, how ar")
 * println(result.text) // "are you"
 * predictor.close()
 * ```
 */
class OctomilPredictor internal constructor(
    private val runtime: ModelRuntime,
) : AutoCloseable {

    /**
     * Predict the next text given the current input context.
     *
     * @param context The text typed so far.
     * @param maxCandidates Maximum number of candidates to return.
     * @return Prediction result with best match and candidates.
     */
    suspend fun predict(
        context: String,
        maxCandidates: Int = 3,
    ): PredictionResult {
        val request = RuntimeRequest(
            messages = listOf(RuntimeMessage(role = MessageRole.USER, parts = listOf(RuntimeContentPart.Text(context)))),
            generationConfig = GenerationConfig(maxTokens = maxCandidates * 5, temperature = 0.0f),
        )
        val response = runtime.run(request)
        return PredictionResult(text = response.text)
    }

    /**
     * Stream prediction tokens as they are generated.
     */
    fun predictStream(context: String): Flow<String> {
        val request = RuntimeRequest(
            messages = listOf(RuntimeMessage(role = MessageRole.USER, parts = listOf(RuntimeContentPart.Text(context)))),
            generationConfig = GenerationConfig(maxTokens = 10, temperature = 0.0f),
        )
        return runtime.stream(request).map { chunk -> chunk.text ?: "" }
    }

    override fun close() {
        runtime.close()
    }
}
