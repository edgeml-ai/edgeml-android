package ai.edgeml.training

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration for on-device training.
 */
@Serializable
data class TrainingConfig(
    /** Number of training epochs */
    @SerialName("epochs")
    val epochs: Int = 1,
    /** Batch size for training */
    @SerialName("batch_size")
    val batchSize: Int = 32,
    /** Learning rate */
    @SerialName("learning_rate")
    val learningRate: Float = 0.001f,
    /** Loss function type (e.g., "categorical_crossentropy", "mse") */
    @SerialName("loss_function")
    val lossFunction: String = "categorical_crossentropy",
    /** Optimizer type (e.g., "adam", "sgd") */
    @SerialName("optimizer")
    val optimizer: String = "adam",
)

/**
 * Result from a local training session.
 */
data class TrainingResult(
    /** Number of samples used for training */
    val sampleCount: Int,
    /** Final training loss */
    val loss: Double?,
    /** Training accuracy (if available) */
    val accuracy: Double?,
    /** Training time in seconds */
    val trainingTime: Double,
    /** Additional training metrics */
    val metrics: Map<String, Double> = emptyMap(),
    /** Path to the updated model file */
    val updatedModelPath: String? = null,
)

/**
 * Weight update for upload to the server.
 */
@Serializable
data class WeightUpdate(
    /** Model identifier */
    @SerialName("model_id")
    val modelId: String,
    /** Model version */
    @SerialName("version")
    val version: String,
    /** Serialized weight data (delta or full weights) */
    @SerialName("weights_data")
    val weightsData: ByteArray,
    /** Number of samples used for training */
    @SerialName("sample_count")
    val sampleCount: Int,
    /** Training metrics */
    @SerialName("metrics")
    val metrics: Map<String, Double> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WeightUpdate

        if (modelId != other.modelId) return false
        if (version != other.version) return false
        if (!weightsData.contentEquals(other.weightsData)) return false
        if (sampleCount != other.sampleCount) return false
        if (metrics != other.metrics) return false

        return true
    }

    override fun hashCode(): Int {
        var result = modelId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + weightsData.contentHashCode()
        result = 31 * result + sampleCount
        result = 31 * result + metrics.hashCode()
        return result
    }
}

/**
 * Training data provider interface.
 */
interface TrainingDataProvider {
    /**
     * Get training data as a list of input-output pairs.
     *
     * @return List of (input, label) pairs
     */
    suspend fun getTrainingData(): List<Pair<FloatArray, FloatArray>>

    /**
     * Get the number of training samples.
     */
    suspend fun getSampleCount(): Int
}

/**
 * Simple in-memory training data provider.
 */
class InMemoryTrainingDataProvider(
    private val data: List<Pair<FloatArray, FloatArray>>,
) : TrainingDataProvider {
    override suspend fun getTrainingData(): List<Pair<FloatArray, FloatArray>> = data

    override suspend fun getSampleCount(): Int = data.size
}
