package ai.octomil.chat

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Pluggable interface for on-device LLM inference.
 *
 * Implement this to integrate any LLM runtime (MediaPipe, ONNX GenAI,
 * llama.cpp, etc.) with the Octomil chat API.
 *
 * ```kotlin
 * class MediaPipeLLMRuntime(modelFile: File) : LLMRuntime {
 *     override fun generate(prompt: String, config: GenerateConfig): Flow<String> {
 *         // MediaPipe LLM inference
 *     }
 * }
 *
 * // Register:
 * LLMRuntimeRegistry.register(MyRuntime(modelFile))
 * ```
 */
interface LLMRuntime {
    /** Generate text from a prompt, emitting tokens as they are produced. */
    fun generate(prompt: String, config: GenerateConfig = GenerateConfig()): Flow<String>

    /** Release resources held by this runtime. */
    fun close()
}

/**
 * Configuration for text generation.
 */
data class GenerateConfig(
    /** Maximum tokens to generate. */
    val maxTokens: Int = 512,
    /** Sampling temperature. */
    val temperature: Float = 0.7f,
    /** Top-p nucleus sampling. */
    val topP: Float = 1.0f,
    /** Stop sequences. */
    val stop: List<String>? = null,
)

/**
 * Global registry for LLM runtimes.
 *
 * Apps register their LLM runtime at initialization. The Octomil SDK
 * uses it when creating chat interfaces.
 *
 * ```kotlin
 * // In Application.onCreate():
 * LLMRuntimeRegistry.factory = { modelFile ->
 *     MediaPipeLLMRuntime(modelFile)
 * }
 * ```
 */
object LLMRuntimeRegistry {
    /**
     * Factory that creates an [LLMRuntime] for a given model file.
     *
     * Set this before calling [Octomil.chat]. If null, the SDK falls back
     * to the built-in [StreamingInferenceEngine] which may be a stub.
     */
    var factory: ((File) -> LLMRuntime)? = null
}
