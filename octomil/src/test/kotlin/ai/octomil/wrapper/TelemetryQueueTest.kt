package ai.octomil.wrapper

import ai.octomil.api.dto.TelemetryV2BatchRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [TelemetryQueue] — batching, flushing, persistence, v2 envelope,
 * and sender integration.
 *
 * Uses [StandardTestDispatcher] for deterministic coroutine control.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryQueueTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "octomil_telemetry_test_${System.nanoTime()}")
        tmpDir.mkdirs()
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun makeEvent(
        modelId: String = "test-model",
        latencyMs: Double = 5.0,
        success: Boolean = true,
        errorMessage: String? = null,
    ) = InferenceTelemetryEvent(
        modelId = modelId,
        latencyMs = latencyMs,
        timestampMs = 1708000000000L,
        success = success,
        errorMessage = errorMessage,
    )

    // =========================================================================
    // Enqueue / pending count
    // =========================================================================

    @Test
    fun `enqueue increments pending count`() {
        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = null,
            dispatcher = testDispatcher,
        )

        assertEquals(0, queue.pendingCount)

        queue.enqueue(makeEvent())
        assertEquals(1, queue.pendingCount)

        queue.enqueue(makeEvent())
        assertEquals(2, queue.pendingCount)

        queue.close()
    }

    // =========================================================================
    // Manual flush — v2 envelope
    // =========================================================================

    @Test
    fun `flush sends v2 batch with correct event count`() = runTest(testDispatcher) {
        val batches = mutableListOf<TelemetryV2BatchRequest>()
        val sender = TelemetrySender { batch -> batches.add(batch) }

        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
            dispatcher = testDispatcher,
            orgId = "org-123",
            deviceId = "dev-456",
        )

        queue.enqueue(makeEvent(modelId = "m1"))
        queue.enqueue(makeEvent(modelId = "m2"))

        queue.flush()

        assertEquals(0, queue.pendingCount)
        assertEquals(1, batches.size)
        assertEquals(2, batches[0].events.size)
        assertEquals("inference.completed", batches[0].events[0].name)
        assertEquals("inference.completed", batches[0].events[1].name)

        queue.close()
    }

    @Test
    fun `flush v2 resource contains sdk metadata`() = runTest(testDispatcher) {
        val batches = mutableListOf<TelemetryV2BatchRequest>()
        val sender = TelemetrySender { batch -> batches.add(batch) }

        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
            dispatcher = testDispatcher,
            orgId = "org-abc",
            deviceId = "dev-xyz",
        )

        queue.enqueue(makeEvent())
        queue.flush()

        val resource = batches[0].resource
        assertEquals("android", resource.sdk)
        assertEquals("android", resource.platform)
        assertEquals("org-abc", resource.orgId)
        assertEquals("dev-xyz", resource.deviceId)
        assertNotNull(resource.sdkVersion)

        queue.close()
    }

    @Test
    fun `flush v2 events have correct attributes`() = runTest(testDispatcher) {
        val batches = mutableListOf<TelemetryV2BatchRequest>()
        val sender = TelemetrySender { batch -> batches.add(batch) }

        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
            dispatcher = testDispatcher,
        )

        queue.enqueue(makeEvent(modelId = "classifier", latencyMs = 12.5))
        queue.flush()

        val event = batches[0].events[0]
        assertEquals("inference.completed", event.name)
        assertEquals("classifier", event.attributes["model.id"])
        assertEquals("12.5", event.attributes["inference.duration_ms"])
        assertEquals("on_device", event.attributes["inference.modality"])
        assertEquals("cpu", event.attributes["device.compute_unit"])
        assertEquals("tflite", event.attributes["model.format"])
        assertEquals("true", event.attributes["inference.success"])
        assertNotNull(event.timestamp)

        queue.close()
    }

    @Test
    fun `flush v2 includes error message on failure`() = runTest(testDispatcher) {
        val batches = mutableListOf<TelemetryV2BatchRequest>()
        val sender = TelemetrySender { batch -> batches.add(batch) }

        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
            dispatcher = testDispatcher,
        )

        queue.enqueue(makeEvent(success = false, errorMessage = "OOM"))
        queue.flush()

        val attrs = batches[0].events[0].attributes
        assertEquals("false", attrs["inference.success"])
        assertEquals("OOM", attrs["error.message"])

        queue.close()
    }

    @Test
    fun `flush with empty queue is a no-op`() = runTest(testDispatcher) {
        var sendCalled = false
        val sender = TelemetrySender { sendCalled = true }

        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
            dispatcher = testDispatcher,
        )

        queue.flush()

        assertTrue(!sendCalled)

        queue.close()
    }

    // =========================================================================
    // Size-based auto-flush
    // =========================================================================

    @Test
    fun `enqueue triggers auto-flush when batch size reached`() = runTest(testDispatcher) {
        val batches = mutableListOf<TelemetryV2BatchRequest>()
        val sender = TelemetrySender { batch -> batches.add(batch) }

        val queue = TelemetryQueue(
            batchSize = 3,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
            dispatcher = testDispatcher,
        )

        queue.enqueue(makeEvent())
        queue.enqueue(makeEvent())
        queue.enqueue(makeEvent()) // should trigger flush

        advanceUntilIdle()

        assertEquals(1, batches.size)
        assertEquals(3, batches[0].events.size)
        assertEquals(0, queue.pendingCount)

        queue.close()
    }

    // =========================================================================
    // Persistence on sender failure
    // =========================================================================

    @Test
    fun `events are persisted to disk when sender fails`() = runTest(testDispatcher) {
        val sender = TelemetrySender { throw RuntimeException("network down") }

        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = tmpDir,
            sender = sender,
            dispatcher = testDispatcher,
        )

        queue.enqueue(makeEvent())
        queue.enqueue(makeEvent())

        queue.flush()

        val files = tmpDir.listFiles()?.filter { it.extension == "json" }
        assertTrue(files != null && files.isNotEmpty(), "Expected persisted telemetry files")

        queue.close()
    }

    @Test
    fun `events are persisted to disk when no sender configured`() = runTest(testDispatcher) {
        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = tmpDir,
            sender = null,
            dispatcher = testDispatcher,
        )

        queue.enqueue(makeEvent())
        queue.flush()

        val files = tmpDir.listFiles()?.filter { it.extension == "json" }
        assertTrue(files != null && files.isNotEmpty())

        queue.close()
    }

    // =========================================================================
    // Close persists remaining events
    // =========================================================================

    @Test
    fun `close persists remaining events to disk`() {
        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = tmpDir,
            sender = null,
            dispatcher = testDispatcher,
        )

        queue.enqueue(makeEvent())
        queue.enqueue(makeEvent())
        queue.enqueue(makeEvent())

        queue.close()

        val files = tmpDir.listFiles()?.filter { it.extension == "json" }
        assertTrue(files != null && files.isNotEmpty())
    }

    // =========================================================================
    // Persisted event recovery
    // =========================================================================

    @Test
    fun `start resends persisted events as v2 batches`() = runTest(testDispatcher) {
        // First: persist events by flushing with no sender
        val queue1 = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = tmpDir,
            sender = null,
            dispatcher = testDispatcher,
        )
        queue1.enqueue(makeEvent())
        queue1.flush()
        queue1.close()

        val filesBefore = tmpDir.listFiles()?.filter { it.extension == "json" }
        assertTrue(filesBefore != null && filesBefore.isNotEmpty())

        // Second: create a new queue with a working sender and start it
        val batches = mutableListOf<TelemetryV2BatchRequest>()
        val sender = TelemetrySender { batch -> batches.add(batch) }

        val queue2 = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = tmpDir,
            sender = sender,
            dispatcher = testDispatcher,
        )
        queue2.start()
        advanceUntilIdle()

        // Persisted events should have been resent as v2 batch
        assertEquals(1, batches.size)
        assertEquals(1, batches[0].events.size)
        assertEquals("inference.completed", batches[0].events[0].name)

        // Persisted files should be cleaned up
        val filesAfter = tmpDir.listFiles()?.filter { it.extension == "json" }
        assertTrue(filesAfter == null || filesAfter.isEmpty())

        queue2.close()
    }

    // =========================================================================
    // InferenceTelemetryEvent
    // =========================================================================

    @Test
    fun `InferenceTelemetryEvent stores all fields`() {
        val event = InferenceTelemetryEvent(
            modelId = "classifier",
            latencyMs = 12.5,
            timestampMs = 1708000000000L,
            success = true,
            errorMessage = null,
        )

        assertEquals("classifier", event.modelId)
        assertEquals(12.5, event.latencyMs)
        assertEquals(1708000000000L, event.timestampMs)
        assertTrue(event.success)
        assertEquals(null, event.errorMessage)
    }

    @Test
    fun `InferenceTelemetryEvent stores error message on failure`() {
        val event = InferenceTelemetryEvent(
            modelId = "classifier",
            latencyMs = 0.0,
            timestampMs = 1708000000000L,
            success = false,
            errorMessage = "OOM",
        )

        assertTrue(!event.success)
        assertEquals("OOM", event.errorMessage)
    }

    @Test
    fun `InferenceTelemetryEvent equality works`() {
        val a = InferenceTelemetryEvent("m", 1.0, 100L, true)
        val b = InferenceTelemetryEvent("m", 1.0, 100L, true)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // =========================================================================
    // Max persisted files limit
    // =========================================================================

    @Test
    fun `persist respects max file limit`() = runTest(testDispatcher) {
        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = tmpDir,
            sender = null,
            dispatcher = testDispatcher,
        )

        for (i in 1..5) {
            queue.enqueue(makeEvent())
            queue.flush()
        }

        val files = tmpDir.listFiles()?.filter { it.extension == "json" }
        assertTrue(files != null && files.size in 1..5)

        queue.close()
    }

    // =========================================================================
    // V2 Envelope — buildV2Batch
    // =========================================================================

    @Test
    fun `buildV2Batch maps inference events to dot notation names`() {
        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = null,
            dispatcher = testDispatcher,
            orgId = "org-1",
            deviceId = "dev-1",
        )

        val events = listOf(
            makeEvent(modelId = "m1", latencyMs = 10.0),
            makeEvent(modelId = "m2", latencyMs = 20.0, success = false, errorMessage = "timeout"),
        )

        val batch = queue.buildV2Batch(events)

        assertEquals("android", batch.resource.sdk)
        assertEquals("android", batch.resource.platform)
        assertEquals("org-1", batch.resource.orgId)
        assertEquals("dev-1", batch.resource.deviceId)

        assertEquals(2, batch.events.size)

        val e1 = batch.events[0]
        assertEquals("inference.completed", e1.name)
        assertEquals("m1", e1.attributes["model.id"])
        assertEquals("10.0", e1.attributes["inference.duration_ms"])
        assertEquals("tflite", e1.attributes["model.format"])
        assertEquals("true", e1.attributes["inference.success"])
        assertTrue(e1.attributes["error.message"] == null)

        val e2 = batch.events[1]
        assertEquals("inference.completed", e2.name)
        assertEquals("m2", e2.attributes["model.id"])
        assertEquals("false", e2.attributes["inference.success"])
        assertEquals("timeout", e2.attributes["error.message"])

        queue.close()
    }

    @Test
    fun `buildV2FunnelBatch maps funnel events`() {
        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = null,
            dispatcher = testDispatcher,
            orgId = "org-2",
            deviceId = "dev-2",
        )

        val funnelEvent = FunnelEvent(
            stage = "pairing_started",
            success = true,
            deviceId = "dev-override",
            modelId = "model-abc",
            rolloutId = "rollout-1",
            sessionId = "sess-1",
            durationMs = 500,
            sdkVersion = "1.0.0",
            metadata = mapOf("key1" to "val1"),
        )

        val batch = queue.buildV2FunnelBatch(funnelEvent)

        assertEquals("android", batch.resource.sdk)
        assertEquals("1.0.0", batch.resource.sdkVersion)
        assertEquals("dev-override", batch.resource.deviceId)
        assertEquals("org-2", batch.resource.orgId)

        assertEquals(1, batch.events.size)
        val event = batch.events[0]
        assertEquals("funnel.pairing_started", event.name)
        assertEquals("true", event.attributes["funnel.success"])
        assertEquals("sdk_android", event.attributes["funnel.source"])
        assertEquals("model-abc", event.attributes["model.id"])
        assertEquals("rollout-1", event.attributes["funnel.rollout_id"])
        assertEquals("sess-1", event.attributes["funnel.session_id"])
        assertEquals("500", event.attributes["funnel.duration_ms"])
        assertEquals("val1", event.attributes["funnel.metadata.key1"])
        assertNotNull(event.timestamp)

        queue.close()
    }

    @Test
    fun `buildV2FunnelBatch includes failure attributes`() {
        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = null,
            dispatcher = testDispatcher,
        )

        val funnelEvent = FunnelEvent(
            stage = "model_download",
            success = false,
            failureReason = "HTTP 500",
            failureCategory = "server_error",
        )

        val batch = queue.buildV2FunnelBatch(funnelEvent)
        val attrs = batch.events[0].attributes

        assertEquals("funnel.model_download", batch.events[0].name)
        assertEquals("false", attrs["funnel.success"])
        assertEquals("HTTP 500", attrs["error.message"])
        assertEquals("server_error", attrs["error.category"])

        queue.close()
    }

    // =========================================================================
    // Single endpoint: both inference and funnel use sendBatch
    // =========================================================================

    @Test
    fun `reportFunnelEvent sends v2 batch through sender`() = runTest(testDispatcher) {
        val batches = mutableListOf<TelemetryV2BatchRequest>()
        val sender = TelemetrySender { batch -> batches.add(batch) }

        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
            dispatcher = testDispatcher,
            orgId = "org-funnel",
        )
        queue.start()

        queue.reportFunnelEvent(
            stage = "benchmark_completed",
            success = true,
            modelId = "model-x",
            durationMs = 1000,
        )

        advanceUntilIdle()

        assertEquals(1, batches.size)
        assertEquals("funnel.benchmark_completed", batches[0].events[0].name)
        assertEquals("org-funnel", batches[0].resource.orgId)

        queue.close()
    }

    @Test
    fun `v2 timestamp is ISO8601 format`() {
        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = null,
            dispatcher = testDispatcher,
        )

        val batch = queue.buildV2Batch(listOf(makeEvent()))
        val ts = batch.events[0].timestamp

        // Should match ISO8601 pattern: 2024-02-15T...Z
        assertTrue(ts.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")),
            "Timestamp should be ISO8601: $ts")

        queue.close()
    }
}
