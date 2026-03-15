package ai.octomil.sample.chat

import ai.octomil.chat.GenerateConfig
import ai.octomil.chat.LLMRuntime
import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [LLMRuntime] backed by llama.cpp via the AiChat inference engine.
 *
 * Wraps [InferenceEngine] behind the SDK's pluggable runtime interface.
 * Model is loaded eagerly via [loadModel] (called from ChatViewModel on
 * screen entry) rather than lazily on first generate().
 *
 * Thread safety: all engine operations are serialized by the engine's own
 * single-threaded dispatcher (`limitedParallelism(1)`).
 */
class LlamaCppRuntime(
    private val modelFile: File,
    context: Context,
) : LLMRuntime {

    private val engine: InferenceEngine = AiChat.getInferenceEngine(context)
    private val modelLoaded = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * Pre-load the model. Call this on screen entry, not on first generate().
     * Suspends until the model is ready or throws on failure.
     */
    suspend fun loadModel() {
        if (closed.get()) throw IllegalStateException("Runtime is closed")
        if (modelLoaded.get()) return

        Timber.i("LlamaCppRuntime: loading model %s", modelFile.absolutePath)
        engine.loadModel(modelFile.absolutePath)

        // Wait for ModelReady state
        engine.state.first { state ->
            when (state) {
                is InferenceEngine.State.ModelReady -> true
                is InferenceEngine.State.Error -> throw state.exception
                else -> false
            }
        }
        modelLoaded.set(true)
        Timber.i("LlamaCppRuntime: model loaded")
    }

    override fun generate(prompt: String, config: GenerateConfig): Flow<String> {
        if (closed.get()) return emptyFlow()
        if (!modelLoaded.get()) {
            return flow {
                loadModel()
                engine.sendUserPrompt(prompt, config.maxTokens).collect { emit(it) }
            }
        }
        return engine.sendUserPrompt(prompt, config.maxTokens)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            Timber.i("LlamaCppRuntime: closing")
            engine.cleanUp()
        }
    }
}
