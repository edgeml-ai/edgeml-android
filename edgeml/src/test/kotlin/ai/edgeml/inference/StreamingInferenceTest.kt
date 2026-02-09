package ai.edgeml.inference

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StreamingInferenceTest {

    // =========================================================================
    // Modality
    // =========================================================================

    @Test
    fun `Modality value returns lowercase name`() {
        assertEquals("text", Modality.TEXT.value)
        assertEquals("image", Modality.IMAGE.value)
        assertEquals("audio", Modality.AUDIO.value)
        assertEquals("video", Modality.VIDEO.value)
    }

    @Test
    fun `Modality has exactly four values`() {
        assertEquals(4, Modality.entries.size)
    }

    @Test
    fun `Modality valueOf round-trips correctly`() {
        for (modality in Modality.entries) {
            assertEquals(modality, Modality.valueOf(modality.name))
        }
    }

    // =========================================================================
    // InferenceChunk
    // =========================================================================

    @Test
    fun `InferenceChunk stores all fields correctly`() {
        val data = byteArrayOf(1, 2, 3)
        val chunk = InferenceChunk(
            index = 5,
            data = data,
            modality = Modality.TEXT,
            timestamp = 1000L,
            latencyMs = 12.5,
        )

        assertEquals(5, chunk.index)
        assertContentEquals(data, chunk.data)
        assertEquals(Modality.TEXT, chunk.modality)
        assertEquals(1000L, chunk.timestamp)
        assertEquals(12.5, chunk.latencyMs)
    }

    @Test
    fun `InferenceChunk equality compares data by content`() {
        val chunk1 = InferenceChunk(
            index = 0,
            data = byteArrayOf(1, 2, 3),
            modality = Modality.TEXT,
            timestamp = 100L,
            latencyMs = 5.0,
        )
        val chunk2 = InferenceChunk(
            index = 0,
            data = byteArrayOf(1, 2, 3),
            modality = Modality.TEXT,
            timestamp = 100L,
            latencyMs = 5.0,
        )

        assertEquals(chunk1, chunk2)
        assertEquals(chunk1.hashCode(), chunk2.hashCode())
    }

    @Test
    fun `InferenceChunk not equal when data differs`() {
        val chunk1 = InferenceChunk(
            index = 0,
            data = byteArrayOf(1, 2, 3),
            modality = Modality.TEXT,
            timestamp = 100L,
            latencyMs = 5.0,
        )
        val chunk2 = InferenceChunk(
            index = 0,
            data = byteArrayOf(4, 5, 6),
            modality = Modality.TEXT,
            timestamp = 100L,
            latencyMs = 5.0,
        )

        assertNotEquals(chunk1, chunk2)
    }

    @Test
    fun `InferenceChunk not equal when index differs`() {
        val data = byteArrayOf(1, 2, 3)
        val chunk1 = InferenceChunk(0, data, Modality.TEXT, 100L, 5.0)
        val chunk2 = InferenceChunk(1, data, Modality.TEXT, 100L, 5.0)

        assertNotEquals(chunk1, chunk2)
    }

    @Test
    fun `InferenceChunk not equal when modality differs`() {
        val data = byteArrayOf(1, 2, 3)
        val chunk1 = InferenceChunk(0, data, Modality.TEXT, 100L, 5.0)
        val chunk2 = InferenceChunk(0, data, Modality.IMAGE, 100L, 5.0)

        assertNotEquals(chunk1, chunk2)
    }

    // =========================================================================
    // StreamingInferenceResult
    // =========================================================================

    @Test
    fun `StreamingInferenceResult stores all metrics`() {
        val result = StreamingInferenceResult(
            sessionId = "session-1",
            modality = Modality.TEXT,
            ttfcMs = 50.0,
            avgChunkLatencyMs = 10.0,
            totalChunks = 100,
            totalDurationMs = 1000.0,
            throughput = 100.0,
        )

        assertEquals("session-1", result.sessionId)
        assertEquals(Modality.TEXT, result.modality)
        assertEquals(50.0, result.ttfcMs)
        assertEquals(10.0, result.avgChunkLatencyMs)
        assertEquals(100, result.totalChunks)
        assertEquals(1000.0, result.totalDurationMs)
        assertEquals(100.0, result.throughput)
    }

    @Test
    fun `StreamingInferenceResult equality works`() {
        val result1 = StreamingInferenceResult("s1", Modality.AUDIO, 10.0, 5.0, 50, 500.0, 100.0)
        val result2 = StreamingInferenceResult("s1", Modality.AUDIO, 10.0, 5.0, 50, 500.0, 100.0)

        assertEquals(result1, result2)
    }
}
