package ai.octomil.experiments

import ai.octomil.api.OctomilApi
import ai.octomil.wrapper.TelemetryQueue
import timber.log.Timber

/**
 * Client for managing A/B experiments across model variants.
 *
 * Provides deterministic variant assignment using hash-based bucketing,
 * matching the Node SDK's `ExperimentsClient` implementation for
 * cross-SDK parity.
 *
 * Usage:
 * ```kotlin
 * val experiments = client.experiments
 * val active = experiments.getActiveExperiments()
 * val variant = experiments.getVariant(active.first(), deviceId)
 * experiments.trackMetric(active.first().id, "accuracy", 0.95)
 * ```
 */
class ExperimentsClient(
    private val api: OctomilApi,
    private val telemetryQueue: TelemetryQueue? = null,
) {
    /**
     * Get all active experiments for the organization.
     *
     * @return List of experiments, or an empty list on failure.
     */
    suspend fun getActiveExperiments(): List<Experiment> {
        val response = api.getActiveExperiments()
        if (!response.isSuccessful) {
            Timber.w("Failed to fetch active experiments: ${response.code()}")
            return emptyList()
        }
        return response.body() ?: emptyList()
    }

    /**
     * Get configuration for a specific experiment.
     *
     * @param experimentId The experiment UUID.
     * @return The experiment configuration.
     * @throws ExperimentException if the experiment is not found or the request fails.
     */
    suspend fun getExperimentConfig(experimentId: String): Experiment {
        val response = api.getExperimentConfig(experimentId)
        if (!response.isSuccessful) {
            throw ExperimentException(
                "Failed to get experiment config: ${response.code()}",
                experimentId = experimentId,
            )
        }
        return response.body()
            ?: throw ExperimentException(
                "Empty experiment config response",
                experimentId = experimentId,
            )
    }

    /**
     * Get the variant assigned to a device for an experiment.
     *
     * Uses deterministic hashing so the same device always gets the same
     * variant for a given experiment. Only returns a variant for active
     * experiments.
     *
     * @param experiment The experiment to resolve.
     * @param deviceId The device identifier for bucketing.
     * @return The assigned variant, or null if the experiment is not active
     *         or the device falls outside all traffic buckets.
     */
    fun getVariant(experiment: Experiment, deviceId: String): ExperimentVariant? {
        if (experiment.status != "active") return null
        if (experiment.variants.isEmpty()) return null

        val hash = "${experiment.id}:${deviceId}".hashCode().toUInt()
        val bucket = (hash % 100u).toInt()

        var cumulative = 0
        for (variant in experiment.variants) {
            cumulative += variant.trafficPercentage
            if (bucket < cumulative) {
                // Emit experiment.assigned telemetry
                try {
                    telemetryQueue?.reportExperimentAssigned(
                        modelId = variant.modelId,
                        experimentId = experiment.id,
                        variant = variant.name,
                    )
                } catch (_: Exception) {
                    // Telemetry must never crash the app
                }
                return variant
            }
        }
        return null
    }

    /**
     * Check if a device is enrolled in an experiment.
     *
     * A device is enrolled if [getVariant] returns a non-null result.
     *
     * @param experiment The experiment to check.
     * @param deviceId The device identifier.
     * @return True if the device is assigned to a variant.
     */
    fun isEnrolled(experiment: Experiment, deviceId: String): Boolean {
        return getVariant(experiment, deviceId) != null
    }

    /**
     * Resolve which experiment (if any) affects a given model, and return
     * the variant assigned to this device.
     *
     * Fetches active experiments and finds the first one with a variant
     * whose [ExperimentVariant.modelId] matches [modelId].
     *
     * @param modelId The model to check.
     * @param deviceId The device identifier for bucketing.
     * @return The matching experiment and variant, or null if none found.
     */
    suspend fun resolveModelExperiment(
        modelId: String,
        deviceId: String,
    ): ModelExperimentResult? {
        val experiments = getActiveExperiments()
        for (experiment in experiments) {
            val hasModel = experiment.variants.any { it.modelId == modelId }
            if (!hasModel) continue

            val variant = getVariant(experiment, deviceId)
            if (variant != null) {
                return ModelExperimentResult(experiment = experiment, variant = variant)
            }
        }
        return null
    }

    /**
     * Track a metric for an experiment.
     *
     * Sends the metric to the server and emits an `experiment.metric`
     * telemetry event.
     *
     * @param experimentId The experiment UUID.
     * @param metricName Name of the metric (e.g. "accuracy", "latency_ms").
     * @param metricValue Numeric value of the metric.
     * @param deviceId Optional device identifier to include in the request.
     */
    suspend fun trackMetric(
        experimentId: String,
        metricName: String,
        metricValue: Double,
        deviceId: String? = null,
    ) {
        val request = ExperimentMetricRequest(
            metricName = metricName,
            metricValue = metricValue,
            deviceId = deviceId,
        )

        val response = api.trackExperimentMetric(experimentId, request)
        if (!response.isSuccessful) {
            Timber.w("Failed to track experiment metric: ${response.code()}")
        }

        // Emit experiment.metric telemetry
        try {
            telemetryQueue?.reportExperimentMetric(
                experimentId = experimentId,
                metricName = metricName,
                metricValue = metricValue,
            )
        } catch (_: Exception) {
            // Telemetry must never crash the app
        }
    }
}

/**
 * Exception thrown when an experiment operation fails.
 */
class ExperimentException(
    message: String,
    val experimentId: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
