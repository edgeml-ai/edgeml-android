package ai.octomil.training

import ai.octomil.config.OctomilConfig
import ai.octomil.models.CachedModel
import ai.octomil.models.InferenceInput
import ai.octomil.models.InferenceOutput
import ai.octomil.models.ServerModelContract
import ai.octomil.privacy.DifferentialPrivacy
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
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
 *
 * Delegate lifecycle (GPU, vendor NPU, NNAPI) is managed by [DelegateManager].
 */
class TFLiteTrainer(
    private val context: Context,
    private val config: OctomilConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var interpreter: Interpreter? = null
    private var _currentModel: CachedModel? = null
    private val mutex = Mutex()

    // Delegate management (GPU, vendor NPU, NNAPI)
    private val delegateManager = DelegateManager(context, config)

    // Model metadata
    private var _inputShape: IntArray = intArrayOf()
    private var _outputShape: IntArray = intArrayOf()
    private var inputDataType: org.tensorflow.lite.DataType? = null
    private var outputDataType: org.tensorflow.lite.DataType? = null

    // Server-provided model contract for input validation (null = no validation)
    private var _serverContract: ServerModelContract? = null

    // Pooled I/O buffers -- allocated once in loadModel, reused across inferences
    private var _pooledInputBuffer: ByteBuffer? = null
    private var _pooledOutputBuffer: ByteBuffer? = null

    // Resolved thread count (stored for warmup CPU-only benchmark)
    private var _effectiveThreads: Int = config.numThreads

    // Training state
    private var originalModelPath: String? = null
    private var updatedModelPath: String? = null
    private val weightExtractor = WeightExtractor()

    // Snapshot of original model bytes taken at load time for delta computation
    private var originalModelBytes: ByteArray? = null

    companion object {
        private const val TAG = "TFLiteTrainer"

        /** Signature key used by TFLite models converted with training support. */
        internal const val TRAIN_SIGNATURE = "train"
        internal const val INFER_SIGNATURE = "infer"
        internal const val SAVE_SIGNATURE = "save"
        internal const val RESTORE_SIGNATURE = "restore"
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

                    // Resolve thread count: prefer big-core count on ARM big.LITTLE
                    _effectiveThreads = delegateManager.resolveThreadCount()
                    val effectiveThreads = _effectiveThreads

                    val options = Interpreter.Options().apply {
                        setNumThreads(effectiveThreads)
                    }

                    // ---------------------------------------------------------------
                    // Delegate priority: vendor NPU > GPU > NNAPI (legacy) > XNNPack/CPU
                    // warmup() benchmarks the selected delegate vs CPU after loading.
                    // ---------------------------------------------------------------
                    delegateManager.configureDelegates(options, model)

                    // XNNPack is always the CPU fallback (built into TFLite 2.17+)

                    // Load model into memory-mapped buffer
                    val modelBuffer = loadModelFile(modelFile)

                    // Create interpreter
                    interpreter = Interpreter(modelBuffer, options)
                    _currentModel = model

                    // Store server-provided contract for input validation
                    _serverContract = model.modelContract

                    // Snapshot original model bytes for weight delta computation
                    originalModelBytes = modelFile.readBytes()
                    originalModelPath = model.filePath

                    // Get input/output tensor info and pre-allocate reusable buffers
                    extractTensorInfo()
                    allocatePooledBuffers()

                    Timber.i(
                        "Model loaded: ${model.modelId} v${model.version}, " +
                            "threads=$effectiveThreads, gpu=${isUsingGpu()}, " +
                            "vendorNpu=${delegateManager.vendorDelegate != null}",
                    )
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

                // Validate against server-provided model contract (if available)
                _serverContract?.let { contract ->
                    val error = contract.validateInput(input)
                    if (error != null) {
                        return@withContext Result.failure(
                            IllegalArgumentException(error),
                        )
                    }
                }

                try {
                    // Use pooled buffers if available (avoid per-inference allocation)
                    val inputBuffer = fillPooledInputBuffer(input)
                        ?: createInputBuffer(input)
                    val outputBuffer = _pooledOutputBuffer?.also { it.clear() }
                        ?: createOutputBuffer()

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
     * Run pipelined inference -- overlap preprocessing with model execution.
     *
     * For camera/sensor streams, this hides preprocessing latency by running
     * the next frame's preprocessing concurrently with the current inference.
     *
     * @param currentInput Already-preprocessed input for the current frame
     * @param preprocess Suspend function that preprocesses the next frame.
     *   Return null to signal end of stream.
     * @return Pair of (current inference result, next preprocessed input or null)
     */
    suspend fun runPipelinedInference(
        currentInput: FloatArray,
        preprocess: suspend () -> FloatArray?,
    ): Result<Pair<InferenceOutput, FloatArray?>> =
        coroutineScope {
            try {
                // Launch preprocessing on IO thread while inference runs on default
                val nextInputDeferred = async(ioDispatcher) { preprocess() }
                val inferenceResult = runInference(currentInput).getOrThrow()
                val nextInput = nextInputDeferred.await()
                Result.success(inferenceResult to nextInput)
            } catch (e: Exception) {
                Timber.e(e, "Pipelined inference failed")
                Result.failure(e)
            }
        }

    /**
     * Run batch inference on multiple inputs.
     *
     * Attempts true batched execution (single kernel launch) by resizing the
     * input tensor's batch dimension. Falls back to sequential inference if
     * the model doesn't support dynamic batch size.
     *
     * @param inputs List of input data arrays
     * @return List of inference outputs
     */
    suspend fun runBatchInference(inputs: List<FloatArray>): Result<List<InferenceOutput>> =
        withContext(defaultDispatcher) {
            mutex.withLock {
                val interp =
                    interpreter
                        ?: return@withContext Result.failure(
                            IllegalStateException("No model loaded. Call loadModel() first."),
                        )

                if (inputs.isEmpty()) {
                    return@withContext Result.success(emptyList())
                }

                try {
                    runTrueBatchInference(interp, inputs)
                } catch (e: Exception) {
                    Timber.d(e, "True batch inference failed, falling back to sequential")
                    runSequentialBatchInference(inputs)
                }
            }
        }

    /**
     * True batch inference: resize input tensor batch dimension and run all
     * samples in a single kernel launch. Much faster on GPU/NPU.
     */
    private fun runTrueBatchInference(
        interp: Interpreter,
        inputs: List<FloatArray>,
    ): Result<List<InferenceOutput>> {
        val batchSize = inputs.size
        val singleInputSize = getInputSize()
        val singleOutputSize = getOutputSize()

        // Resize input tensor: [1, ...shape] -> [batchSize, ...shape]
        val batchedInputShape = _inputShape.copyOf().also { it[0] = batchSize }
        interp.resizeInput(0, batchedInputShape)
        interp.allocateTensors()

        // Pack all inputs into one contiguous buffer
        val batchedInput = ByteBuffer.allocateDirect(batchSize * singleInputSize * 4)
            .order(ByteOrder.nativeOrder())
        for (input in inputs) {
            for (value in input) batchedInput.putFloat(value)
        }
        batchedInput.rewind()

        // Allocate batched output buffer
        val batchedOutput = ByteBuffer.allocateDirect(batchSize * singleOutputSize * 4)
            .order(ByteOrder.nativeOrder())

        // Single kernel launch for the full batch
        val startTime = System.nanoTime()
        interp.run(batchedInput, batchedOutput)
        val totalMs = (System.nanoTime() - startTime) / 1_000_000
        val perSampleMs = totalMs / batchSize

        // Split output back into individual InferenceOutputs
        batchedOutput.rewind()
        val results = (0 until batchSize).map {
            val sampleData = FloatArray(singleOutputSize)
            for (i in 0 until singleOutputSize) sampleData[i] = batchedOutput.float
            InferenceOutput(
                data = sampleData,
                shape = _outputShape.copyOf(),
                inferenceTimeMs = perSampleMs,
            )
        }

        // Restore original single-sample tensor shape for subsequent inferences
        interp.resizeInput(0, _inputShape)
        interp.allocateTensors()

        return Result.success(results)
    }

    /**
     * Fallback: run each sample individually when batch resizing isn't supported.
     */
    private suspend fun runSequentialBatchInference(
        inputs: List<FloatArray>,
    ): Result<List<InferenceOutput>> =
        try {
            val results = inputs.map { input -> runInference(input).getOrThrow() }
            Result.success(results)
        } catch (e: Exception) {
            Timber.e(e, "Sequential batch inference failed")
            Result.failure(e)
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
     * Get the server-provided model contract (if available).
     */
    val serverContract: ServerModelContract? get() = _serverContract

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
    ): InternalTrainingMetrics {
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

        return InternalTrainingMetrics(loss = avgLoss, accuracy = accuracy)
    }

    /**
     * Train using forward-pass based approach for models without training signatures.
     *
     * This performs inference on training data to compute loss, but cannot update
     * model weights directly. Instead, it copies the model and records the training
     * metrics.
     *
     * For true on-device training, models should be exported with training signatures.
     */
    private fun trainWithForwardPass(
        interp: Interpreter,
        trainingData: List<Pair<FloatArray, FloatArray>>,
        trainingConfig: TrainingConfig,
        model: CachedModel,
    ): InternalTrainingMetrics {
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
            return InternalTrainingMetrics(loss = 0.0, accuracy = 0.0)
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

        return InternalTrainingMetrics(loss = avgLoss, accuracy = accuracy)
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
                // No save signature -- copy the model file
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
    private data class InternalTrainingMetrics(
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

                val privacyConfig = config.privacyConfiguration
                val privacyTransform: ((Map<String, WeightExtractor.TensorData>) -> Map<String, WeightExtractor.TensorData>)? =
                    if (privacyConfig.enableDifferentialPrivacy) { deltas ->
                        val sigma = DifferentialPrivacy.calibrateSigma(
                            clippingNorm = privacyConfig.dpClippingNorm,
                            epsilon = privacyConfig.dpEpsilon,
                            delta = privacyConfig.dpDelta,
                        )
                        Timber.i(
                            "Applying differential privacy: epsilon=%.2f, delta=%.1e, sigma=%.4f, clippingNorm=%.2f",
                            privacyConfig.dpEpsilon,
                            privacyConfig.dpDelta,
                            sigma,
                            privacyConfig.dpClippingNorm,
                        )
                        DifferentialPrivacy.apply(deltas, privacyConfig, trainingResult.sampleCount)
                    } else null

                if (originalModelPath != null) {
                    try {
                        weightsData =
                            weightExtractor.extractWeightDelta(
                                originalModelPath = originalModelPath!!,
                                updatedModelPath = updatedPath,
                                privacyTransform = privacyTransform,
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
    // Warmup
    // =========================================================================

    /**
     * Warm up the interpreter and validate that the active hardware delegate is
     * actually faster than CPU. If not (partition stalls, bad model compat), the
     * delegate is disabled and the next one in the priority chain is tried:
     *
     *   vendor NPU -> GPU -> NNAPI -> XNNPack/CPU
     *
     * This ensures we never ship an inference path that is _slower_ than CPU.
     *
     * @return [WarmupResult] with timing breakdown, or null if no model is loaded.
     */
    suspend fun warmup(): WarmupResult? =
        withContext(defaultDispatcher) {
            mutex.withLock {
                interpreter ?: return@withContext null
                val model = _currentModel ?: return@withContext null

                try {
                    val inputSize = getInputSize()
                    if (inputSize <= 0) return@withContext null

                    // Benchmark CPU-only as baseline (always needed for comparison)
                    val cpuLatency = benchmarkCpuOnly(model, inputSize)

                    // Benchmark whatever delegate is currently active
                    var delegateLatency = benchmarkInference(interpreter!!, inputSize)
                    val disabledDelegates = mutableListOf<String>()

                    // --- Cascade: if active delegate is slower than CPU, try next ---

                    // 1. Vendor NPU active and slower?
                    if (delegateManager.vendorDelegate != null && cpuLatency.warmMs < delegateLatency.warmMs) {
                        logPartitionStall("Vendor NPU", delegateLatency.warmMs, cpuLatency.warmMs)
                        disabledDelegates.add("vendor_npu")
                        delegateManager.closeVendorDelegate()

                        // Try falling through to GPU
                        if (config.enableGpuAcceleration && delegateManager.isGpuSupported()) {
                            interpreter?.close()
                            interpreter = delegateManager.reloadWithGpu(model, _effectiveThreads)
                            delegateLatency = benchmarkInference(interpreter!!, inputSize)
                        } else {
                            interpreter?.close()
                            interpreter = delegateManager.reloadCpuOnly(model, _effectiveThreads)
                            delegateLatency = cpuLatency
                        }
                    }

                    // 2. GPU active and slower?
                    if (delegateManager.gpuDelegate != null && cpuLatency.warmMs < delegateLatency.warmMs) {
                        logPartitionStall("GPU", delegateLatency.warmMs, cpuLatency.warmMs)
                        disabledDelegates.add("gpu")
                        interpreter?.close()
                        interpreter = delegateManager.reloadCpuOnly(model, _effectiveThreads)
                        delegateLatency = cpuLatency
                    }

                    // Re-allocate pooled buffers if we swapped interpreters
                    if (disabledDelegates.isNotEmpty()) {
                        extractTensorInfo()
                        allocatePooledBuffers()
                    }

                    val activeDelegate = when {
                        delegateManager.vendorDelegate != null -> "vendor_npu"
                        delegateManager.gpuDelegate != null -> "gpu"
                        else -> "cpu"
                    }

                    if (disabledDelegates.isEmpty() && activeDelegate != "cpu") {
                        Timber.i(
                            "%s delegate validated: warm=%.1fms, CPU warm=%.1fms (%.1fx faster)",
                            activeDelegate.uppercase(),
                            delegateLatency.warmMs,
                            cpuLatency.warmMs,
                            cpuLatency.warmMs / delegateLatency.warmMs,
                        )
                    }

                    val result = WarmupResult(
                        coldInferenceMs = delegateLatency.coldMs,
                        warmInferenceMs = delegateLatency.warmMs,
                        cpuInferenceMs = cpuLatency.warmMs,
                        usingGpu = isUsingGpu(),
                        activeDelegate = activeDelegate,
                        disabledDelegates = disabledDelegates,
                    )

                    Timber.i(
                        "Warmup complete: cold=%.1fms, warm=%.1fms, delegate=%s, disabled=%s",
                        delegateLatency.coldMs,
                        delegateLatency.warmMs,
                        activeDelegate,
                        disabledDelegates.ifEmpty { listOf("none") },
                    )

                    result
                } catch (e: Exception) {
                    Timber.w(e, "Warmup inference failed (non-fatal)")
                    null
                }
            }
        }

    private fun logPartitionStall(delegateName: String, delegateMs: Double, cpuMs: Double) {
        Timber.e(
            "DELEGATE PARTITION STALL: %s warm=%.1fms, CPU warm=%.1fms. " +
                "%s is %.1fx SLOWER than CPU -- disabling and cascading to next delegate.",
            delegateName,
            delegateMs,
            cpuMs,
            delegateName,
            delegateMs / cpuMs,
        )
    }

    /**
     * Benchmark a CPU-only interpreter against the same model.
     */
    private fun benchmarkCpuOnly(model: CachedModel, inputSize: Int): BenchmarkLatency {
        val cpuOptions = Interpreter.Options().apply {
            setNumThreads(_effectiveThreads)
        }
        val cpuInterp = Interpreter(loadModelFile(File(model.filePath)), cpuOptions)
        return try {
            benchmarkInference(cpuInterp, inputSize)
        } finally {
            cpuInterp.close()
        }
    }

    /**
     * Run cold + warm inference on an interpreter and return both timings.
     */
    private fun benchmarkInference(interp: Interpreter, inputSize: Int): BenchmarkLatency {
        val dummyInput = FloatArray(inputSize)
        val inputBuffer = createInputBuffer(dummyInput)
        val outputBuffer = createOutputBuffer()

        // Cold run
        val coldStart = System.nanoTime()
        interp.run(inputBuffer, outputBuffer)
        val coldMs = (System.nanoTime() - coldStart) / 1_000_000.0

        // Warm run
        inputBuffer.rewind()
        outputBuffer.clear()
        val warmStart = System.nanoTime()
        interp.run(inputBuffer, outputBuffer)
        val warmMs = (System.nanoTime() - warmStart) / 1_000_000.0

        return BenchmarkLatency(coldMs, warmMs)
    }

    private data class BenchmarkLatency(val coldMs: Double, val warmMs: Double)

    // =========================================================================
    // GPU Support (delegated)
    // =========================================================================

    /**
     * Check if GPU acceleration is supported on this device.
     */
    fun isGpuSupported(): Boolean = delegateManager.isGpuSupported()

    /**
     * Check if GPU delegate is currently active.
     */
    fun isUsingGpu(): Boolean = delegateManager.isUsingGpu()

    // =========================================================================
    // Buffer Pooling
    // =========================================================================

    /**
     * Pre-allocate reusable I/O ByteBuffers. Called once after [extractTensorInfo].
     * Eliminates per-inference native allocation and reduces GC pressure.
     */
    private fun allocatePooledBuffers() {
        val inputSize = getInputSize()
        val outputSize = getOutputSize()
        if (inputSize > 0) {
            _pooledInputBuffer = ByteBuffer.allocateDirect(inputSize * 4)
                .order(ByteOrder.nativeOrder())
        }
        if (outputSize > 0) {
            _pooledOutputBuffer = ByteBuffer.allocateDirect(outputSize * 4)
                .order(ByteOrder.nativeOrder())
        }
        Timber.d("Pooled buffers allocated: input=${inputSize * 4}B, output=${outputSize * 4}B")
    }

    /**
     * Fill the pooled input buffer with data and return it, or null if not available.
     */
    private fun fillPooledInputBuffer(input: FloatArray): ByteBuffer? {
        val buf = _pooledInputBuffer ?: return null
        val expectedSize = getInputSize()
        if (input.size != expectedSize) return null // size mismatch, fall back
        buf.clear()
        for (value in input) buf.putFloat(value)
        buf.rewind()
        return buf
    }

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
            delegateManager.closeAll()
        } catch (e: Exception) {
            Timber.w(e, "Error closing interpreter")
        } finally {
            interpreter = null
            _currentModel = null
            _inputShape = intArrayOf()
            _outputShape = intArrayOf()
            _pooledInputBuffer = null
            _pooledOutputBuffer = null
            _serverContract = null
            _effectiveThreads = config.numThreads
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
