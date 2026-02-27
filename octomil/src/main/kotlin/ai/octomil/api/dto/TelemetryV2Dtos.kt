package ai.octomil.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * V2 OTLP-style telemetry envelope for POST /api/v2/telemetry/events.
 *
 * All SDK telemetry (inference events and funnel events) is sent through
 * this single envelope, replacing the separate v1 endpoints.
 */
@Serializable
data class TelemetryV2BatchRequest(
    @SerialName("resource")
    val resource: TelemetryV2Resource,
    @SerialName("events")
    val events: List<TelemetryV2Event>,
)

/**
 * Resource block identifying the SDK, device, and organization.
 * Shared across all events in the batch.
 */
@Serializable
data class TelemetryV2Resource(
    @SerialName("sdk")
    val sdk: String = "android",
    @SerialName("sdk_version")
    val sdkVersion: String,
    @SerialName("device_id")
    val deviceId: String? = null,
    @SerialName("platform")
    val platform: String = "android",
    @SerialName("org_id")
    val orgId: String? = null,
)

/**
 * A single event in the v2 OTLP envelope. Uses dot-notation event names
 * (e.g. "inference.completed", "funnel.pairing_started") and typed attributes.
 *
 * Attributes use [JsonPrimitive] values so numbers and booleans serialize as
 * native JSON types (e.g. `12.5` instead of `"12.5"`).
 */
@Serializable
data class TelemetryV2Event(
    @SerialName("name")
    val name: String,
    @SerialName("timestamp")
    val timestamp: String,
    @SerialName("attributes")
    val attributes: Map<String, JsonPrimitive> = emptyMap(),
    @SerialName("trace_id")
    val traceId: String? = null,
    @SerialName("span_id")
    val spanId: String? = null,
)

/**
 * Helper for building typed attribute maps from heterogeneous key-value pairs.
 *
 * Usage:
 * ```kotlin
 * val attrs = TelemetryAttributes.of(
 *     "model.id" to "classifier",
 *     "inference.duration_ms" to 12.5,
 *     "inference.success" to true,
 * )
 * ```
 */
object TelemetryAttributes {
    fun of(vararg pairs: Pair<String, Any?>): Map<String, JsonPrimitive> {
        return pairs.mapNotNull { (key, value) ->
            when (value) {
                null -> null
                is String -> key to JsonPrimitive(value)
                is Number -> key to JsonPrimitive(value)
                is Boolean -> key to JsonPrimitive(value)
                else -> key to JsonPrimitive(value.toString())
            }
        }.toMap()
    }
}
