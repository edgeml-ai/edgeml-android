package ai.octomil.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
 * (e.g. "inference.completed", "funnel.pairing_started") and flat attributes.
 */
@Serializable
data class TelemetryV2Event(
    @SerialName("name")
    val name: String,
    @SerialName("timestamp")
    val timestamp: String,
    @SerialName("attributes")
    val attributes: Map<String, String> = emptyMap(),
)
