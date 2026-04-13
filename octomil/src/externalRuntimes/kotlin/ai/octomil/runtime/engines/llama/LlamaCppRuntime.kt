package ai.octomil.runtime.engines.llama

import ai.octomil.chat.GenerateConfig
import ai.octomil.chat.LLMRuntime
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.ProgressListener
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * [LLMRuntime] backed by llama.cpp via the AiChat InferenceEngine.
 *
 * Wraps the reference llama.android InferenceEngine behind the SDK's
 * [LLMRuntime] interface for on-device GGUF model inference.
 */
internal class LlamaCppRuntime(
    private val modelFile: File,
    private val mmprojFile: File? = null,
    context: Context,
) : LLMRuntime {

    private val engine: InferenceEngine = AiChat.getInferenceEngine(context)
    private val modelLoaded = AtomicBoolean(false)
    private val mmprojLoaded = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun setLoadProgressListener(listener: ((Float) -> Unit)?) {
        engine.setProgressListener(
            if (listener != null) ProgressListener { listener(it) } else null
        )
    }

    /**
     * Eagerly load the model. Call this before [generate] to avoid
     * cold-start latency on first prompt.
     */
    override suspend fun load() {
        check(!closed.get()) { "Runtime is closed" }
        Log.i(TAG, "Loading model: ${modelFile.absolutePath}")
        engine.loadModel(modelFile.absolutePath)
        engine.state.first { state ->
            when (state) {
                is InferenceEngine.State.ModelReady -> true
                is InferenceEngine.State.Error -> throw state.exception
                else -> false
            }
        }
        modelLoaded.set(true)
        Log.i(TAG, "Model loaded successfully")

        // Load mmproj if available. Use NonCancellable so a scope cancellation
        // during loading doesn't leave us with a native mmproj loaded but the
        // Kotlin flag unset — that causes "vision unavailable" on cached reuse.
        if (mmprojFile != null && mmprojFile.exists()) {
            try {
                withContext(NonCancellable) {
                    Log.i(TAG, "Loading mmproj: ${mmprojFile.absolutePath}")
                    engine.loadMmproj(mmprojFile.absolutePath)
                    mmprojLoaded.set(true)
                    Log.i(TAG, "Mmproj loaded. vision=${engine.supportsVision()}, audio=${engine.supportsAudio()}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load mmproj (multimodal disabled): ${e.message}")
            }
        }
    }

    override fun generate(prompt: String, config: GenerateConfig): Flow<String> {
        check(!closed.get()) { "Runtime is closed" }

        if (!modelLoaded.get()) {
            return flow {
                load()
                engine.sendUserPrompt(prompt, config.maxTokens).collect { emit(it) }
            }
        }
        return engine.sendUserPrompt(prompt, config.maxTokens)
    }

    override fun generateMultimodal(
        text: String,
        mediaData: ByteArray,
        config: GenerateConfig,
    ): Flow<String> {
        check(!closed.get()) { "Runtime is closed" }
        check(mmprojLoaded.get()) { "Mmproj not loaded — multimodal unavailable" }

        // Model-specific preprocessing: downscale to fit this runtime's max image dimension.
        // mtmd resizes internally, but decoding a 12MP image to a raw bitmap in native
        // memory causes OOM on constrained devices. Downscale on the JVM side first.
        val preprocessed = preprocessImage(mediaData)
        return engine.sendMultimodalPrompt(text, preprocessed, config.maxTokens)
    }

    /**
     * Downscale image bytes if they exceed [MAX_IMAGE_DIMENSION] on either axis.
     * Returns the original bytes if already within bounds.
     */
    private fun preprocessImage(imageBytes: ByteArray): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight

        if (w <= MAX_IMAGE_DIMENSION && h <= MAX_IMAGE_DIMENSION) return imageBytes

        val sampleSize = max(1, max(w, h) / MAX_IMAGE_DIMENSION)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOpts)
            ?: return imageBytes

        val scale = min(
            MAX_IMAGE_DIMENSION.toFloat() / sampled.width,
            MAX_IMAGE_DIMENSION.toFloat() / sampled.height,
        )
        val finalW = (sampled.width * scale).toInt()
        val finalH = (sampled.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(sampled, finalW, finalH, true)
        if (scaled !== sampled) sampled.recycle()

        Log.i(TAG, "Runtime preprocessing: ${w}x${h} → ${finalW}x${finalH} (max=$MAX_IMAGE_DIMENSION)")

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        scaled.recycle()
        return out.toByteArray()
    }

    override fun supportsVision(): Boolean = mmprojLoaded.get() && engine.supportsVision()

    override fun supportsAudio(): Boolean = mmprojLoaded.get() && engine.supportsAudio()

    // ── Next-token prediction ──

    override fun supportsPrediction(): Boolean = true

    override suspend fun loadPredictionHandle(modelPath: String): Long =
        engine.loadModelHandle(modelPath)

    override suspend fun predictNext(handle: Long, text: String, k: Int): List<Pair<String, Float>> =
        engine.predictNext(handle, text, k).map { it.text to it.probability }

    override suspend fun unloadPredictionHandle(handle: Long) =
        engine.unloadHandle(handle)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            Log.i(TAG, "Closing runtime")
            engine.cleanUp()
            engine.destroy()
        }
    }

    companion object {
        private const val TAG = "LlamaCppRuntime"

        /**
         * Max image dimension for this runtime. Keeps native bitmap allocation
         * under ~2MB (768*768*4 bytes) to prevent OOM on constrained devices.
         *
         * TODO: derive from model metadata / capability profile once available,
         * rather than hardcoding. Different VLMs have different optimal sizes
         * (SmolVLM2: 384-512, LLaVA: 336-672, Qwen-VL: 448).
         */
        private const val MAX_IMAGE_DIMENSION = 768
    }
}
