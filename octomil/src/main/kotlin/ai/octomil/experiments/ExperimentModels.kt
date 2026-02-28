package ai.octomil.experiments

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An A/B experiment targeting one or more models.
 */
@Serializable
data class Experiment(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("status")
    val status: String, // "draft", "active", "paused", "completed"
    @SerialName("variants")
    val variants: List<ExperimentVariant>,
    @SerialName("created_at")
    val createdAt: String,
)

/**
 * A single variant within an experiment, mapping to a specific model version.
 */
@Serializable
data class ExperimentVariant(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("model_id")
    val modelId: String,
    @SerialName("model_version")
    val modelVersion: String,
    @SerialName("traffic_percentage")
    val trafficPercentage: Int,
)

/**
 * Result of resolving which experiment (if any) affects a given model.
 */
data class ModelExperimentResult(
    val experiment: Experiment,
    val variant: ExperimentVariant,
)

/**
 * Request body for POST /api/v1/experiments/{id}/metrics.
 */
@Serializable
data class ExperimentMetricRequest(
    @SerialName("metric_name")
    val metricName: String,
    @SerialName("metric_value")
    val metricValue: Double,
    @SerialName("device_id")
    val deviceId: String? = null,
)
