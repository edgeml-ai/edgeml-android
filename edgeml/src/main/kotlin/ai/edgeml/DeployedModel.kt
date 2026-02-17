package ai.edgeml

import ai.edgeml.models.InferenceOutput
import ai.edgeml.training.WarmupResult

/**
 * A model deployed to a specific inference engine.
 *
 * Created via [EdgeML.deploy]. Provides a unified inference API
 * regardless of the underlying engine.
 */
class DeployedModel internal constructor(
    /** Human-readable model name (derived from filename). */
    val name: String,
    /** The inference engine used. */
    val engine: Engine,
    private val localModel: LocalModel,
) {
    /** Warmup benchmark results, populated when deployed with `benchmark = true`. */
    var warmupResult: WarmupResult? = null
        internal set

    /** Active compute delegate after benchmarking (e.g. "gpu", "cpu", "vendor_npu"). */
    val activeDelegate: String get() = warmupResult?.activeDelegate ?: "unknown"

    /** Whether the model is loaded and ready for inference. */
    val isLoaded: Boolean get() = localModel.isLoaded

    /** Input tensor shape. */
    val inputShape: IntArray get() = localModel.inputShape

    /** Output tensor shape. */
    val outputShape: IntArray get() = localModel.outputShape

    /**
     * Run inference on the input data.
     *
     * @param input Float array matching the model's input shape.
     * @return Inference output with predictions and timing.
     */
    suspend fun predict(input: FloatArray): Result<InferenceOutput> =
        localModel.runInference(input)

    /**
     * Run classification and return top-K results.
     *
     * @param input Float array matching the model's input shape.
     * @param topK Number of top predictions to return.
     * @return List of (classIndex, confidence) pairs.
     */
    suspend fun classify(input: FloatArray, topK: Int = 5): Result<List<Pair<Int, Float>>> =
        localModel.classify(input, topK)

    /**
     * Run batch inference on multiple inputs.
     *
     * @param inputs List of float arrays.
     * @return List of inference outputs.
     */
    suspend fun predictBatch(inputs: List<FloatArray>): Result<List<InferenceOutput>> =
        localModel.runBatchInference(inputs)

    /**
     * Release model resources.
     */
    suspend fun close() = localModel.close()
}
