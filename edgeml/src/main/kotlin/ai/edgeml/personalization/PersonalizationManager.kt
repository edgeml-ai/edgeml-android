package ai.edgeml.personalization

import ai.edgeml.config.EdgeMLConfig
import ai.edgeml.models.CachedModel
import ai.edgeml.training.TFLiteTrainer
import ai.edgeml.training.TrainingConfig
import ai.edgeml.training.TrainingDataProvider
import ai.edgeml.training.TrainingResult
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.Date

/**
 * Manages continuous on-device personalization with incremental training.
 *
 * Similar to Google Keyboard's adaptive learning, this manager:
 * - Buffers user interactions and training data
 * - Triggers incremental model updates in the background
 * - Manages personalized model versions per user
 * - Ensures privacy through local-only training
 * - Periodically uploads aggregated updates to the server (if FEDERATED mode)
 *
 * @param trainingMode The training mode (LOCAL_ONLY for maximum privacy, FEDERATED for collaborative learning)
 */
class PersonalizationManager(
    private val context: Context,
    private val config: EdgeMLConfig,
    private val trainer: TFLiteTrainer,
    private val bufferSizeThreshold: Int = 50,
    private val minSamplesForTraining: Int = 10,
    private val trainingIntervalMs: Long = 300_000, // 5 minutes
    private val trainingMode: TrainingMode = TrainingMode.LOCAL_ONLY,
    private val uploadThreshold: Int = 10,
) {

    private val mutex = Mutex()
    private val maxBufferSize = bufferSizeThreshold * 2

    // Training buffer
    private val trainingBuffer = mutableListOf<TrainingSample>()

    // Personalized model management
    private var personalizedModel: CachedModel? = null
    private var baseModel: CachedModel? = null
    private val trainingHistory = mutableListOf<TrainingSession>()

    // State
    private var isTraining = false
    private var lastTrainingTimeMs: Long? = null

    companion object {
        private const val TAG = "PersonalizationManager"
        private const val PERSONALIZED_MODELS_DIR = "personalized_models"
        private const val TRAINING_HISTORY_FILE = "training_history.json"
    }

    // =========================================================================
    // Model Management
    // =========================================================================

    /**
     * Sets the base model for personalization.
     *
     * @param model The base model to personalize
     */
    suspend fun setBaseModel(model: CachedModel) = mutex.withLock {
        baseModel = model

        // Check if a personalized version exists
        val personalizedFile = getPersonalizedModelFile(model.modelId)
        if (personalizedFile.exists()) {
            personalizedModel = model.copy(
                filePath = personalizedFile.absolutePath,
                version = "${model.version}-personalized",
            )

            Timber.i("Loaded personalized model for ${model.modelId}")
        }

        // Load training history
        loadTrainingHistory()
    }

    /**
     * Gets the current model (personalized if available, otherwise base).
     */
    suspend fun getCurrentModel(): CachedModel? = mutex.withLock {
        return personalizedModel ?: baseModel
    }

    /**
     * Resets personalization by deleting the personalized model.
     */
    suspend fun resetPersonalization() = mutex.withLock {
        val model = baseModel ?: run {
            Timber.w("No base model set")
            return@withLock
        }

        // Delete personalized model file
        val personalizedFile = getPersonalizedModelFile(model.modelId)
        personalizedFile.delete()

        // Clear state
        personalizedModel = null
        trainingBuffer.clear()
        trainingHistory.clear()
        lastTrainingTimeMs = null

        // Save empty history
        saveTrainingHistory()

        Timber.i("Reset personalization for model ${model.modelId}")
    }

    // =========================================================================
    // Training Data Collection
    // =========================================================================

    /**
     * Adds a training sample to the buffer.
     *
     * When the buffer reaches the threshold, training is automatically triggered.
     *
     * @param input Input data for training
     * @param target Expected output / label
     * @param metadata Optional metadata about the sample
     */
    suspend fun addTrainingSample(
        input: FloatArray,
        target: FloatArray,
        metadata: Map<String, String>? = null,
    ) = mutex.withLock {
        val sample = TrainingSample(
            input = input,
            target = target,
            timestampMs = System.currentTimeMillis(),
            metadata = metadata,
        )

        trainingBuffer.add(sample)

        // Enforce max buffer size
        if (trainingBuffer.size > maxBufferSize) {
            val removeCount = trainingBuffer.size - maxBufferSize
            repeat(removeCount) {
                trainingBuffer.removeAt(0)
            }

            Timber.w("Training buffer exceeded max size, removed $removeCount oldest samples")
        }

        Timber.d("Added training sample, buffer size: ${trainingBuffer.size}")

        // Check if we should trigger training
        if (shouldTriggerTraining()) {
            // Release lock before training
            mutex.unlock()
            try {
                trainIncrementally()
            } finally {
                mutex.lock()
            }
        }
    }

    /**
     * Adds multiple training samples at once.
     */
    suspend fun addTrainingSamples(samples: List<Pair<FloatArray, FloatArray>>) {
        for ((input, target) in samples) {
            addTrainingSample(input, target)
        }
    }

    // =========================================================================
    // Incremental Training
    // =========================================================================

    /**
     * Triggers incremental training on buffered samples.
     *
     * This happens automatically when the buffer threshold is reached,
     * but can also be called manually.
     */
    suspend fun trainIncrementally(): Result<TrainingResult> {
        // Check if already training
        if (isTraining) {
            Timber.d("Training already in progress, skipping")
            return Result.failure(IllegalStateException("Training already in progress"))
        }

        // Check buffer size
        val bufferSize = mutex.withLock { trainingBuffer.size }
        if (bufferSize < minSamplesForTraining) {
            Timber.d("Not enough samples for training ($bufferSize < $minSamplesForTraining)")
            return Result.failure(IllegalStateException("Not enough samples"))
        }

        // Get current model
        val model = getCurrentModel() ?: return Result.failure(
            IllegalStateException("No model loaded")
        )

        isTraining = true
        val startTime = System.currentTimeMillis()

        return try {
            Timber.i("Starting incremental training with $bufferSize samples")

            // Create data provider from buffer
            val samples = mutex.withLock { trainingBuffer.toList() }
            val dataProvider = BufferedTrainingDataProvider(samples)

            // Configure training (small updates for incremental learning)
            val trainingConfig = TrainingConfig(
                epochs = 1, // Single epoch for incremental updates
                batchSize = minOf(bufferSize, 32),
                learningRate = 0.0001f, // Small learning rate for fine-tuning
            )

            // Train the model
            val result = trainer.train(dataProvider, trainingConfig).getOrThrow()

            // Save personalized model
            savePersonalizedModel(result)

            // Record training session
            val session = TrainingSession(
                timestampMs = System.currentTimeMillis(),
                sampleCount = bufferSize,
                trainingTimeMs = System.currentTimeMillis() - startTime,
                loss = result.loss,
                accuracy = result.accuracy,
            )

            mutex.withLock {
                trainingHistory.add(session)
                saveTrainingHistory()

                // Clear buffer after successful training
                trainingBuffer.clear()
                lastTrainingTimeMs = System.currentTimeMillis()
            }

            Timber.i("Incremental training completed in ${session.trainingTimeMs}ms")

            // Check if we should upload updates (only in FEDERATED mode)
            if (trainingMode.uploadsToServer && trainingHistory.size >= uploadThreshold) {
                Timber.i("Upload threshold reached (${trainingHistory.size} sessions) - mode: $trainingMode")
                // TODO: Trigger upload of aggregated updates
            }

            Result.success(result)
        } catch (e: Exception) {
            Timber.e(e, "Incremental training failed")
            Result.failure(e)
        } finally {
            isTraining = false
        }
    }

    /**
     * Forces training on current buffer, regardless of thresholds.
     */
    suspend fun forceTraining(): Result<TrainingResult> {
        val bufferSize = mutex.withLock { trainingBuffer.size }

        if (bufferSize == 0) {
            return Result.failure(IllegalStateException("No training samples in buffer"))
        }

        return trainIncrementally()
    }

    // =========================================================================
    // Statistics
    // =========================================================================

    /**
     * Gets statistics about personalization progress.
     */
    suspend fun getStatistics(): PersonalizationStatistics = mutex.withLock {
        PersonalizationStatistics(
            totalTrainingSessions = trainingHistory.size,
            totalSamplesTrained = trainingHistory.sumOf { it.sampleCount },
            bufferedSamples = trainingBuffer.size,
            lastTrainingTimeMs = lastTrainingTimeMs,
            averageLoss = trainingHistory.mapNotNull { it.loss }.average().takeIf { !it.isNaN() },
            averageAccuracy = trainingHistory.mapNotNull { it.accuracy }.average().takeIf { !it.isNaN() },
            isPersonalized = personalizedModel != null,
            trainingMode = trainingMode,
        )
    }

    /**
     * Gets the training history.
     */
    suspend fun getTrainingHistory(): List<TrainingSession> = mutex.withLock {
        trainingHistory.toList()
    }

    /**
     * Clears the training buffer without training.
     */
    suspend fun clearBuffer() = mutex.withLock {
        trainingBuffer.clear()
        Timber.i("Training buffer cleared")
    }

    // =========================================================================
    // Private Methods
    // =========================================================================

    private suspend fun shouldTriggerTraining(): Boolean {
        // Must hold mutex lock when calling this

        // Check buffer size threshold
        if (trainingBuffer.size < bufferSizeThreshold) {
            return false
        }

        // Check minimum samples
        if (trainingBuffer.size < minSamplesForTraining) {
            return false
        }

        // Check training interval
        lastTrainingTimeMs?.let { lastTime ->
            val timeSinceLastTraining = System.currentTimeMillis() - lastTime
            if (timeSinceLastTraining < trainingIntervalMs) {
                return false
            }
        }

        // Don't trigger if already training
        if (isTraining) {
            return false
        }

        return true
    }

    private fun getPersonalizedModelFile(modelId: String): File {
        val modelsDir = File(context.filesDir, PERSONALIZED_MODELS_DIR)
        modelsDir.mkdirs()
        return File(modelsDir, "$modelId-personalized.tflite")
    }

    private fun getTrainingHistoryFile(): File {
        return File(context.filesDir, TRAINING_HISTORY_FILE)
    }

    private suspend fun savePersonalizedModel(result: TrainingResult) {
        val model = baseModel ?: return

        // Get the updated model path from training result
        val updatedModelPath = result.updatedModelPath ?: run {
            Timber.w("No updated model path in training result")
            return
        }

        // Copy to personalized model location
        val personalizedFile = getPersonalizedModelFile(model.modelId)
        File(updatedModelPath).copyTo(personalizedFile, overwrite = true)

        // Update personalized model reference
        personalizedModel = model.copy(
            filePath = personalizedFile.absolutePath,
            version = "${model.version}-personalized",
        )

        Timber.i("Saved personalized model to ${personalizedFile.absolutePath}")
    }

    private fun saveTrainingHistory() {
        try {
            val json = Json.encodeToString(trainingHistory)
            getTrainingHistoryFile().writeText(json)
        } catch (e: Exception) {
            Timber.e(e, "Failed to save training history")
        }
    }

    private fun loadTrainingHistory() {
        try {
            val file = getTrainingHistoryFile()
            if (file.exists()) {
                val json = file.readText()
                val loaded = Json.decodeFromString<List<TrainingSession>>(json)
                trainingHistory.clear()
                trainingHistory.addAll(loaded)
                Timber.i("Loaded ${trainingHistory.size} training sessions from history")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load training history")
        }
    }
}

// =========================================================================
// Supporting Types
// =========================================================================

/**
 * Represents a single training sample with metadata.
 */
data class TrainingSample(
    val input: FloatArray,
    val target: FloatArray,
    val timestampMs: Long,
    val metadata: Map<String, String>?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TrainingSample

        if (!input.contentEquals(other.input)) return false
        if (!target.contentEquals(other.target)) return false
        if (timestampMs != other.timestampMs) return false
        if (metadata != other.metadata) return false

        return true
    }

    override fun hashCode(): Int {
        var result = input.contentHashCode()
        result = 31 * result + target.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + (metadata?.hashCode() ?: 0)
        return result
    }
}

/**
 * Training data provider backed by buffered samples.
 */
private class BufferedTrainingDataProvider(
    private val samples: List<TrainingSample>,
) : TrainingDataProvider {

    override suspend fun getTrainingData(): List<Pair<FloatArray, FloatArray>> {
        return samples.map { it.input to it.target }
    }

    override suspend fun getSampleCount(): Int = samples.size
}

/**
 * Record of a training session.
 */
@Serializable
data class TrainingSession(
    val timestampMs: Long,
    val sampleCount: Int,
    val trainingTimeMs: Long,
    val loss: Double?,
    val accuracy: Double?,
)

/**
 * Statistics about personalization progress.
 */
data class PersonalizationStatistics(
    val totalTrainingSessions: Int,
    val totalSamplesTrained: Int,
    val bufferedSamples: Int,
    val lastTrainingTimeMs: Long?,
    val averageLoss: Double?,
    val averageAccuracy: Double?,
    val isPersonalized: Boolean,
    val trainingMode: TrainingMode,
)
