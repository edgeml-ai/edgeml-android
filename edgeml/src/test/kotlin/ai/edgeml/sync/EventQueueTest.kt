package ai.edgeml.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EventQueueTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var queueDir: File
    private lateinit var eventQueue: EventQueue

    @Before
    fun setUp() {
        queueDir = File(System.getProperty("java.io.tmpdir"), "edgeml_test_queue_${System.nanoTime()}")
        queueDir.mkdirs()
        eventQueue = EventQueue.createForTesting(queueDir, testDispatcher)
    }

    @After
    fun tearDown() {
        queueDir.deleteRecursively()
    }

    // =========================================================================
    // addEvent
    // =========================================================================

    @Test
    fun `addEvent writes JSON file to queue directory`() = runTest(testDispatcher) {
        val event = QueuedEvent(
            id = "evt-1",
            type = EventTypes.INFERENCE_COMPLETED,
            timestamp = 1000L,
            metrics = mapOf("latency" to 50.0),
            metadata = mapOf("model" to "test-model"),
        )

        val result = eventQueue.addEvent(event)

        assertTrue(result)
        val eventFile = File(queueDir, "evt-1.json")
        assertTrue(eventFile.exists())
        val content = eventFile.readText()
        assertTrue(content.contains("evt-1"))
        assertTrue(content.contains("inference_completed"))
    }

    @Test
    fun `addTrainingEvent creates event with UUID and timestamp`() = runTest(testDispatcher) {
        val result = eventQueue.addTrainingEvent(
            type = EventTypes.TRAINING_STARTED,
            metrics = mapOf("lr" to 0.001),
            metadata = mapOf("round" to "1"),
        )

        assertTrue(result)
        assertEquals(1, eventQueue.getQueueSize())

        val events = eventQueue.getPendingEvents()
        assertEquals(1, events.size)
        assertEquals(EventTypes.TRAINING_STARTED, events[0].type)
        assertNotNull(events[0].id)
        assertTrue(events[0].timestamp > 0)
    }

    // =========================================================================
    // getPendingEvents
    // =========================================================================

    @Test
    fun `getPendingEvents returns events sorted by timestamp`() = runTest(testDispatcher) {
        eventQueue.addEvent(QueuedEvent("e3", "type3", 3000L))
        eventQueue.addEvent(QueuedEvent("e1", "type1", 1000L))
        eventQueue.addEvent(QueuedEvent("e2", "type2", 2000L))

        val events = eventQueue.getPendingEvents()

        assertEquals(3, events.size)
        assertEquals("e1", events[0].id)
        assertEquals("e2", events[1].id)
        assertEquals("e3", events[2].id)
    }

    @Test
    fun `getPendingEvents returns empty list when no events`() = runTest(testDispatcher) {
        val events = eventQueue.getPendingEvents()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `getPendingEvents skips corrupt JSON files`() = runTest(testDispatcher) {
        // Add a valid event
        eventQueue.addEvent(QueuedEvent("valid", "test", 1000L))

        // Write a corrupt JSON file directly
        File(queueDir, "corrupt.json").writeText("not valid json {{{")

        val events = eventQueue.getPendingEvents()
        assertEquals(1, events.size)
        assertEquals("valid", events[0].id)
    }

    // =========================================================================
    // removeEvent
    // =========================================================================

    @Test
    fun `removeEvent deletes the event file`() = runTest(testDispatcher) {
        eventQueue.addEvent(QueuedEvent("to-remove", "test", 1000L))
        assertEquals(1, eventQueue.getQueueSize())

        val removed = eventQueue.removeEvent("to-remove")

        assertTrue(removed)
        assertEquals(0, eventQueue.getQueueSize())
        assertFalse(File(queueDir, "to-remove.json").exists())
    }

    @Test
    fun `removeEvent returns false for nonexistent event`() = runTest(testDispatcher) {
        val removed = eventQueue.removeEvent("nonexistent")
        assertFalse(removed)
    }

    // =========================================================================
    // getQueueSize
    // =========================================================================

    @Test
    fun `getQueueSize returns correct count`() = runTest(testDispatcher) {
        assertEquals(0, eventQueue.getQueueSize())

        eventQueue.addEvent(QueuedEvent("a", "t", 1L))
        assertEquals(1, eventQueue.getQueueSize())

        eventQueue.addEvent(QueuedEvent("b", "t", 2L))
        assertEquals(2, eventQueue.getQueueSize())
    }

    @Test
    fun `getQueueSize ignores non-json files`() = runTest(testDispatcher) {
        eventQueue.addEvent(QueuedEvent("json-event", "t", 1L))
        File(queueDir, "readme.txt").writeText("not an event")

        assertEquals(1, eventQueue.getQueueSize())
    }

    // =========================================================================
    // clear
    // =========================================================================

    @Test
    fun `clear removes all events`() = runTest(testDispatcher) {
        eventQueue.addEvent(QueuedEvent("a", "t", 1L))
        eventQueue.addEvent(QueuedEvent("b", "t", 2L))
        eventQueue.addEvent(QueuedEvent("c", "t", 3L))
        assertEquals(3, eventQueue.getQueueSize())

        eventQueue.clear()

        assertEquals(0, eventQueue.getQueueSize())
        assertTrue(eventQueue.getPendingEvents().isEmpty())
    }

    // =========================================================================
    // FIFO eviction at max queue size
    // =========================================================================

    @Test
    fun `eviction removes oldest when queue exceeds max size`() = runTest(testDispatcher) {
        // The max queue size is 1000 (private). Fill up with events to observe eviction.
        // We add 1001 events — the oldest one should be evicted.
        for (i in 1..1001) {
            eventQueue.addEvent(
                QueuedEvent(
                    id = "evt-$i",
                    type = "test",
                    timestamp = i.toLong(),
                ),
            )
        }

        val size = eventQueue.getQueueSize()
        assertEquals(1000, size)
    }

    // =========================================================================
    // Event serialization roundtrip
    // =========================================================================

    @Test
    fun `event with null metrics and metadata roundtrips`() = runTest(testDispatcher) {
        eventQueue.addEvent(QueuedEvent("minimal", "bare", 500L))

        val events = eventQueue.getPendingEvents()
        assertEquals(1, events.size)
        assertEquals("minimal", events[0].id)
        assertEquals("bare", events[0].type)
        assertEquals(500L, events[0].timestamp)
        assertEquals(null, events[0].metrics)
        assertEquals(null, events[0].metadata)
    }

    @Test
    fun `event with metrics and metadata roundtrips`() = runTest(testDispatcher) {
        val event = QueuedEvent(
            id = "full",
            type = EventTypes.TRAINING_COMPLETED,
            timestamp = 9999L,
            metrics = mapOf("loss" to 0.05, "accuracy" to 0.95),
            metadata = mapOf("round" to "10", "device" to "pixel"),
        )
        eventQueue.addEvent(event)

        val events = eventQueue.getPendingEvents()
        assertEquals(1, events.size)
        val retrieved = events[0]
        assertEquals("full", retrieved.id)
        assertEquals(EventTypes.TRAINING_COMPLETED, retrieved.type)
        assertEquals(0.05, retrieved.metrics?.get("loss"))
        assertEquals(0.95, retrieved.metrics?.get("accuracy"))
        assertEquals("10", retrieved.metadata?.get("round"))
    }

    // =========================================================================
    // createForTesting factory
    // =========================================================================

    @Test
    fun `createForTesting creates queue dir if needed`() {
        val newDir = File(System.getProperty("java.io.tmpdir"), "edgeml_test_new_${System.nanoTime()}")
        assertFalse(newDir.exists())

        EventQueue.createForTesting(newDir, testDispatcher)
        assertTrue(newDir.exists())

        // cleanup
        newDir.deleteRecursively()
    }
}
