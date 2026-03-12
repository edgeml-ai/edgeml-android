package ai.octomil.client

import ai.octomil.api.dto.TelemetryV2Event
import ai.octomil.wrapper.TelemetryQueue
import kotlinx.serialization.json.JsonPrimitive

/**
 * Public-facing telemetry API for emitting custom events and flushing
 * the event buffer.
 *
 * Accessed via `client.telemetry`.
 *
 * ```kotlin
 * // Emit a custom event
 * client.telemetry.track("user.action", mapOf("button" to "download"))
 *
 * // Force-flush before app goes to background
 * client.telemetry.flush()
 * ```
 */
class TelemetryClient internal constructor(
    private val queue: TelemetryQueue?,
) {
    /**
     * Force-send all buffered telemetry events.
     *
     * Call this before the app goes to the background or during
     * [OctomilClient.close] to ensure no events are lost.
     */
    suspend fun flush() {
        queue?.flush()
    }

    /**
     * Emit a custom telemetry event.
     *
     * Events are buffered and flushed periodically or when the batch
     * threshold is reached.
     *
     * @param name Event name (e.g., "user.action", "feature.used").
     * @param attributes Free-form key-value attributes attached to the event.
     */
    fun track(name: String, attributes: Map<String, Any> = emptyMap()) {
        val q = queue ?: return

        val converted: Map<String, JsonPrimitive> = attributes.mapValues { (_, v) ->
            when (v) {
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        }

        q.enqueueV2Event(
            TelemetryV2Event(
                name = name,
                timestamp = q.formatTimestamp(),
                attributes = converted,
            ),
        )
    }
}
