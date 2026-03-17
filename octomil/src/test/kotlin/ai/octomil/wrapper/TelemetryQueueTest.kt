package ai.octomil.wrapper

import ai.octomil.api.dto.AnyValue
import ai.octomil.api.dto.ExportLogsServiceRequest
import ai.octomil.api.dto.LogRecord
import ai.octomil.api.dto.TelemetryAttributes
import ai.octomil.api.dto.TelemetryV2Event
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [TelemetryQueue] — batching, flushing, persistence, OTLP envelope,
 * generic v2 events, inference.failed naming, and sender integration.
 *
 * Uses [StandardTestDispatcher] for deterministic coroutine control.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryQueueTest {

    // =========================================================================
    // OTLP helpers — extract log records and resource attrs from OTLP envelope
    // =========================================================================

    /** Flatten all log records from an OTLP request. */
    private val ExportLogsServiceRequest.logRecords: List<LogRecord>
        get() = resourceLogs.flatMap { it.scopeLogs }.flatMap { it.logRecords }

    /** Get the body string of a log record (event name). */
    private val LogRecord.eventName: String
        get() = (body as? AnyValue.StringValue)?.stringValue ?: ""

    /** Get a resource attribute value by key. */
    private fun ExportLogsServiceRequest.resourceAttr(key: String): String? =
        resourceLogs.firstOrNull()?.resource?.attributes
            ?.firstOrNull { it.key == key }
            ?.let { (it.value as? AnyValue.StringValue)?.stringValue }

    /** Get a log record attribute string value by key. */
    private fun LogRecord.attrString(key: String): String? =
        attributes?.firstOrNull { it.key == key }
            ?.let { (it.value as? AnyValue.StringValue)?.stringValue }


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
        val batches = mutableListOf<ExportLogsServiceRequest>()
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
        val records = batches[0].logRecords
        assertEquals(2, records.size)
        assertEquals("inference.completed", records[0].eventName)
        assertEquals("inference.completed", records[1].eventName)

        queue.close()
    }

    @Test
    fun `flush OTLP resource contains sdk metadata`() = runTest(testDispatcher) {
        val batches = mutableListOf<ExportLogsServiceRequest>()
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

        val req = batches[0]
        assertEquals("android", req.resourceAttr("telemetry.sdk.name"))
        assertEquals("org-abc", req.resourceAttr("org.id"))
        assertEquals("dev-xyz", req.resourceAttr("device.id"))
        assertEquals("dev-xyz", req.resourceAttr("octomil.device.id"))
        assertNotNull(req.resourceAttr("service.version"))

        queue.close()
    }

    @Test
    fun `flush OTLP events have typed attributes`() = runTest(testDispatcher) {
        val batches = mutableListOf<ExportLogsServiceRequest>()
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

        val record = batches[0].logRecords[0]
        assertEquals("inference.completed", record.eventName)
        assertEquals("classifier", record.attrString("model.id"))
        assertNotNull(record.timeUnixNano)

        queue.close()
    }

    @Test
    fun `flush OTLP includes error message on failure`() = runTest(testDispatcher) {
        val batches = mutableListOf<ExportLogsServiceRequest>()
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

        val record = batches[0].logRecords[0]
        assertEquals("inference.failed", record.eventName)
        assertEquals("OOM", record.attrString("error.message"))

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
    // inference.failed event name for failure events
    // =========================================================================

    @Test
    fun `failed inference events use inference_failed name`() = runTest(testDispatcher) {
        val batches = mutableListOf<ExportLogsServiceRequest>()
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

        assertEquals("inference.failed", batches[0].logRecords[0].eventName)

        queue.close()
    }

    @Test
    fun `successful inference events use inference_completed name`() = runTest(testDispatcher) {
        val batches = mutableListOf<ExportLogsServiceRequest>()
        val sender = TelemetrySender { batch -> batches.add(batch) }

        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
            dispatcher = testDispatcher,
        )

        queue.enqueue(makeEvent(success = true))
        queue.flush()

        assertEquals("inference.completed", batches[0].logRecords[0].eventName)

        queue.close()
    }

    // =========================================================================
    // Size-based auto-flush
    // =========================================================================

    @Test
    fun `enqueue triggers auto-flush when batch size reached`() = runTest(testDispatcher) {
        val batches = mutableListOf<ExportLogsServiceRequest>()
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
        assertEquals(3, batches[0].logRecords.size)
        assertEquals(0, queue.pendingCount)

        queue.close()
    }

    // =========================================================================
    // Generic v2 event queue
    // =========================================================================

    @Test
    fun `enqueueV2Event adds to v2 queue`() {
        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = null,
            dispatcher = testDispatcher,
        )

        assertEquals(0, queue.pendingV2Count)

        val event = TelemetryV2Event(
            name = "inference.started",
            timestamp = "2024-02-15T10:00:00.000Z",
            attributes = TelemetryAttributes.of("model.id" to "test"),
        )
        queue.enqueueV2Event(event)

        assertEquals(1, queue.pendingV2Count)
        assertEquals(0, queue.pendingCount)

        queue.close()
    }

    @Test
    fun `generic v2 events batch correctly with inference events`() = runTest(testDispatcher) {
        val batches = mutableListOf<ExportLogsServiceRequest>()
        val sender = TelemetrySender { batch -> batches.add(batch) }

        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
            dispatcher = testDispatcher,
        )

        // Add inference event
        queue.enqueue(makeEvent(modelId = "m1"))

        // Add generic v2 event (e.g. inference.started)
        val startedEvent = TelemetryV2Event(
            name = "inference.started",
            timestamp = "2024-02-15T10:00:00.000Z",
            attributes = TelemetryAttributes.of("model.id" to "m1"),
        )
        queue.enqueueV2Event(startedEvent)

        // Add training event
        val trainingEvent = TelemetryV2Event(
            name = "training.epoch_completed",
            timestamp = "2024-02-15T10:01:00.000Z",
            attributes = TelemetryAttributes.of(
                "model.id" to "m1",
                "training.loss" to 0.05,
            ),
        )
        queue.enqueueV2Event(trainingEvent)

        queue.flush()

        assertEquals(1, batches.size)
        val records = batches[0].logRecords
        assertEquals(3, records.size)
        // First: converted inference event
        assertEquals("inference.completed", records[0].eventName)
        // Second and third: generic v2 events
        assertEquals("inference.started", records[1].eventName)
        assertEquals("training.epoch_completed", records[2].eventName)

        queue.close()
    }

    @Test
    fun `v2 events trigger auto-flush when combined count reaches batch size`() = runTest(testDispatcher) {
        val batches = mutableListOf<ExportLogsServiceRequest>()
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
        queue.enqueueV2Event(
            TelemetryV2Event(
                name = "inference.started",
                timestamp = "2024-02-15T10:00:00.000Z",
            ),
        ) // combined count = 3, should trigger flush

        advanceUntilIdle()

        assertEquals(1, batches.size)
        assertEquals(3, batches[0].logRecords.size)

        queue.close()
    }

    // =========================================================================
    // OTLP attributes use typed AnyValue
    // =========================================================================

    @Test
    fun `OTLP attributes use typed AnyValue not string`() = runTest(testDispatcher) {
        val batches = mutableListOf<ExportLogsServiceRequest>()
        val sender = TelemetrySender { batch -> batches.add(batch) }

        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
            dispatcher = testDispatcher,
        )

        queue.enqueue(makeEvent(latencyMs = 12.5))
        queue.flush()

        val record = batches[0].logRecords[0]
        val attrs = record.attributes ?: emptyList()

        // Duration should be a DoubleValue
        val durationKv = attrs.firstOrNull { it.key == "inference.duration_ms" }
        assertNotNull(durationKv)
        assertTrue(durationKv.value is AnyValue.DoubleValue, "duration_ms should be DoubleValue")
        assertEquals(12.5, (durationKv.value as AnyValue.DoubleValue).doubleValue)

        // Success should be a BoolValue
        val successKv = attrs.firstOrNull { it.key == "inference.success" }
        assertNotNull(successKv)
        assertTrue(successKv.value is AnyValue.BoolValue, "success should be BoolValue")

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

    @Test
    fun `close persists both inference and v2 events`() {
        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = tmpDir,
            sender = null,
            dispatcher = testDispatcher,
        )

        queue.enqueue(makeEvent())
        queue.enqueueV2Event(
            TelemetryV2Event(
                name = "training.started",
                timestamp = "2024-02-15T10:00:00.000Z",
            ),
        )

        queue.close()

        val files = tmpDir.listFiles()?.filter { it.extension == "json" }
        assertTrue(files != null && files.isNotEmpty())
    }

    // =========================================================================
    // Persisted v2 event recovery
    // =========================================================================

    @Test
    fun `persisted v2 events restore and resend`() = runTest(testDispatcher) {
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
        val batches = mutableListOf<ExportLogsServiceRequest>()
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

        // Persisted events should have been resent as OTLP batch
        assertEquals(1, batches.size)
        assertTrue(batches[0].logRecords.isNotEmpty())

        // Persisted files should be cleaned up
        val filesAfter = tmpDir.listFiles()?.filter { it.extension == "json" }
        assertTrue(filesAfter == null || filesAfter.isEmpty())

        queue2.close()
    }

    @Test
    fun `old format persisted files are discarded gracefully`() = runTest(testDispatcher) {
        // Write a file in the old InferenceTelemetryEvent format
        val oldFormatJson = """[{"modelId":"m1","latencyMs":5.0,"timestampMs":1708000000000,"success":true}]"""
        val oldFile = File(tmpDir, "telemetry_old.json")
        oldFile.writeText(oldFormatJson)

        val batches = mutableListOf<ExportLogsServiceRequest>()
        val sender = TelemetrySender { batch -> batches.add(batch) }

        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = tmpDir,
            sender = sender,
            dispatcher = testDispatcher,
        )
        queue.start()
        advanceUntilIdle()

        // Old file should be discarded (deleted), not crash
        assertTrue(!oldFile.exists(), "Old format file should be discarded")

        queue.close()
    }

    // =========================================================================
    // Training events route through v2
    // =========================================================================

    @Test
    fun `training events can be enqueued as v2 events`() = runTest(testDispatcher) {
        val batches = mutableListOf<ExportLogsServiceRequest>()
        val sender = TelemetrySender { batch -> batches.add(batch) }

        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
            dispatcher = testDispatcher,
        )

        val trainingEvent = TelemetryV2Event(
            name = "training.epoch_completed",
            timestamp = "2024-02-15T10:00:00.000Z",
            attributes = TelemetryAttributes.of(
                "model.id" to "classifier",
                "training.loss" to 0.05,
                "training.accuracy" to 0.98,
            ),
        )
        queue.enqueueV2Event(trainingEvent)
        queue.flush()

        assertEquals(1, batches.size)
        val record = batches[0].logRecords[0]
        assertEquals("training.epoch_completed", record.eventName)
        assertEquals("classifier", record.attrString("model.id"))

        queue.close()
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
        assertEquals(JsonPrimitive("m1"), e1.attributes["model.id"])
        assertEquals(JsonPrimitive(10.0), e1.attributes["inference.duration_ms"])
        assertEquals(JsonPrimitive("tflite"), e1.attributes["model.format"])
        assertEquals(JsonPrimitive(true), e1.attributes["inference.success"])
        assertTrue(e1.attributes["error.message"] == null)

        val e2 = batch.events[1]
        assertEquals("inference.failed", e2.name)
        assertEquals(JsonPrimitive("m2"), e2.attributes["model.id"])
        assertEquals(JsonPrimitive(false), e2.attributes["inference.success"])
        assertEquals(JsonPrimitive("timeout"), e2.attributes["error.message"])

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
        assertEquals(JsonPrimitive(true), event.attributes["funnel.success"])
        assertEquals(JsonPrimitive("sdk_android"), event.attributes["funnel.source"])
        assertEquals(JsonPrimitive("model-abc"), event.attributes["model.id"])
        assertEquals(JsonPrimitive("rollout-1"), event.attributes["funnel.rollout_id"])
        assertEquals(JsonPrimitive("sess-1"), event.attributes["funnel.session_id"])
        assertEquals(JsonPrimitive(500), event.attributes["funnel.duration_ms"])
        assertEquals(JsonPrimitive("val1"), event.attributes["funnel.metadata.key1"])
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
        assertEquals(JsonPrimitive(false), attrs["funnel.success"])
        assertEquals(JsonPrimitive("HTTP 500"), attrs["error.message"])
        assertEquals(JsonPrimitive("server_error"), attrs["error.category"])

        queue.close()
    }

    // =========================================================================
    // Single endpoint: both inference and funnel use sendBatch
    // =========================================================================

    @Test
    fun `reportFunnelEvent sends v2 batch through sender`() = runTest(testDispatcher) {
        val batches = mutableListOf<ExportLogsServiceRequest>()
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
        assertEquals("funnel.benchmark_completed", batches[0].logRecords[0].eventName)
        assertEquals("org-funnel", batches[0].resourceAttr("org.id"))

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
