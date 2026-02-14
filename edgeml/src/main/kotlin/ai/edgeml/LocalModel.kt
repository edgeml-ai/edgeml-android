package ai.edgeml

import ai.edgeml.models.CachedModel
import ai.edgeml.models.InferenceOutput
import ai.edgeml.training.TFLiteTrainer
import ai.edgeml.training.WarmupResult
import java.io.File

/**
 * A locally-loaded ML model for server-free inference.
 *
 * Created via [EdgeML.loadModel]. Wraps [TFLiteTrainer] for inference without
 * any server dependency.
 *
 * ```kotlin
 * val model = EdgeML.loadModel(context, "classifier.tflite")
 * val result = model.runInference(floatArrayOf(1f, 2f, 3f)).getOrThrow()
 * model.close()
 * ```
 *
 * **Upgrade to server platform:** The [cachedModel] property bridges local and
 * server workflows. When ready for federated learning or analytics, construct an
 * `EdgeMLClient` and use the same model ID.
 */
class LocalModel internal constructor(
    /** The underlying cached model metadata. */
    val cachedModel: CachedModel,
    private val trainer: TFLiteTrainer,
    /** The model file on disk. */
    val modelFile: File,
) {
    /** Whether the model is currently loaded and ready for inference. */
    val isLoaded: Boolean get() = trainer.isModelLoaded()

    /** The expected input tensor shape. */
    val inputShape: IntArray get() = trainer.inputShape

    /** The expected output tensor shape. */
    val outputShape: IntArray get() = trainer.outputShape

    /**
     * Run inference on a single input.
     *
     * @param input Input data as a float array matching [inputShape].
     * @return [Result] containing [InferenceOutput] with predictions and timing.
     */
    suspend fun runInference(input: FloatArray): Result<InferenceOutput> =
        trainer.runInference(input)

    /**
     * Run inference and return top-K class predictions sorted by confidence.
     *
     * @param input Input data as a float array.
     * @param topK Number of top predictions to return (default 5).
     * @return [Result] containing a list of (classIndex, confidence) pairs.
     */
    suspend fun classify(input: FloatArray, topK: Int = 5): Result<List<Pair<Int, Float>>> =
        trainer.classify(input, topK)

    /**
     * Run batch inference on multiple inputs.
     *
     * Attempts true batched execution (single kernel launch) when the model
     * supports dynamic batch size. Falls back to sequential inference otherwise.
     *
     * @param inputs List of input data arrays.
     * @return [Result] containing a list of [InferenceOutput]s.
     */
    suspend fun runBatchInference(inputs: List<FloatArray>): Result<List<InferenceOutput>> =
        trainer.runBatchInference(inputs)

    /**
     * Warm up the model interpreter and validate hardware delegate performance.
     *
     * Benchmarks the active delegate against CPU and disables it if CPU is faster,
     * cascading through the delegate priority chain (vendor NPU -> GPU -> CPU).
     *
     * @return [WarmupResult] with timing breakdown, or null if the model isn't loaded.
     */
    suspend fun warmup(): WarmupResult? = trainer.warmup()

    /**
     * Release all resources held by this model (interpreter, delegates, buffers).
     *
     * The model cannot be used for inference after calling this method.
     */
    suspend fun close() = trainer.close()
}
