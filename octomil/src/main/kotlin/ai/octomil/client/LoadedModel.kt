package ai.octomil.client

import ai.octomil.models.CachedModel
import ai.octomil.models.InferenceInput
import ai.octomil.models.InferenceOutput
import ai.octomil.training.TFLiteTrainer
import ai.octomil.training.TensorInfo
import ai.octomil.training.WarmupResult

/**
 * Wrapper around a loaded TFLite model that exposes inference and
 * lifecycle operations directly on the model object.
 *
 * Obtained via [OctomilClient.getLoadedModel]. This object is only
 * valid as long as the underlying model remains loaded; a new instance
 * is returned if the model is reloaded or updated.
 *
 * ```kotlin
 * val model = client.getLoadedModel() ?: error("No model loaded")
 *
 * // Run inference
 * val output = model.predict(floatArrayOf(1.0f, 2.0f)).getOrThrow()
 *
 * // Pre-allocate runtime buffers for lower first-inference latency
 * model.warmup()
 * ```
 */
class LoadedModel internal constructor(
    /** Metadata for the underlying cached model file. */
    val cachedModel: CachedModel,
    private val trainer: TFLiteTrainer,
) {
    /** Model identifier. */
    val modelId: String get() = cachedModel.modelId

    /** Model version string. */
    val version: String get() = cachedModel.version

    /** Model file format (e.g., "tensorflow_lite"). */
    val format: String get() = cachedModel.format

    /** Model file size in bytes. */
    val sizeBytes: Long get() = cachedModel.sizeBytes

    // =========================================================================
    // Inference
    // =========================================================================

    /**
     * Run inference on the loaded model.
     *
     * @param input Input data as a flat float array.
     * @return [InferenceOutput] with results and timing.
     */
    suspend fun predict(input: FloatArray): Result<InferenceOutput> =
        trainer.runInference(input)

    /**
     * Run inference on the loaded model with structured input.
     *
     * @param input [InferenceInput] with data, shape, and optional name.
     * @return [InferenceOutput] with results and timing.
     */
    suspend fun predict(input: InferenceInput): Result<InferenceOutput> =
        trainer.runInference(input.data)

    /**
     * Stream generative inference (delegates to [OctomilClient.predictStream]).
     *
     * For streaming use cases, prefer calling `client.predictStream()` directly
     * since it requires additional parameters (modality, engine) that live at
     * the client level.
     */
    // Note: predictStream is intentionally NOT on LoadedModel because it
    // requires engine resolution and v2 telemetry plumbing that live on the
    // client. Users should call client.predictStream() instead.

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Pre-allocate runtime buffers and benchmark the active delegate.
     *
     * Calling this after model load absorbs JIT, shader compilation, and
     * delegate initialization costs so the first real inference is fast.
     *
     * @return [WarmupResult] with timing breakdown, or null if the model
     *         is no longer loaded.
     */
    suspend fun warmup(): WarmupResult? = trainer.warmup()

    /**
     * Get the model's input/output tensor information.
     *
     * @return Tensor metadata or null if not available.
     */
    fun getTensorInfo(): TensorInfo? = trainer.getTensorInfo()

    /**
     * Whether the model supports on-device gradient-based training.
     */
    val hasTrainingSignature: Boolean get() = trainer.hasTrainingSignature()

    /**
     * Available TFLite signature keys (e.g., ["train", "infer", "save"]).
     */
    val signatureKeys: List<String> get() = trainer.getSignatureKeys()
}
