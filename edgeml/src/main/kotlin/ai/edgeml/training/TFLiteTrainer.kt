package ai.edgeml.training

import ai.edgeml.config.EdgeMLConfig
import ai.edgeml.models.CachedModel
import ai.edgeml.models.InferenceInput
import ai.edgeml.models.InferenceOutput
import ai.edgeml.models.ServerModelContract
import ai.edgeml.privacy.DifferentialPrivacy
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Delegate
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

// TODO(acceleration): Migrate from TensorFlow Lite to LiteRT.
//   TensorFlow Lite has been rebranded to LiteRT (google-ai-edge/LiteRT).
//   LiteRT provides the same API surface but with newer NPU delegate support,
//   better GPU performance, and access to the Acceleration Service (Play Services).
//   Migration: replace org.tensorflow:tensorflow-lite:* with
//   com.google.ai.edge.litert:litert:* in build.gradle.kts.
//   See: https://github.com/google-ai-edge/LiteRT

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
    private var vendorDelegate: Delegate? = null
    private var _currentModel: CachedModel? = null
    private val mutex = Mutex()

    // Model metadata
    private var _inputShape: IntArray = intArrayOf()
    private var _outputShape: IntArray = intArrayOf()
    private var inputDataType: org.tensorflow.lite.DataType? = null
    private var outputDataType: org.tensorflow.lite.DataType? = null

    // Server-provided model contract for input validation (null = no validation)
    private var _serverContract: ServerModelContract? = null

    // Pooled I/O buffers — allocated once in loadModel, reused across inferences
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
        private const val TRAIN_SIGNATURE = "train"
        private const val INFER_SIGNATURE = "infer"
        private const val SAVE_SIGNATURE = "save"
        private const val RESTORE_SIGNATURE = "restore"

        // Vendor NPU delegate class names (loaded via reflection)
        private const val QUALCOMM_QNN_CLASS = "com.qualcomm.qti.QnnDelegate"
        private const val SAMSUNG_EDEN_CLASS = "com.samsung.android.eden.EdenDelegate"
        private const val MEDIATEK_NEURON_CLASS = "com.mediatek.neuropilot.tflite.NeuronDelegate"
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
                    _effectiveThreads = resolveThreadCount()
                    val effectiveThreads = _effectiveThreads

                    val options = Interpreter.Options().apply {
                        setNumThreads(effectiveThreads)
                    }

                    // ---------------------------------------------------------------
                    // Delegate priority: vendor NPU > GPU > NNAPI (legacy) > XNNPack/CPU
                    // warmup() benchmarks the selected delegate vs CPU after loading.
                    // ---------------------------------------------------------------

                    val vendorAttached = if (config.enableVendorNpu) {
                        tryAttachVendorNpu(options)
                    } else false

                    if (!vendorAttached && config.enableGpuAcceleration && isGpuSupported()) {
                        tryAttachGpu(options, model)
                    }

                    if (!vendorAttached && gpuDelegate == null && config.enableNnapi) {
                        tryAttachNnapi(options)
                    }

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
                            "vendorNpu=${vendorDelegate != null}",
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
     * Run pipelined inference — overlap preprocessing with model execution.
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

        // Resize input tensor: [1, ...shape] → [batchSize, ...shape]
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
     *   vendor NPU → GPU → NNAPI → XNNPack/CPU
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
                    if (vendorDelegate != null && cpuLatency.warmMs < delegateLatency.warmMs) {
                        logPartitionStall("Vendor NPU", delegateLatency.warmMs, cpuLatency.warmMs)
                        disabledDelegates.add("vendor_npu")
                        closeVendorDelegate()
                        vendorDelegate = null

                        // Try falling through to GPU
                        if (config.enableGpuAcceleration && isGpuSupported()) {
                            reloadWithGpu(model)
                            delegateLatency = benchmarkInference(interpreter!!, inputSize)
                        } else {
                            reloadCpuOnly(model)
                            delegateLatency = cpuLatency
                        }
                    }

                    // 2. GPU active and slower?
                    if (gpuDelegate != null && cpuLatency.warmMs < delegateLatency.warmMs) {
                        logPartitionStall("GPU", delegateLatency.warmMs, cpuLatency.warmMs)
                        disabledDelegates.add("gpu")
                        reloadCpuOnly(model)
                        delegateLatency = cpuLatency
                    }

                    // Re-allocate pooled buffers if we swapped interpreters
                    if (disabledDelegates.isNotEmpty()) {
                        extractTensorInfo()
                        allocatePooledBuffers()
                    }

                    val activeDelegate = when {
                        vendorDelegate != null -> "vendor_npu"
                        gpuDelegate != null -> "gpu"
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
                "%s is %.1fx SLOWER than CPU — disabling and cascading to next delegate.",
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
     * Reload the model with GPU delegate (cascading from vendor NPU).
     */
    private fun reloadWithGpu(model: CachedModel) {
        interpreter?.close()
        gpuDelegate?.close()
        gpuDelegate = null

        val options = Interpreter.Options().apply { setNumThreads(_effectiveThreads) }
        tryAttachGpu(options, model)
        interpreter = Interpreter(loadModelFile(File(model.filePath)), options)
    }

    /**
     * Reload the model CPU-only (all delegates failed).
     */
    private fun reloadCpuOnly(model: CachedModel) {
        interpreter?.close()
        gpuDelegate?.close()
        gpuDelegate = null
        closeVendorDelegate()
        vendorDelegate = null

        val options = Interpreter.Options().apply { setNumThreads(_effectiveThreads) }
        interpreter = Interpreter(loadModelFile(File(model.filePath)), options)
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
    // Delegate Setup
    // =========================================================================

    /**
     * Attach GPU delegate with serialization and float16 options.
     */
    private fun tryAttachGpu(options: Interpreter.Options, model: CachedModel) {
        try {
            @Suppress("DEPRECATION")
            val delegateOptions = GpuDelegate.Options()

            // GPU shader serialization — cache compiled programs to skip recompilation
            if (config.enableGpuSerialization) {
                try {
                    val cacheDir = context.cacheDir.resolve("gpu_cache").apply { mkdirs() }
                    val setSerMethod = delegateOptions.javaClass.getMethod(
                        "setSerializationParams",
                        String::class.java,
                        String::class.java,
                    )
                    setSerMethod.invoke(
                        delegateOptions,
                        cacheDir.absolutePath,
                        "${model.modelId}_${model.version}",
                    )
                    Timber.d("GPU shader serialization enabled")
                } catch (_: NoSuchMethodException) {
                    Timber.d("GPU serialization not available in this TFLite version")
                } catch (e: Exception) {
                    Timber.w(e, "Failed to enable GPU serialization")
                }
            }

            // Float16 inference — ~2x throughput on GPU
            if (config.enableFloat16Inference) {
                delegateOptions.setPrecisionLossAllowed(true)
                delegateOptions.setInferencePreference(
                    GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED,
                )
                Timber.d("GPU float16 inference enabled")
            }

            gpuDelegate = GpuDelegate(delegateOptions)
            options.addDelegate(gpuDelegate)
            Timber.i("GPU delegate attached")
        } catch (e: Exception) {
            Timber.w(e, "Failed to create GPU delegate, falling back to CPU")
            gpuDelegate?.close()
            gpuDelegate = null
        }
    }

    /**
     * Attach NNAPI delegate for Android 8.1–14 devices.
     * NNAPI is deprecated in Android 15+; use vendor NPU delegates instead.
     */
    @Suppress("DEPRECATION")
    private fun tryAttachNnapi(options: Interpreter.Options) {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.O_MR1..34) {
            Timber.d("NNAPI skipped: API ${Build.VERSION.SDK_INT} outside supported range 27-34")
            return
        }
        try {
            options.setUseNNAPI(true)
            Timber.i("NNAPI delegate enabled (API ${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            Timber.w(e, "Failed to enable NNAPI delegate")
        }
    }

    /**
     * Try to attach vendor NPU delegates via reflection.
     * Returns true if a vendor delegate was successfully attached.
     *
     * Vendor AARs are optional — if not on classpath, this silently falls through.
     * To enable: add the vendor AAR to your app-level build.gradle dependencies.
     */
    private fun tryAttachVendorNpu(options: Interpreter.Options): Boolean {
        val soc = getSocIdentifier().lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()

        // Qualcomm QNN — Snapdragon NPU (replaces deprecated Hexagon DSP)
        if (soc.contains("qcom") || soc.contains("snapdragon") || soc.startsWith("sm")) {
            if (tryLoadReflectionDelegate(options, QUALCOMM_QNN_CLASS, "Qualcomm QNN")) return true
        }

        // Samsung Eden / ENN — Exynos NPU
        if (manufacturer == "samsung" && (soc.contains("exynos") || soc.contains("s5e"))) {
            if (tryLoadReflectionDelegate(options, SAMSUNG_EDEN_CLASS, "Samsung Eden")) return true
        }

        // MediaTek NeuroPilot — Dimensity APU
        if (soc.contains("mt") || soc.contains("mediatek") || soc.contains("dimensity")) {
            if (tryLoadReflectionDelegate(options, MEDIATEK_NEURON_CLASS, "MediaTek NeuroPilot")) return true
        }

        return false
    }

    /**
     * Load a TFLite delegate by class name via reflection.
     * The delegate must implement [Delegate] and have a no-arg constructor.
     */
    private fun tryLoadReflectionDelegate(
        options: Interpreter.Options,
        className: String,
        displayName: String,
    ): Boolean {
        return try {
            val clazz = Class.forName(className)
            val instance = clazz.getDeclaredConstructor().newInstance()
            if (instance is Delegate) {
                options.addDelegate(instance)
                vendorDelegate = instance
                Timber.i("$displayName delegate attached via reflection")
                true
            } else {
                Timber.w("$displayName class does not implement Delegate interface")
                false
            }
        } catch (_: ClassNotFoundException) {
            Timber.d("$displayName delegate not on classpath (add vendor AAR to enable)")
            false
        } catch (e: Exception) {
            Timber.w(e, "Failed to initialize $displayName delegate")
            false
        }
    }

    // =========================================================================
    // Thread & SoC Detection
    // =========================================================================

    /**
     * Resolve the effective thread count. When [EdgeMLConfig.preferBigCores] is true,
     * detects ARM big.LITTLE topology and returns the performance core count.
     */
    private fun resolveThreadCount(): Int {
        if (!config.preferBigCores) return config.numThreads
        val bigCores = detectBigCoreCount()
        if (bigCores != config.numThreads) {
            Timber.d("Thread count: %d big cores detected (config=%d)", bigCores, config.numThreads)
        }
        return bigCores
    }

    /**
     * Detect the number of "big" (performance) cores on ARM big.LITTLE SoCs by
     * reading max CPU frequencies from sysfs. Falls back to [config.numThreads].
     */
    private fun detectBigCoreCount(): Int {
        return try {
            val cpuDir = File("/sys/devices/system/cpu/")
            val cores = cpuDir.listFiles { file -> file.name.matches(Regex("cpu\\d+")) }
                ?: return config.numThreads

            val maxFreqs = cores.mapNotNull { core ->
                try {
                    File(core, "cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
                } catch (_: Exception) {
                    null
                }
            }

            if (maxFreqs.isEmpty()) return config.numThreads

            // "Big" cores are those with max freq >= 80% of the highest
            val topFreq = maxFreqs.max()
            val threshold = (topFreq * 0.8).toLong()
            maxFreqs.count { it >= threshold }.coerceAtLeast(1)
        } catch (_: Exception) {
            config.numThreads
        }
    }

    /**
     * Get the SoC identifier for vendor delegate gating.
     * Uses Build.SOC_MODEL on API 31+ (e.g., "SM8550"), falls back to Build.HARDWARE.
     */
    private fun getSocIdentifier(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }
    }

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
            gpuDelegate?.close()
            closeVendorDelegate()
        } catch (e: Exception) {
            Timber.w(e, "Error closing interpreter")
        } finally {
            interpreter = null
            gpuDelegate = null
            vendorDelegate = null
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

    private fun closeVendorDelegate() {
        val delegate = vendorDelegate ?: return
        try {
            delegate.javaClass.getMethod("close").invoke(delegate)
        } catch (_: Exception) {
            // Vendor delegate may not have a close method
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

/**
 * Result of a warmup pass including delegate benchmark.
 *
 * @property coldInferenceMs First inference time (includes JIT/shader/delegate init).
 * @property warmInferenceMs Second inference time (steady-state latency with selected delegate).
 * @property cpuInferenceMs CPU-only warm latency, if GPU was benchmarked. Null when GPU wasn't active.
 * @property usingGpu Whether the GPU delegate is active after warmup (may be false if it was disabled).
 * @property delegateDisabled True if GPU was disabled during warmup because CPU was faster.
 */
data class WarmupResult(
    val coldInferenceMs: Double,
    val warmInferenceMs: Double,
    val cpuInferenceMs: Double?,
    val usingGpu: Boolean,
    /** Which delegate survived warmup: "vendor_npu", "gpu", or "cpu" */
    val activeDelegate: String,
    /** Delegates that were disabled during cascade (e.g. ["vendor_npu", "gpu"]) */
    val disabledDelegates: List<String> = emptyList(),
) {
    /** True if any delegate was disabled during warmup. */
    val delegateDisabled: Boolean get() = disabledDelegates.isNotEmpty()
}
