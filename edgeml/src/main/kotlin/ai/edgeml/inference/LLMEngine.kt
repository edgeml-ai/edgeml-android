package ai.edgeml.inference

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * MediaPipe LLM Inference API-based text generation engine.
 *
 * Each generated token is emitted as an [InferenceChunk] with UTF-8 encoded
 * token text. In production this delegates to MediaPipe's `LlmInference` API.
 *
 * **STATUS: NOT IMPLEMENTED.** This engine requires integration with MediaPipe
 * LLM Inference or another on-device LLM runtime. Calling [generate] will throw
 * [NotImplementedError]. To use streaming text generation, provide your own
 * [StreamingInferenceEngine] implementation to [ai.edgeml.client.EdgeMLClient.generateStream].
 *
 * @param context Android application context.
 * @param modelPath Path to the MediaPipe `.task` model file.
 * @param maxTokens Maximum tokens to generate.
 * @param temperature Sampling temperature.
 */
class LLMEngine(
    private val context: Context,
    private val modelPath: File? = null,
    private val maxTokens: Int = 512,
    private val temperature: Float = 0.7f,
) : StreamingInferenceEngine {
    override fun generate(
        input: Any,
        modality: Modality,
    ): Flow<InferenceChunk> =
        flow {
            throw NotImplementedError(
                "LLMEngine is not yet implemented. " +
                    "Provide a custom StreamingInferenceEngine to generateStream() " +
                    "or integrate MediaPipe LlmInference. " +
                    "See: https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android",
            )
        }
}
