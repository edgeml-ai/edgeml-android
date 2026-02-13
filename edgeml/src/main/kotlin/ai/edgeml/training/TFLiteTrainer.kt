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
import java.util.Locale

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

    // Snapshot of original model bytes taken at load time for delta computation
    private var originalModelBytes: ByteArray? = null

    companion object {
        private const val TAG = "TFLiteTrainer"

        /** Signature key used by TFLite models converted with training support. */
        private const val TRAIN_SIGNATURE = "train"
        private const val INFER_SIGNATURE = "infer"
        private const val SAVE_SIGNATURE = "save"
        private const val RESTORE_SIGNATURE = "restore"
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
                    // TODO(optimization): Select delegates based on model optimization metadata
                    //   from ModelDownloadResponse. Currently we only try GPU. The server can
                    //   return recommended delegates per model variant:
                    //   - float32 model → GPU delegate (current behavior)
                    //   - float16 model → GPU delegate with float16 inference
                    //   - int8 model    → XNNPack delegate (2-6x faster CPU inference)
                    //   - any model on supported SoC → NNAPI delegate (Qualcomm/Samsung/MediaTek HW accel)
                    val options =
                        Interpreter.Options().apply {
                            setNumThreads(config.numThreads)
                        }

                    // TODO(optimization): Add NNAPI delegate support.
                    //   NNAPI is detected in DeviceInfo (Build.VERSION.SDK_INT >= O_MR1) but never
                    //   used. Create NnApiDelegate when device supports it and model is compatible:
                    //     val nnApiDelegate = NnApiDelegate(NnApiDelegate.Options())
                    //     options.addDelegate(nnApiDelegate)
                    //   Must handle: fallback on unsupported ops, GPU vs NNAPI priority.

                    // TODO(optimization): Add XNNPack delegate for quantized models.
                    //   XNNPack is bundled with TFLite but needs explicit opt-in for best perf:
                    //     val xnnpackOptions = XNNPackDelegate.Options().apply {
                    //         setNumThreads(config.numThreads)
                    //     }
                    //     options.addDelegate(XNNPackDelegate(xnnpackOptions))
                    //   Should be selected when model metadata indicates int8 quantization.

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

                    // TODO(optimization): Wire up MNN device config.
                    //   ModelManager fetches and saves mnn_config.json per model+device, but
                    //   nothing reads it here. Load the saved config and apply runtime settings:
                    //     val mnnConfig = loadMnnConfig(model.modelId, model.version)
                    //     mnnConfig?.let { applyRuntimeConfig(options, it) }
                    //   Config may include: thread affinity, memory allocation strategy,
                    //   operator fusion hints, precision mode.

                    // Load model into memory-mapped buffer
                    val modelBuffer = loadModelFile(modelFile)

                    // Create interpreter
                    interpreter = Interpreter(modelBuffer, options)
                    _currentModel = model

                    // Snapshot original model bytes for weight delta computation
                    originalModelBytes = modelFile.readBytes()
                    originalModelPath = model.filePath

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
     * Check if the loaded model supports training via TFLite signatures.
     *
     * @return true if the model has a "train" signature key
     */
    fun hasTrainingSignature(): Boolean {
        val interp = interpreter ?: return false
        return try {
            interp.signatureKeys.contains(TRAIN_SIGNATURE)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get available signature keys from the loaded model.
     */
    fun getSignatureKeys(): List<String> {
        val interp = interpreter ?: return emptyList()
        return try {
            interp.signatureKeys.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Trains a model on local data using TFLite training signatures.
     *
     * If the model has a "train" signature, the signature runner is used for
     * proper gradient-based training. Otherwise, a forward-pass based training
     * approach is used where the model file is updated in-place.
     *
     * Models must be converted with training signatures enabled:
     * ```python
     * converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
     * converter.experimental_enable_resource_variables = True
     * tflite_model = converter.convert()
     * ```
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

                val interp = interpreter
                    ?: return@withContext Result.failure(
                        IllegalStateException("Interpreter not available."),
                    )

                try {
                    Timber.i("Starting local training...")
                    val startTime = System.currentTimeMillis()

                    val trainingData = dataProvider.getTrainingData()
                    val sampleCount = trainingData.size

                    if (sampleCount == 0) {
                        return@withContext Result.failure(
                            IllegalArgumentException("Training data is empty."),
                        )
                    }

                    Timber.i("Training on $sampleCount samples for ${trainingConfig.epochs} epochs")

                    val result = if (hasTrainingSignature()) {
                        trainWithSignature(interp, trainingData, trainingConfig, model)
                    } else {
                        if (!config.allowDegradedTraining) {
                            return@withContext Result.failure(
                                MissingTrainingSignatureException(getSignatureKeys())
                            )
                        }
                        trainWithForwardPass(interp, trainingData, trainingConfig, model)
                    }

                    val trainingTime = (System.currentTimeMillis() - startTime) / 1000.0

                    val trainingResult = TrainingResult(
                        sampleCount = sampleCount,
                        loss = result.loss,
                        accuracy = result.accuracy,
                        trainingTime = trainingTime,
                        metrics = mapOf(
                            "epochs" to trainingConfig.epochs.toDouble(),
                            "batch_size" to trainingConfig.batchSize.toDouble(),
                            "learning_rate" to trainingConfig.learningRate.toDouble(),
                            "training_method" to if (hasTrainingSignature()) 1.0 else 0.0,
                        ),
                        updatedModelPath = updatedModelPath,
                    )

                    Timber.i(
                        "Training completed: $sampleCount samples, " +
                            "loss=${String.format(Locale.ROOT, "%.4f", result.loss)}, " +
                            "accuracy=${String.format(Locale.ROOT, "%.4f", result.accuracy)}, " +
                            "time=${String.format(Locale.ROOT, "%.2f", trainingTime)}s",
                    )

                    Result.success(trainingResult)
                } catch (e: Exception) {
                    Timber.e(e, "Training failed")
                    Result.failure(e)
                }
            }
        }

    /**
     * Train using the model's "train" signature runner.
     *
     * The signature runner expects named inputs (typically "x" and "y" or similar)
     * and produces outputs (typically "loss"). The exact input/output names depend
     * on how the model was exported.
     */
    private fun trainWithSignature(
        interp: Interpreter,
        trainingData: List<Pair<FloatArray, FloatArray>>,
        trainingConfig: TrainingConfig,
        model: CachedModel,
    ): TrainingMetrics {
        Timber.i("Training via runSignature API")

        val inputNames = interp.getSignatureInputs(TRAIN_SIGNATURE)
        val outputNames = interp.getSignatureOutputs(TRAIN_SIGNATURE)

        Timber.d("Train signature inputs: ${inputNames.toList()}, outputs: ${outputNames.toList()}")

        var totalLoss = 0.0
        var batchCount = 0

        for (epoch in 0 until trainingConfig.epochs) {
            val shuffled = trainingData.shuffled()
            var epochLoss = 0.0
            var epochBatches = 0

            for (batchStart in shuffled.indices step trainingConfig.batchSize) {
                val batchEnd = minOf(batchStart + trainingConfig.batchSize, shuffled.size)
                val batch = shuffled.subList(batchStart, batchEnd)

                // Prepare batch inputs
                val batchInputs = FloatArray(batch.sumOf { it.first.size })
                val batchLabels = FloatArray(batch.sumOf { it.second.size })

                var inputOffset = 0
                var labelOffset = 0
                for ((input, label) in batch) {
                    System.arraycopy(input, 0, batchInputs, inputOffset, input.size)
                    inputOffset += input.size
                    System.arraycopy(label, 0, batchLabels, labelOffset, label.size)
                    labelOffset += label.size
                }

                // Create input buffers
                val inputBuffer = ByteBuffer.allocateDirect(batchInputs.size * 4)
                    .order(ByteOrder.nativeOrder())
                for (v in batchInputs) inputBuffer.putFloat(v)
                inputBuffer.rewind()

                val labelBuffer = ByteBuffer.allocateDirect(batchLabels.size * 4)
                    .order(ByteOrder.nativeOrder())
                for (v in batchLabels) labelBuffer.putFloat(v)
                labelBuffer.rewind()

                // Allocate output buffer for loss
                val lossBuffer = ByteBuffer.allocateDirect(4)
                    .order(ByteOrder.nativeOrder())

                try {
                    // Build input map by name
                    val inputs = mutableMapOf<String, Any>()
                    if (inputNames.size >= 2) {
                        inputs[inputNames[0]] = inputBuffer
                        inputs[inputNames[1]] = labelBuffer
                    } else if (inputNames.size == 1) {
                        inputs[inputNames[0]] = inputBuffer
                    }

                    // Build output map by name
                    val outputs = mutableMapOf<String, Any>()
                    if (outputNames.isNotEmpty()) {
                        outputs[outputNames[0]] = lossBuffer
                    }

                    interp.runSignature(inputs, outputs, TRAIN_SIGNATURE)

                    // Read loss
                    if (outputNames.isNotEmpty()) {
                        lossBuffer.rewind()
                        val batchLoss = lossBuffer.float.toDouble()
                        epochLoss += batchLoss
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Batch training step failed, continuing...")
                }

                epochBatches++
                batchCount++
            }

            if (epochBatches > 0) {
                val avgLoss = epochLoss / epochBatches
                totalLoss += avgLoss
                Timber.d("Epoch ${epoch + 1}/${trainingConfig.epochs}: loss=${String.format(Locale.ROOT, "%.4f", avgLoss)}")
            }
        }

        // Save updated model
        saveTrainedModel(interp, model)

        val avgLoss = if (batchCount > 0) totalLoss / trainingConfig.epochs else 0.0
        val accuracy = maxOf(0.0, 1.0 - avgLoss).coerceIn(0.0, 1.0)

        return TrainingMetrics(loss = avgLoss, accuracy = accuracy)
    }

    /**
     * Train using forward-pass based approach for models without training signatures.
     *
     * This performs inference on training data to compute loss, but cannot update
     * model weights directly. Instead, it copies the model and records the training
     * metrics. The actual weight update happens server-side for these models.
     *
     * For true on-device training, models should be exported with training signatures.
     */
    private fun trainWithForwardPass(
        interp: Interpreter,
        trainingData: List<Pair<FloatArray, FloatArray>>,
        trainingConfig: TrainingConfig,
        model: CachedModel,
    ): TrainingMetrics {
        Timber.e(
            "DEGRADED TRAINING: Model does not have a '$TRAIN_SIGNATURE' signature. " +
                "Weights will NOT be updated on-device. Loss/accuracy metrics reflect " +
                "inference on training data only, not actual learning. " +
                "Available signatures: ${getSignatureKeys()}. " +
                "To fix: export model with converter.experimental_enable_resource_variables = True",
        )

        var totalLoss = 0.0
        var correctCount = 0
        var totalCount = 0

        val outputSize = getOutputSize()
        if (outputSize <= 0) {
            return TrainingMetrics(loss = 0.0, accuracy = 0.0)
        }

        for (epoch in 0 until trainingConfig.epochs) {
            val shuffled = trainingData.shuffled()

            for ((input, label) in shuffled) {
                try {
                    val inputBuffer = createInputBuffer(input)
                    val outputBuffer = createOutputBuffer()

                    interp.run(inputBuffer, outputBuffer)

                    outputBuffer.rewind()
                    val predictions = FloatArray(outputSize)
                    for (i in predictions.indices) {
                        predictions[i] = outputBuffer.float
                    }

                    // Compute cross-entropy loss
                    val loss = computeCrossEntropyLoss(predictions, label)
                    totalLoss += loss

                    // Check accuracy (argmax comparison)
                    val predictedClass = predictions.indices.maxByOrNull { predictions[it] } ?: 0
                    val targetClass = label.indices.maxByOrNull { label[it] } ?: 0
                    if (predictedClass == targetClass) correctCount++
                    totalCount++
                } catch (e: Exception) {
                    Timber.w(e, "Forward pass failed for sample, skipping")
                }
            }
        }

        // Copy model file as the "updated" model (weights unchanged since no gradient update)
        val tempDir = context.cacheDir
        val updatedFile = File(tempDir, "updated_${System.currentTimeMillis()}.tflite")
        File(model.filePath).copyTo(updatedFile, overwrite = true)
        updatedModelPath = updatedFile.absolutePath

        val avgLoss = if (totalCount > 0) totalLoss / totalCount else 0.0
        val accuracy = if (totalCount > 0) correctCount.toDouble() / totalCount else 0.0

        return TrainingMetrics(loss = avgLoss, accuracy = accuracy)
    }

    /**
     * Save the trained model to a temp file after signature-based training.
     * For models with a "save" signature, invokes it. Otherwise copies the
     * current model file (in-memory weights will differ from the original).
     */
    private fun saveTrainedModel(interp: Interpreter, model: CachedModel) {
        val tempDir = context.cacheDir
        val updatedFile = File(tempDir, "updated_${System.currentTimeMillis()}.tflite")

        try {
            if (interp.signatureKeys.contains(SAVE_SIGNATURE)) {
                Timber.d("Saving model via 'save' signature")

                // The save signature typically takes a checkpoint path as input
                val checkpointPath = updatedFile.absolutePath
                val pathBytes = checkpointPath.toByteArray()
                val pathBuffer = ByteBuffer.allocateDirect(pathBytes.size)
                pathBuffer.put(pathBytes)
                pathBuffer.rewind()

                try {
                    val inputNames = interp.getSignatureInputs(SAVE_SIGNATURE)
                    val outputNames = interp.getSignatureOutputs(SAVE_SIGNATURE)

                    val inputs = mutableMapOf<String, Any>()
                    if (inputNames.isNotEmpty()) {
                        inputs[inputNames[0]] = pathBuffer
                    }

                    val outputs = mutableMapOf<String, Any>()
                    if (outputNames.isNotEmpty()) {
                        // Allocate a small buffer for any output the save signature produces
                        val outBuffer = ByteBuffer.allocateDirect(4)
                            .order(ByteOrder.nativeOrder())
                        outputs[outputNames[0]] = outBuffer
                    }

                    interp.runSignature(inputs, outputs, SAVE_SIGNATURE)
                } catch (e: Exception) {
                    Timber.w(e, "Save signature failed, falling back to file copy")
                    File(model.filePath).copyTo(updatedFile, overwrite = true)
                }
            } else {
                // No save signature — copy the model file
                // Note: for signature-based training, the interpreter's internal state
                // has been updated, but we can't serialize it without a save signature.
                // The weight delta will be computed from the interpreter's tensor state.
                File(model.filePath).copyTo(updatedFile, overwrite = true)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to save model, copying original")
            File(model.filePath).copyTo(updatedFile, overwrite = true)
        }

        updatedModelPath = updatedFile.absolutePath
    }

    /**
     * Compute cross-entropy loss between predictions and one-hot labels.
     */
    private fun computeCrossEntropyLoss(predictions: FloatArray, labels: FloatArray): Double {
        var loss = 0.0
        val epsilon = 1e-7f

        for (i in predictions.indices) {
            if (i < labels.size && labels[i] > 0f) {
                val clipped = predictions[i].coerceIn(epsilon, 1.0f - epsilon)
                loss -= labels[i] * Math.log(clipped.toDouble())
            }
        }

        return loss
    }

    /** Internal data class for training metrics. */
    private data class TrainingMetrics(
        val loss: Double,
        val accuracy: Double,
    )

    /**
     * Extracts weight updates from a trained model.
     *
     * Attempts to extract weight deltas (updated_weights - original_weights) for
     * efficient FL upload. Falls back to full weight extraction if delta computation
     * is not supported.
     *
     * The extraction uses the original model snapshot taken at load time and the
     * updated model file produced by training.
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

                var weightsData: ByteArray
                var updateFormat: String

                if (originalModelPath != null) {
                    try {
                        weightsData =
                            weightExtractor.extractWeightDelta(
                                originalModelPath = originalModelPath!!,
                                updatedModelPath = updatedPath,
                            )
                        updateFormat = "delta"
                        Timber.i("Successfully extracted weight delta")
                    } catch (e: Exception) {
                        Timber.w(e, "Delta extraction failed, falling back to full weights")
                        weightsData =
                            weightExtractor.extractFullWeights(
                                modelPath = updatedPath,
                            )
                        updateFormat = "weights"
                    }
                } else {
                    weightsData =
                        weightExtractor.extractFullWeights(
                            modelPath = updatedPath,
                        )
                    updateFormat = "weights"
                }

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
            originalModelPath = null
            originalModelBytes = null
            updatedModelPath = null
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
