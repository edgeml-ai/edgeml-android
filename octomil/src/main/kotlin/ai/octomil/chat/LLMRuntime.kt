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
    /** Eagerly load model weights into memory. Optional — some runtimes load lazily. */
    suspend fun load() {}

    /** Set a progress listener for model loading (0.0–1.0). Pass null to clear. */
    fun setLoadProgressListener(listener: ((Float) -> Unit)?) {}

    /** Generate text from a prompt, emitting tokens as they are produced. */
    fun generate(prompt: String, config: GenerateConfig = GenerateConfig()): Flow<String>

    /** Generate from a multimodal prompt (text + media bytes), emitting tokens. */
    fun generateMultimodal(
        text: String,
        mediaData: ByteArray,
        config: GenerateConfig = GenerateConfig(),
    ): Flow<String> = throw UnsupportedOperationException("Multimodal not supported by this runtime")

    /** Whether this runtime has vision (image) support loaded. */
    fun supportsVision(): Boolean = false

    /** Whether this runtime has audio support loaded. */
    fun supportsAudio(): Boolean = false

    // ── Next-token prediction (optional) ──

    /** Whether this runtime supports handle-based next-token prediction. */
    fun supportsPrediction(): Boolean = false

    /**
     * Load a lightweight prediction handle for the given model file.
     *
     * The handle is independent of the main chat context and can be used
     * concurrently with [generate] calls. Call [unloadPredictionHandle]
     * to release resources.
     *
     * @return An opaque handle ID, or -1 on failure.
     */
    suspend fun loadPredictionHandle(modelPath: String): Long = -1

    /**
     * Predict the top-k next tokens for the given context.
     *
     * @param handle Handle from [loadPredictionHandle].
     * @param text Input context text.
     * @param k Number of raw candidates to retrieve.
     * @return List of (token, score) pairs, ranked by score descending.
     */
    suspend fun predictNext(handle: Long, text: String, k: Int): List<Pair<String, Float>> =
        emptyList()

    /** Release a prediction handle loaded via [loadPredictionHandle]. */
    suspend fun unloadPredictionHandle(handle: Long) {}

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
