package ai.octomil.client

import ai.octomil.api.dto.TelemetryV2Event
import ai.octomil.wrapper.TelemetryQueue
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelemetryClientTest {

    @Test
    fun `flush delegates to TelemetryQueue flush`() = runTest {
        val queue = mockk<TelemetryQueue>(relaxed = true)
        val client = TelemetryClient(queue)

        client.flush()

        coVerify { queue.flush() }
    }

    @Test
    fun `flush with null queue does not throw`() = runTest {
        val client = TelemetryClient(null)
        // Should complete without exception
        client.flush()
    }

    @Test
    fun `track emits v2 event with string attribute`() {
        val eventSlot = slot<TelemetryV2Event>()
        val queue = mockk<TelemetryQueue>(relaxed = true)
        every { queue.formatTimestamp(any()) } returns "2026-03-12T21:00:00.000Z"
        every { queue.enqueueV2Event(capture(eventSlot)) } returns Unit

        val client = TelemetryClient(queue)
        client.track("user.action", mapOf("button" to "download"))

        assertTrue(eventSlot.isCaptured)
        assertEquals("user.action", eventSlot.captured.name)
        assertTrue(eventSlot.captured.attributes.containsKey("button"))
        assertEquals("download", eventSlot.captured.attributes["button"]?.content)
    }

    @Test
    fun `track emits v2 event with numeric attribute`() {
        val eventSlot = slot<TelemetryV2Event>()
        val queue = mockk<TelemetryQueue>(relaxed = true)
        every { queue.formatTimestamp(any()) } returns "2026-03-12T21:00:00.000Z"
        every { queue.enqueueV2Event(capture(eventSlot)) } returns Unit

        val client = TelemetryClient(queue)
        client.track("latency.measured", mapOf("value_ms" to 42.5))

        assertTrue(eventSlot.isCaptured)
        assertEquals("latency.measured", eventSlot.captured.name)
        assertEquals("42.5", eventSlot.captured.attributes["value_ms"]?.content)
    }

    @Test
    fun `track emits v2 event with boolean attribute`() {
        val eventSlot = slot<TelemetryV2Event>()
        val queue = mockk<TelemetryQueue>(relaxed = true)
        every { queue.formatTimestamp(any()) } returns "2026-03-12T21:00:00.000Z"
        every { queue.enqueueV2Event(capture(eventSlot)) } returns Unit

        val client = TelemetryClient(queue)
        client.track("feature.flag", mapOf("enabled" to true))

        assertTrue(eventSlot.isCaptured)
        assertEquals("true", eventSlot.captured.attributes["enabled"]?.content)
    }

    @Test
    fun `track with null queue is a no-op`() {
        val client = TelemetryClient(null)
        // Should complete without exception
        client.track("event", mapOf("key" to "val"))
    }

    @Test
    fun `track with empty attributes`() {
        val eventSlot = slot<TelemetryV2Event>()
        val queue = mockk<TelemetryQueue>(relaxed = true)
        every { queue.formatTimestamp(any()) } returns "2026-03-12T21:00:00.000Z"
        every { queue.enqueueV2Event(capture(eventSlot)) } returns Unit

        val client = TelemetryClient(queue)
        client.track("simple.event")

        assertTrue(eventSlot.isCaptured)
        assertEquals("simple.event", eventSlot.captured.name)
        assertTrue(eventSlot.captured.attributes.isEmpty())
    }
}
