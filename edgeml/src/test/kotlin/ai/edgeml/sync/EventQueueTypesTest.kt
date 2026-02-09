package ai.edgeml.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for EventQueue data types and serialization.
 */
class EventQueueTypesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // =========================================================================
    // QueuedEvent
    // =========================================================================

    @Test
    fun `QueuedEvent serialization roundtrip with all fields`() {
        val event = QueuedEvent(
            id = "evt-1",
            type = "inference_completed",
            timestamp = 1706745600000,
            metrics = mapOf("latency_ms" to 42.5, "accuracy" to 0.95),
            metadata = mapOf("model_id" to "m1", "version" to "1.0"),
        )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<QueuedEvent>(serialized)

        assertEquals("evt-1", deserialized.id)
        assertEquals("inference_completed", deserialized.type)
        assertEquals(1706745600000, deserialized.timestamp)
        assertEquals(42.5, deserialized.metrics?.get("latency_ms"))
        assertEquals(0.95, deserialized.metrics?.get("accuracy"))
        assertEquals("m1", deserialized.metadata?.get("model_id"))
    }

    @Test
    fun `QueuedEvent serialization roundtrip with null optional fields`() {
        val event = QueuedEvent(
            id = "evt-2",
            type = "sync_started",
            timestamp = 1000L,
        )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<QueuedEvent>(serialized)

        assertEquals("evt-2", deserialized.id)
        assertEquals("sync_started", deserialized.type)
        assertNull(deserialized.metrics)
        assertNull(deserialized.metadata)
    }

    @Test
    fun `QueuedEvent deserialization from raw JSON`() {
        val jsonStr = """
            {
                "id": "evt-3",
                "type": "training_completed",
                "timestamp": 9999,
                "metrics": {"loss": 0.01},
                "metadata": {"tag": "test"}
            }
        """.trimIndent()

        val event = json.decodeFromString<QueuedEvent>(jsonStr)

        assertEquals("evt-3", event.id)
        assertEquals("training_completed", event.type)
        assertEquals(9999L, event.timestamp)
        assertEquals(0.01, event.metrics?.get("loss"))
        assertEquals("test", event.metadata?.get("tag"))
    }

    @Test
    fun `QueuedEvent ignores unknown fields during deserialization`() {
        val jsonStr = """
            {
                "id": "evt-4",
                "type": "test",
                "timestamp": 100,
                "unknown_field": "ignored"
            }
        """.trimIndent()

        val event = json.decodeFromString<QueuedEvent>(jsonStr)
        assertEquals("evt-4", event.id)
    }

    @Test
    fun `QueuedEvent equality works`() {
        val event1 = QueuedEvent("e1", "test", 100L, mapOf("k" to 1.0), mapOf("m" to "v"))
        val event2 = QueuedEvent("e1", "test", 100L, mapOf("k" to 1.0), mapOf("m" to "v"))

        assertEquals(event1, event2)
        assertEquals(event1.hashCode(), event2.hashCode())
    }

    // =========================================================================
    // EventTypes
    // =========================================================================

    @Test
    fun `EventTypes contains all expected model events`() {
        assertEquals("model_loaded", EventTypes.MODEL_LOADED)
        assertEquals("model_download_started", EventTypes.MODEL_DOWNLOAD_STARTED)
        assertEquals("model_download_completed", EventTypes.MODEL_DOWNLOAD_COMPLETED)
        assertEquals("model_download_failed", EventTypes.MODEL_DOWNLOAD_FAILED)
    }

    @Test
    fun `EventTypes contains all expected inference events`() {
        assertEquals("inference_started", EventTypes.INFERENCE_STARTED)
        assertEquals("inference_completed", EventTypes.INFERENCE_COMPLETED)
        assertEquals("inference_failed", EventTypes.INFERENCE_FAILED)
    }

    @Test
    fun `EventTypes contains all expected training events`() {
        assertEquals("training_started", EventTypes.TRAINING_STARTED)
        assertEquals("training_completed", EventTypes.TRAINING_COMPLETED)
        assertEquals("training_failed", EventTypes.TRAINING_FAILED)
    }

    @Test
    fun `EventTypes contains device and sync events`() {
        assertEquals("device_registered", EventTypes.DEVICE_REGISTERED)
        assertEquals("sync_started", EventTypes.SYNC_STARTED)
        assertEquals("sync_completed", EventTypes.SYNC_COMPLETED)
        assertEquals("sync_failed", EventTypes.SYNC_FAILED)
    }

    @Test
    fun `EventTypes contains streaming generation events`() {
        assertEquals("generation_started", EventTypes.GENERATION_STARTED)
        assertEquals("chunk_produced", EventTypes.CHUNK_PRODUCED)
        assertEquals("generation_completed", EventTypes.GENERATION_COMPLETED)
        assertEquals("generation_failed", EventTypes.GENERATION_FAILED)
    }
}
