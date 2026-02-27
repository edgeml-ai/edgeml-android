package ai.octomil.api.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for TelemetryV2 DTOs — serialization round-trips, typed attributes,
 * and optional trace/span fields.
 */
class TelemetryV2DtosTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // =========================================================================
    // TelemetryV2Event serialization
    // =========================================================================

    @Test
    fun `TelemetryV2Event roundtrips with JsonPrimitive attributes`() {
        val event = TelemetryV2Event(
            name = "inference.completed",
            timestamp = "2024-02-15T10:00:00.000Z",
            attributes = mapOf(
                "model.id" to JsonPrimitive("classifier"),
                "inference.duration_ms" to JsonPrimitive(12.5),
                "inference.success" to JsonPrimitive(true),
                "inference.batch_size" to JsonPrimitive(32),
            ),
        )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<TelemetryV2Event>(serialized)

        assertEquals("inference.completed", deserialized.name)
        assertEquals("2024-02-15T10:00:00.000Z", deserialized.timestamp)
        assertEquals(JsonPrimitive("classifier"), deserialized.attributes["model.id"])
        assertEquals(JsonPrimitive(12.5), deserialized.attributes["inference.duration_ms"])
        assertEquals(JsonPrimitive(true), deserialized.attributes["inference.success"])
        assertEquals(JsonPrimitive(32), deserialized.attributes["inference.batch_size"])
    }

    @Test
    fun `TelemetryV2Event serializes numbers as native JSON types`() {
        val event = TelemetryV2Event(
            name = "inference.completed",
            timestamp = "2024-02-15T10:00:00.000Z",
            attributes = mapOf(
                "inference.duration_ms" to JsonPrimitive(12.5),
                "inference.success" to JsonPrimitive(true),
            ),
        )

        val serialized = json.encodeToString(event)

        // Number should not be quoted — native JSON number
        assertTrue(serialized.contains("12.5"), "Expected unquoted number 12.5 in: $serialized")
        assertTrue(!serialized.contains("\"12.5\""), "Number should not be quoted in: $serialized")

        // Boolean should not be quoted
        assertTrue(serialized.contains("true"), "Expected unquoted boolean in: $serialized")
    }

    @Test
    fun `TelemetryV2Event trace_id and span_id are optional`() {
        val eventWithout = TelemetryV2Event(
            name = "inference.completed",
            timestamp = "2024-02-15T10:00:00.000Z",
        )

        assertNull(eventWithout.traceId)
        assertNull(eventWithout.spanId)

        val serialized = json.encodeToString(eventWithout)
        val deserialized = json.decodeFromString<TelemetryV2Event>(serialized)
        assertNull(deserialized.traceId)
        assertNull(deserialized.spanId)
    }

    @Test
    fun `TelemetryV2Event roundtrips with trace_id and span_id`() {
        val event = TelemetryV2Event(
            name = "inference.started",
            timestamp = "2024-02-15T10:00:00.000Z",
            traceId = "abc123def456",
            spanId = "span-001",
        )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<TelemetryV2Event>(serialized)

        assertEquals("abc123def456", deserialized.traceId)
        assertEquals("span-001", deserialized.spanId)
    }

    @Test
    fun `TelemetryV2Event deserializes from JSON without trace fields`() {
        val jsonStr = """
            {
                "name": "inference.completed",
                "timestamp": "2024-02-15T10:00:00.000Z",
                "attributes": {"model.id": "m1"}
            }
        """.trimIndent()

        val event = json.decodeFromString<TelemetryV2Event>(jsonStr)

        assertEquals("inference.completed", event.name)
        assertNull(event.traceId)
        assertNull(event.spanId)
        assertEquals(JsonPrimitive("m1"), event.attributes["model.id"])
    }

    // =========================================================================
    // TelemetryV2BatchRequest serialization
    // =========================================================================

    @Test
    fun `TelemetryV2BatchRequest roundtrips correctly`() {
        val batch = TelemetryV2BatchRequest(
            resource = TelemetryV2Resource(
                sdkVersion = "1.0.0",
                deviceId = "dev-1",
                orgId = "org-1",
            ),
            events = listOf(
                TelemetryV2Event(
                    name = "inference.completed",
                    timestamp = "2024-02-15T10:00:00.000Z",
                    attributes = mapOf("model.id" to JsonPrimitive("classifier")),
                    traceId = "trace-1",
                ),
            ),
        )

        val serialized = json.encodeToString(batch)
        val deserialized = json.decodeFromString<TelemetryV2BatchRequest>(serialized)

        assertEquals("1.0.0", deserialized.resource.sdkVersion)
        assertEquals("dev-1", deserialized.resource.deviceId)
        assertEquals("org-1", deserialized.resource.orgId)
        assertEquals(1, deserialized.events.size)
        assertEquals("trace-1", deserialized.events[0].traceId)
    }

    // =========================================================================
    // TelemetryAttributes helper
    // =========================================================================

    @Test
    fun `TelemetryAttributes of builds typed map from heterogeneous pairs`() {
        val attrs = TelemetryAttributes.of(
            "model.id" to "classifier",
            "inference.duration_ms" to 12.5,
            "inference.success" to true,
            "inference.batch_size" to 32,
            "skip_me" to null,
        )

        assertEquals(JsonPrimitive("classifier"), attrs["model.id"])
        assertEquals(JsonPrimitive(12.5), attrs["inference.duration_ms"])
        assertEquals(JsonPrimitive(true), attrs["inference.success"])
        assertEquals(JsonPrimitive(32), attrs["inference.batch_size"])
        assertTrue("skip_me" !in attrs, "Null values should be filtered out")
    }

    @Test
    fun `TelemetryAttributes of converts unknown types via toString`() {
        data class Custom(val x: Int)
        val attrs = TelemetryAttributes.of(
            "custom" to Custom(42),
        )

        assertEquals(JsonPrimitive("Custom(x=42)"), attrs["custom"])
    }

    @Test
    fun `TelemetryAttributes of returns empty map for empty input`() {
        val attrs = TelemetryAttributes.of()
        assertTrue(attrs.isEmpty())
    }
}
