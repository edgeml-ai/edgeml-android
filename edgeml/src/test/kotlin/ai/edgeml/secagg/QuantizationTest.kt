package ai.edgeml.secagg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantizationTest {

    // =========================================================================
    // quantize
    // =========================================================================

    @Test
    fun `quantize clips values to clipping range`() {
        val values = listOf(-100.0f, 0.0f, 100.0f)
        val result = Quantization.quantize(values, clippingRange = 3.0f, targetRange = 1000L)

        // -100 clipped to -3 -> 0
        assertEquals(0L, result[0])
        // 0 -> mid-range = 500
        assertEquals(500L, result[1])
        // 100 clipped to +3 -> 1000
        assertEquals(1000L, result[2])
    }

    @Test
    fun `quantize maps boundary values correctly`() {
        val values = listOf(-3.0f, 0.0f, 3.0f)
        val result = Quantization.quantize(values, clippingRange = 3.0f, targetRange = 1000L)

        assertEquals(0L, result[0])
        assertEquals(500L, result[1])
        assertEquals(1000L, result[2])
    }

    @Test
    fun `quantize empty list returns empty`() {
        val result = Quantization.quantize(emptyList(), clippingRange = 3.0f, targetRange = 1000L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `quantize with zero clipping range returns zeros`() {
        val result = Quantization.quantize(listOf(1.0f, 2.0f), clippingRange = 0.0f, targetRange = 1000L)
        assertEquals(listOf(0L, 0L), result)
    }

    @Test
    fun `quantize preserves ordering`() {
        val values = listOf(-2.0f, -1.0f, 0.0f, 1.0f, 2.0f)
        val result = Quantization.quantize(values, clippingRange = 3.0f, targetRange = 65536L)

        for (i in 0 until result.size - 1) {
            assertTrue("Values should be monotonically increasing", result[i] <= result[i + 1])
        }
    }

    @Test
    fun `quantize values in range produce results in target range`() {
        val values = (-10..10).map { it / 10.0f }
        val result = Quantization.quantize(values, clippingRange = 1.0f, targetRange = 65536L)

        for (v in result) {
            assertTrue("Quantized value $v should be >= 0", v >= 0L)
            assertTrue("Quantized value $v should be <= 65536", v <= 65536L)
        }
    }

    @Test
    fun `quantize with large target range`() {
        val values = listOf(-8.0f, 0.0f, 8.0f)
        val targetRange = 4194304L // 2^22
        val result = Quantization.quantize(values, clippingRange = 8.0f, targetRange = targetRange)

        // -8 -> 0, 0 -> ~2^21, 8 -> 2^22
        assertEquals(0L, result[0])
        assertTrue(result[1] in (targetRange / 2 - 1)..(targetRange / 2 + 1))
        assertEquals(targetRange, result[2])
    }

    @Test
    fun `quantize stochastic rounding produces values near expected`() {
        // Run multiple times and check the average is close to expected
        val values = listOf(0.0f)
        val clippingRange = 1.0f
        val targetRange = 100L

        // 0.0 maps to target_range / 2 = 50
        var sum = 0L
        val runs = 1000
        for (i in 0 until runs) {
            val result = Quantization.quantize(values, clippingRange, targetRange)
            sum += result[0]
        }
        val avg = sum.toDouble() / runs
        // Should be close to 50 (within 5 due to stochastic rounding)
        assertTrue("Average $avg should be close to 50", avg in 45.0..55.0)
    }

    // =========================================================================
    // dequantize
    // =========================================================================

    @Test
    fun `dequantize reverses quantize for boundary values`() {
        val clippingRange = 3.0f
        val targetRange = 65536L

        // Exact boundary values have no rounding error
        val quantized = listOf(0L, targetRange / 2, targetRange)
        val recovered = Quantization.dequantize(quantized, clippingRange, targetRange)

        assertEquals(-3.0f, recovered[0], 0.001f)
        assertEquals(0.0f, recovered[1], 0.001f)
        assertEquals(3.0f, recovered[2], 0.001f)
    }

    @Test
    fun `dequantize empty list returns empty`() {
        val result = Quantization.dequantize(emptyList(), clippingRange = 3.0f, targetRange = 1000L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `dequantize with zero target range returns zeros`() {
        val result = Quantization.dequantize(listOf(100L, 200L), clippingRange = 3.0f, targetRange = 0L)
        assertEquals(listOf(0.0f, 0.0f), result)
    }

    @Test
    fun `dequantize output within clipping range`() {
        val clippingRange = 8.0f
        val targetRange = 4194304L
        val quantized = (0L..10L).map { (it * targetRange) / 10 }
        val recovered = Quantization.dequantize(quantized, clippingRange, targetRange)

        for (v in recovered) {
            assertTrue("Value $v should be >= -$clippingRange", v >= -clippingRange)
            assertTrue("Value $v should be <= $clippingRange", v <= clippingRange)
        }
    }

    // =========================================================================
    // Roundtrip
    // =========================================================================

    @Test
    fun `quantize-dequantize roundtrip approximates original`() {
        val clippingRange = 3.0f
        val targetRange = 1L shl 16
        val original = listOf(-2.0f, -1.0f, 0.0f, 1.0f, 2.0f)

        val quantized = Quantization.quantize(original, clippingRange, targetRange)
        val recovered = Quantization.dequantize(quantized, clippingRange, targetRange)

        for (i in original.indices) {
            assertEquals(
                "Value at index $i",
                original[i].toDouble(),
                recovered[i].toDouble(),
                0.01,
            )
        }
    }

    @Test
    fun `quantize-dequantize with server defaults`() {
        val clippingRange = 8.0f
        val targetRange = 4194304L // 2^22

        val original = listOf(-7.5f, -4.0f, 0.0f, 4.0f, 7.5f)
        val quantized = Quantization.quantize(original, clippingRange, targetRange)
        val recovered = Quantization.dequantize(quantized, clippingRange, targetRange)

        for (i in original.indices) {
            assertEquals(
                "Value at index $i",
                original[i].toDouble(),
                recovered[i].toDouble(),
                0.001,
            )
        }
    }

    @Test
    fun `quantize-dequantize clips out-of-range values`() {
        val clippingRange = 3.0f
        val targetRange = 65536L

        val original = listOf(-10.0f, 0.0f, 10.0f)
        val quantized = Quantization.quantize(original, clippingRange, targetRange)
        val recovered = Quantization.dequantize(quantized, clippingRange, targetRange)

        // Out-of-range values get clipped to boundaries
        assertEquals(-3.0f, recovered[0], 0.001f)
        assertEquals(0.0f, recovered[1], 0.001f)
        assertEquals(3.0f, recovered[2], 0.001f)
    }
}
