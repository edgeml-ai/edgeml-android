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

/**
 * Controls whether and how weight updates are uploaded after training.
 *
 * This is the single knob developers use to decide upload behavior:
 * - [AUTO]: Upload automatically after training completes (default for FEDERATED mode).
 * - [MANUAL]: Train locally and return the [WeightUpdate] without uploading.
 *   The developer can inspect, transform, or upload it themselves.
 * - [DISABLED]: Local-only training. No weight data is prepared for upload.
 */
enum class UploadPolicy {
    /**
     * Upload weight updates automatically after training completes.
     *
     * If a roundId is provided, uploads to that round (with SecAgg if enabled).
     * Otherwise uploads as an ad-hoc update.
     */
    AUTO,

    /**
     * Train locally and return weight updates without uploading.
     *
     * Use this when you need to inspect or transform weights before upload,
     * or when you want full control over upload timing.
     */
    MANUAL,

    /**
     * Local-only training. No weight extraction or upload.
     *
     * Use this for pure on-device personalization where model
     * improvements never leave the device.
     */
    DISABLED,
}

/**
 * Result of the unified [ai.edgeml.client.EdgeMLClient.train] method.
 *
 * Combines training metrics, optional weight update, and upload status
 * into a single result type.
 */
data class TrainingOutcome(
    /** Local training metrics (loss, accuracy, timing). */
    val trainingResult: TrainingResult,
    /** Extracted weight update, or null if [UploadPolicy.DISABLED]. */
    val weightUpdate: WeightUpdate? = null,
    /** Whether the update was uploaded to the server. */
    val uploaded: Boolean = false,
    /** Whether secure aggregation was used for the upload. */
    val secureAggregation: Boolean = false,
    /** The upload policy that was used. */
    val uploadPolicy: UploadPolicy = UploadPolicy.DISABLED,
    /**
     * Whether training ran in degraded mode (forward-pass only, no gradient updates).
     *
     * When true, the model's weights were NOT updated on-device. The loss and accuracy
     * metrics reflect inference on training data, not actual learning. To enable real
     * on-device training, export your model with TFLite training signatures.
     *
     * @see [ai.edgeml.training.TFLiteTrainer.hasTrainingSignature]
     */
    val degraded: Boolean = false,
)

/**
 * Exception thrown when a model lacks training signatures and
 * [EdgeMLConfig.allowDegradedTraining][ai.edgeml.config.EdgeMLConfig.allowDegradedTraining]
 * is false (the default).
 *
 * To fix this, export your TFLite model with training signatures:
 * ```python
 * converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
 * converter.experimental_enable_resource_variables = True
 * tflite_model = converter.convert()
 * ```
 *
 * Or set `allowDegradedTraining(true)` in config to permit forward-pass-only training.
 */
class MissingTrainingSignatureException(
    availableSignatures: List<String>,
) : IllegalStateException(
    "Model does not have a 'train' signature and cannot perform on-device gradient updates. " +
        "Available signatures: $availableSignatures. " +
        "Either export your model with training signatures " +
        "(converter.experimental_enable_resource_variables = True) " +
        "or set allowDegradedTraining(true) to permit forward-pass-only training.",
)
