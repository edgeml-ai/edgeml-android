package ai.edgeml.client

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClientTypesTest {
    // =========================================================================
    // ClientState
    // =========================================================================

    @Test
    fun `ClientState has all expected values`() {
        val states = ClientState.entries
        assertEquals(5, states.size)
        assertTrue(states.contains(ClientState.UNINITIALIZED))
        assertTrue(states.contains(ClientState.INITIALIZING))
        assertTrue(states.contains(ClientState.READY))
        assertTrue(states.contains(ClientState.ERROR))
        assertTrue(states.contains(ClientState.CLOSED))
    }

    @Test
    fun `ClientState valueOf round-trips correctly`() {
        for (state in ClientState.entries) {
            assertEquals(state, ClientState.valueOf(state.name))
        }
    }

    // =========================================================================
    // ModelInfo
    // =========================================================================

    @Test
    fun `ModelInfo stores all fields`() {
        val info =
            ModelInfo(
                modelId = "model-1",
                version = "2.0.0",
                format = "tensorflow_lite",
                sizeBytes = 5_000_000,
                inputShape = intArrayOf(1, 224, 224, 3),
                outputShape = intArrayOf(1, 1000),
                usingGpu = true,
            )

        assertEquals("model-1", info.modelId)
        assertEquals("2.0.0", info.version)
        assertEquals("tensorflow_lite", info.format)
        assertEquals(5_000_000, info.sizeBytes)
        assertEquals(4, info.inputShape.size)
        assertEquals(224, info.inputShape[1])
        assertEquals(1000, info.outputShape[1])
        assertTrue(info.usingGpu)
    }

    @Test
    fun `ModelInfo equality compares shapes by content`() {
        val info1 = ModelInfo("m1", "1.0", "tflite", 100, intArrayOf(1, 28, 28), intArrayOf(1, 10), false)
        val info2 = ModelInfo("m1", "1.0", "tflite", 100, intArrayOf(1, 28, 28), intArrayOf(1, 10), false)

        assertEquals(info1, info2)
        assertEquals(info1.hashCode(), info2.hashCode())
    }

    @Test
    fun `ModelInfo not equal when inputShape differs`() {
        val info1 = ModelInfo("m1", "1.0", "tflite", 100, intArrayOf(1, 28, 28), intArrayOf(1, 10), false)
        val info2 = ModelInfo("m1", "1.0", "tflite", 100, intArrayOf(1, 32, 32), intArrayOf(1, 10), false)

        assertNotEquals(info1, info2)
    }

    @Test
    fun `ModelInfo not equal when outputShape differs`() {
        val info1 = ModelInfo("m1", "1.0", "tflite", 100, intArrayOf(1, 28, 28), intArrayOf(1, 10), false)
        val info2 = ModelInfo("m1", "1.0", "tflite", 100, intArrayOf(1, 28, 28), intArrayOf(1, 5), false)

        assertNotEquals(info1, info2)
    }

    @Test
    fun `ModelInfo not equal when usingGpu differs`() {
        val info1 = ModelInfo("m1", "1.0", "tflite", 100, intArrayOf(1), intArrayOf(1), false)
        val info2 = ModelInfo("m1", "1.0", "tflite", 100, intArrayOf(1), intArrayOf(1), true)

        assertNotEquals(info1, info2)
    }

    @Test
    fun `ModelInfo not equal when version differs`() {
        val info1 = ModelInfo("m1", "1.0", "tflite", 100, intArrayOf(1), intArrayOf(1), false)
        val info2 = ModelInfo("m1", "2.0", "tflite", 100, intArrayOf(1), intArrayOf(1), false)

        assertNotEquals(info1, info2)
    }
}
