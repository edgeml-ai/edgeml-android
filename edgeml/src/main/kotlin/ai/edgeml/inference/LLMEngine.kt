package ai.edgeml.inference

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * LLM Inference text generation engine.
 *
 * Each generated token is emitted as an [InferenceChunk] with UTF-8 encoded
 * token text. This stub implementation splits input on whitespace and emits
 * one chunk per token, capped at [maxTokens]. Swap in a MediaPipe or other
 * on-device LLM runtime for production use.
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
            val prompt = input.toString()
            val tokens = prompt.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            val output = (if (tokens.isEmpty()) listOf("output") else tokens).take(maxTokens)

            output.forEachIndexed { index, token ->
                emit(
                    InferenceChunk(
                        index = index,
                        data = token.toByteArray(Charsets.UTF_8),
                        modality = Modality.TEXT,
                        timestamp = System.currentTimeMillis(),
                        latencyMs = 0.0,
                    ),
                )
            }
        }
}
