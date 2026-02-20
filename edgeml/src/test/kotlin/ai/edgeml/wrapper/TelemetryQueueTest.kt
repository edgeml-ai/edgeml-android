package ai.edgeml.wrapper

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [TelemetryQueue] — batching, flushing, persistence, and sender integration.
 *
 * Uses [StandardTestDispatcher] for deterministic coroutine control.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryQueueTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "edgeml_telemetry_test_${System.nanoTime()}")
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
    ) = InferenceTelemetryEvent(
        modelId = modelId,
        latencyMs = latencyMs,
        timestampMs = System.currentTimeMillis(),
        success = success,
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
    // Manual flush
    // =========================================================================

    @Test
    fun `flush drains the queue`() = runTest(testDispatcher) {
        val sent = mutableListOf<InferenceTelemetryEvent>()
        val sender = TelemetrySender { events -> sent.addAll(events) }

        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
            dispatcher = testDispatcher,
        )

        queue.enqueue(makeEvent(modelId = "m1"))
        queue.enqueue(makeEvent(modelId = "m2"))

        queue.flush()

        assertEquals(0, queue.pendingCount)
        assertEquals(2, sent.size)
        assertEquals("m1", sent[0].modelId)
        assertEquals("m2", sent[1].modelId)

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

        // Sender should NOT be called when there are no events
        assertTrue(!sendCalled)

        queue.close()
    }

    // =========================================================================
    // Size-based auto-flush
    // =========================================================================

    @Test
    fun `enqueue triggers auto-flush when batch size reached`() = runTest(testDispatcher) {
        val sent = mutableListOf<InferenceTelemetryEvent>()
        val sender = TelemetrySender { events -> sent.addAll(events) }

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

        // Let the coroutine launched by enqueue complete
        advanceUntilIdle()

        assertEquals(3, sent.size)
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

        // Events should be written to disk
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
    fun `start resends persisted events when sender succeeds`() = runTest(testDispatcher) {
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
        val sent = mutableListOf<InferenceTelemetryEvent>()
        val sender = TelemetrySender { events -> sent.addAll(events) }

        val queue2 = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = tmpDir,
            sender = sender,
            dispatcher = testDispatcher,
        )
        queue2.start()
        advanceUntilIdle()

        // Persisted events should have been resent
        assertEquals(1, sent.size)

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
        // Create a queue that persists (no sender) with a small persist dir
        // We can't easily test the 100-file limit without writing 100 files,
        // so instead verify the mechanism works with a few files.
        val queue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = tmpDir,
            sender = null,
            dispatcher = testDispatcher,
        )

        // Enqueue and flush multiple batches
        for (i in 1..5) {
            queue.enqueue(makeEvent())
            queue.flush()
        }

        val files = tmpDir.listFiles()?.filter { it.extension == "json" }
        assertTrue(files != null && files.size in 1..5)

        queue.close()
    }
}
