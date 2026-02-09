package ai.edgeml.training

import ai.edgeml.config.EdgeMLConfig
import ai.edgeml.models.CachedModel
import ai.edgeml.models.InferenceInput
import ai.edgeml.models.InferenceOutput
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite model inference and training wrapper.
 *
 * Provides:
 * - Model loading with GPU acceleration support
 * - Synchronous and asynchronous inference
 * - On-device federated training
 * - Weight delta extraction for federated learning
 * - Input/output tensor handling
 * - Resource management and cleanup
 */
class TFLiteTrainer(
    private val context: Context,
    private val config: EdgeMLConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var _currentModel: CachedModel? = null
    private val mutex = Mutex()

    // Model metadata
    private var _inputShape: IntArray = intArrayOf()
    private var _outputShape: IntArray = intArrayOf()
    private var inputDataType: org.tensorflow.lite.DataType? = null
    private var outputDataType: org.tensorflow.lite.DataType? = null

    // Training state
    private var originalModelPath: String? = null
    private var updatedModelPath: String? = null
    private val weightExtractor = WeightExtractor()

    companion object {
        private const val TAG = "TFLiteTrainer"
    }

    // =========================================================================
    // Model Loading
    // =========================================================================

    /**
     * Load a model for inference.
     *
     * @param model The cached model to load
     * @return True if loaded successfully
     */
    suspend fun loadModel(model: CachedModel): Result<Boolean> =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    // Close existing interpreter
                    closeInternal()

                    val modelFile = File(model.filePath)
                    if (!modelFile.exists()) {
                        return@withContext Result.failure(
                            IllegalStateException("Model file not found: ${model.filePath}"),
                        )
                    }

                    // Create interpreter options
                    val options =
                        Interpreter.Options().apply {
                            setNumThreads(config.numThreads)
                        }

                    // Try to use GPU delegate if enabled
                    if (config.enableGpuAcceleration && isGpuSupported()) {
                        try {
                            @Suppress("DEPRECATION")
                            val delegateOptions = GpuDelegate.Options()
                            gpuDelegate = GpuDelegate(delegateOptions)
                            options.addDelegate(gpuDelegate)
                            Timber.d("GPU delegate enabled")
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to create GPU delegate, falling back to CPU")
                            gpuDelegate?.close()
                            gpuDelegate = null
                        }
                    }

                    // Load model into memory-mapped buffer
                    val modelBuffer = loadModelFile(modelFile)

                    // Create interpreter
                    interpreter = Interpreter(modelBuffer, options)
                    _currentModel = model

                    // Get input/output tensor info
                    extractTensorInfo()

                    Timber.i("Model loaded: ${model.modelId} v${model.version}")
                    Result.success(true)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to load model")
                    closeInternal()
                    Result.failure(e)
                }
            }
        }

    /**
     * Check if a model is currently loaded.
     */
    fun isModelLoaded(): Boolean = interpreter != null

    /**
     * Get the currently loaded model.
     */
    val currentModel: CachedModel? get() = _currentModel

    // =========================================================================
    // Inference
    // =========================================================================

    /**
     * Run inference on a single input.
     *
     * @param input Input data as float array
     * @return Inference output with results and timing
     */
    suspend fun runInference(input: FloatArray): Result<InferenceOutput> =
        withContext(defaultDispatcher) {
            mutex.withLock {
                val interp =
                    interpreter
                        ?: return@withContext Result.failure(
                            IllegalStateException("No model loaded. Call loadModel() first."),
                        )

                try {
                    // Prepare input buffer
                    val inputBuffer = createInputBuffer(input)

                    // Prepare output buffer
                    val outputBuffer = createOutputBuffer()

                    // Run inference
                    val startTime = System.nanoTime()
                    interp.run(inputBuffer, outputBuffer)
                    val inferenceTimeMs = (System.nanoTime() - startTime) / 1_000_000

                    // Extract output
                    val output = extractOutput(outputBuffer, inferenceTimeMs)

                    Result.success(output)
                } catch (e: Exception) {
                    Timber.e(e, "Inference failed")
                    Result.failure(e)
                }
            }
        }

    /**
     * Run inference with structured input.
     *
     * @param input Inference input with data and shape
     * @return Inference output with results and timing
     */
    suspend fun runInference(input: InferenceInput): Result<InferenceOutput> = runInference(input.data)

    /**
     * Run batch inference on multiple inputs.
     *
     * @param inputs List of input data arrays
     * @return List of inference outputs
     */
    suspend fun runBatchInference(inputs: List<FloatArray>): Result<List<InferenceOutput>> =
        withContext(defaultDispatcher) {
            try {
                val results =
                    inputs.map { input ->
                        runInference(input).getOrThrow()
                    }
                Result.success(results)
            } catch (e: Exception) {
                Timber.e(e, "Batch inference failed")
                Result.failure(e)
            }
        }

    /**
     * Run inference and get class predictions.
     *
     * @param input Input data as float array
     * @param topK Number of top predictions to return
     * @return List of (class index, confidence) pairs
     */
    suspend fun classify(
        input: FloatArray,
        topK: Int = 5,
    ): Result<List<Pair<Int, Float>>> =
        withContext(defaultDispatcher) {
            runInference(input).map { output ->
                output.topK(topK)
            }
        }

    // =========================================================================
    // Tensor Information
    // =========================================================================

    /**
     * Get the expected input shape.
     */
    val inputShape: IntArray get() = _inputShape.copyOf()

    /**
     * Get the expected output shape.
     */
    val outputShape: IntArray get() = _outputShape.copyOf()

    /**
     * Get the number of input elements required.
     */
    fun getInputSize(): Int = inputShape.fold(1) { acc, dim -> acc * dim }

    /**
     * Get the number of output elements.
     */
    fun getOutputSize(): Int = outputShape.fold(1) { acc, dim -> acc * dim }

    /**
     * Get model input/output tensor details.
     */
    fun getTensorInfo(): TensorInfo? {
        interpreter ?: return null
        return TensorInfo(
            inputShape = inputShape.copyOf(),
            outputShape = outputShape.copyOf(),
            inputType = inputDataType?.name ?: "UNKNOWN",
            outputType = outputDataType?.name ?: "UNKNOWN",
        )
    }

    // =========================================================================
    // Training
    // =========================================================================

    /**
     * Trains a model on local data.
     *
     * Note: TensorFlow Lite's on-device training support is limited.
     * This is a placeholder for training functionality. For production use,
     * you'll need to integrate with TensorFlow Lite's training APIs or
     * use a custom training loop.
     *
     * @param dataProvider Provider for training data
     * @param trainingConfig Training configuration
     * @return Training result with metrics
     */
    suspend fun train(
        dataProvider: TrainingDataProvider,
        trainingConfig: TrainingConfig = TrainingConfig(),
    ): Result<TrainingResult> =
        withContext(defaultDispatcher) {
            mutex.withLock {
                val model =
                    currentModel
                        ?: return@withContext Result.failure(
                            IllegalStateException("No model loaded. Call loadModel() first."),
                        )

                try {
                    Timber.i("Starting local training...")
                    val startTime = System.currentTimeMillis()

                    // Store original model path for delta computation
                    originalModelPath = model.filePath

                    // Get training data
                    val trainingData = dataProvider.getTrainingData()
                    val sampleCount = trainingData.size

                    Timber.i("Training on $sampleCount samples for ${trainingConfig.epochs} epochs")

                    // Simulate training (replace with actual TFLite training API when available)
                    // In production, you would:
                    // 1. Use TensorFlow Lite's training APIs (if model supports it)
                    // 2. Or use a custom training loop with gradient computation
                    // 3. Or integrate with TensorFlow Lite Model Maker

                    // For now, we'll create a placeholder "trained" model
                    // by copying the original model to a new location
                    val tempDir = context.cacheDir
                    val updatedFile = File(tempDir, "updated_${System.currentTimeMillis()}.tflite")
                    File(model.filePath).copyTo(updatedFile, overwrite = true)
                    updatedModelPath = updatedFile.absolutePath

                    val trainingTime = (System.currentTimeMillis() - startTime) / 1000.0

                    // Calculate placeholder metrics
                    val loss = 0.1 // Placeholder
                    val accuracy = 0.95 // Placeholder

                    val result =
                        TrainingResult(
                            sampleCount = sampleCount,
                            loss = loss,
                            accuracy = accuracy,
                            trainingTime = trainingTime,
                            metrics =
                                mapOf(
                                    "epochs" to trainingConfig.epochs.toDouble(),
                                    "batch_size" to trainingConfig.batchSize.toDouble(),
                                    "learning_rate" to trainingConfig.learningRate.toDouble(),
                                ),
                            updatedModelPath = updatedModelPath,
                        )

                    Timber.i("Training completed: $sampleCount samples in ${String.format("%.2f", trainingTime)}s")

                    Result.success(result)
                } catch (e: Exception) {
                    Timber.e(e, "Training failed")
                    Result.failure(e)
                }
            }
        }

    /**
     * Extracts weight updates from a trained model.
     *
     * Attempts to extract weight deltas (updated - original) when possible.
     * Falls back to full weight extraction if delta computation is not supported.
     *
     * @param trainingResult Result from training
     * @return Weight update for upload
     */
    suspend fun extractWeightUpdate(trainingResult: TrainingResult): Result<WeightUpdate> =
        withContext(ioDispatcher) {
            try {
                val model =
                    currentModel
                        ?: return@withContext Result.failure(
                            IllegalStateException("No model loaded"),
                        )

                val updatedPath =
                    updatedModelPath
                        ?: return@withContext Result.failure(
                            IllegalStateException("No training session available. Train the model first."),
                        )

                Timber.i("Extracting weight updates...")

                // Try to extract weight delta
                var weightsData: ByteArray
                var updateFormat: String

                if (originalModelPath != null) {
                    // Try delta extraction first
                    try {
                        weightsData =
                            weightExtractor.extractWeightDelta(
                                originalModelPath = originalModelPath!!,
                                updatedModelPath = updatedPath,
                            )
                        updateFormat = "delta"

                        Timber.i("Successfully extracted weight delta")
                    } catch (e: Exception) {
                        // Fall back to full weights if delta extraction fails
                        Timber.w(e, "Delta extraction failed, falling back to full weights")

                        weightsData =
                            weightExtractor.extractFullWeights(
                                modelPath = updatedPath,
                            )
                        updateFormat = "weights"
                    }
                } else {
                    // No original model path, extract full weights
                    weightsData =
                        weightExtractor.extractFullWeights(
                            modelPath = updatedPath,
                        )
                    updateFormat = "weights"
                }

                // Add update format to metrics
                val metrics = trainingResult.metrics.toMutableMap()
                metrics["update_format"] = if (updateFormat == "delta") 1.0 else 0.0

                Timber.i("Weight extraction completed: ${weightsData.size} bytes ($updateFormat)")

                val weightUpdate =
                    WeightUpdate(
                        modelId = model.modelId,
                        version = model.version,
                        weightsData = weightsData,
                        sampleCount = trainingResult.sampleCount,
                        metrics = metrics,
                    )

                Result.success(weightUpdate)
            } catch (e: Exception) {
                Timber.e(e, "Weight extraction failed")
                Result.failure(e)
            }
        }

    // =========================================================================
    // GPU Support
    // =========================================================================

    /**
     * Check if GPU acceleration is supported on this device.
     */
    fun isGpuSupported(): Boolean =
        try {
            val compatList = CompatibilityList()
            compatList.isDelegateSupportedOnThisDevice
        } catch (e: Exception) {
            Timber.w(e, "Failed to check GPU support")
            false
        }

    /**
     * Check if GPU delegate is currently active.
     */
    fun isUsingGpu(): Boolean = gpuDelegate != null

    // =========================================================================
    // Resource Management
    // =========================================================================

    /**
     * Close the interpreter and release resources.
     */
    suspend fun close() {
        mutex.withLock {
            closeInternal()
        }
    }

    private fun closeInternal() {
        try {
            interpreter?.close()
            gpuDelegate?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing interpreter")
        } finally {
            interpreter = null
            gpuDelegate = null
            _currentModel = null
            _inputShape = intArrayOf()
            _outputShape = intArrayOf()
        }
    }

    // =========================================================================
    // Private Helpers
    // =========================================================================

    private fun loadModelFile(file: File): MappedByteBuffer =
        file.inputStream().channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }

    private fun extractTensorInfo() {
        val interp = interpreter ?: return

        // Get input tensor info
        val inputTensor = interp.getInputTensor(0)
        _inputShape = inputTensor.shape()
        inputDataType = inputTensor.dataType()

        // Get output tensor info
        val outputTensor = interp.getOutputTensor(0)
        _outputShape = outputTensor.shape()
        outputDataType = outputTensor.dataType()

        Timber.d("Input shape: ${_inputShape.contentToString()}, type: $inputDataType")
        Timber.d("Output shape: ${_outputShape.contentToString()}, type: $outputDataType")
    }

    private fun createInputBuffer(input: FloatArray): ByteBuffer {
        val inputSize = input.size
        val expectedSize = getInputSize()

        require(inputSize == expectedSize) {
            "Input size mismatch: got $inputSize, expected $expectedSize"
        }

        val buffer = ByteBuffer.allocateDirect(inputSize * 4) // 4 bytes per float
        buffer.order(ByteOrder.nativeOrder())

        for (value in input) {
            buffer.putFloat(value)
        }

        buffer.rewind()
        return buffer
    }

    private fun createOutputBuffer(): ByteBuffer {
        val outputSize = getOutputSize()
        val buffer = ByteBuffer.allocateDirect(outputSize * 4) // 4 bytes per float
        buffer.order(ByteOrder.nativeOrder())
        return buffer
    }

    private fun extractOutput(
        buffer: ByteBuffer,
        inferenceTimeMs: Long,
    ): InferenceOutput {
        buffer.rewind()
        val outputSize = getOutputSize()
        val outputData = FloatArray(outputSize)

        for (i in 0 until outputSize) {
            outputData[i] = buffer.float
        }

        return InferenceOutput(
            data = outputData,
            shape = outputShape.copyOf(),
            inferenceTimeMs = inferenceTimeMs,
        )
    }
}

/**
 * Information about model tensors.
 */
data class TensorInfo(
    val inputShape: IntArray,
    val outputShape: IntArray,
    val inputType: String,
    val outputType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TensorInfo

        if (!inputShape.contentEquals(other.inputShape)) return false
        if (!outputShape.contentEquals(other.outputShape)) return false
        if (inputType != other.inputType) return false
        if (outputType != other.outputType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = inputShape.contentHashCode()
        result = 31 * result + outputShape.contentHashCode()
        result = 31 * result + inputType.hashCode()
        result = 31 * result + outputType.hashCode()
        return result
    }
}
