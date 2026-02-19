package ai.edgeml.pairing

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [BenchmarkRunner] utility functions.
 *
 * Note: Full benchmark execution requires a TFLite model file and runtime,
 * which aren't available in unit tests. These tests cover the pure logic
 * (percentile calculations) and data integrity.
 */
class BenchmarkRunnerTest {

    // =========================================================================
    // Percentile calculation
    // =========================================================================

    @Test
    fun `percentile returns zero for empty list`() {
        assertEquals(0.0, BenchmarkRunner.percentile(emptyList(), 50))
        assertEquals(0.0, BenchmarkRunner.percentile(emptyList(), 95))
        assertEquals(0.0, BenchmarkRunner.percentile(emptyList(), 99))
    }

    @Test
    fun `percentile returns single value for single-element list`() {
        val values = listOf(42.0)
        assertEquals(42.0, values.let { BenchmarkRunner.percentile(it, 50) })
        assertEquals(42.0, values.let { BenchmarkRunner.percentile(it, 95) })
        assertEquals(42.0, values.let { BenchmarkRunner.percentile(it, 99) })
    }

    @Test
    fun `p50 returns median of sorted list`() {
        // For [1, 2, 3, 4, 5], p50 index = 0.5 * 4 = 2.0 → values[2] = 3.0
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(3.0, BenchmarkRunner.percentile(values, 50))
    }

    @Test
    fun `p95 returns near-maximum of sorted list`() {
        val values = (1..100).map { it.toDouble() }
        val p95 = BenchmarkRunner.percentile(values, 95)
        // p95 of 1..100: index = 0.95 * 99 = 94.05
        // Interpolation: values[94] + 0.05 * (values[95] - values[94]) = 95 + 0.05 * 1 = 95.05
        assertTrue(p95 >= 95.0, "p95 should be >= 95.0, was $p95")
        assertTrue(p95 <= 96.0, "p95 should be <= 96.0, was $p95")
    }

    @Test
    fun `p99 returns near-maximum of sorted list`() {
        val values = (1..100).map { it.toDouble() }
        val p99 = BenchmarkRunner.percentile(values, 99)
        assertTrue(p99 >= 99.0, "p99 should be >= 99.0, was $p99")
        assertTrue(p99 <= 100.0, "p99 should be <= 100.0, was $p99")
    }

    @Test
    fun `percentile handles two-element list`() {
        val values = listOf(10.0, 20.0)
        // p50: index = 0.5 * 1 = 0.5 → 10 + 0.5*(20-10) = 15
        assertEquals(15.0, BenchmarkRunner.percentile(values, 50))
        // p0: index = 0.0 * 1 = 0.0 → values[0] = 10
        assertEquals(10.0, BenchmarkRunner.percentile(values, 0))
        // p100: index = 1.0 * 1 = 1.0 → values[1] = 20
        assertEquals(20.0, BenchmarkRunner.percentile(values, 100))
    }

    @Test
    fun `percentile with identical values returns that value`() {
        val values = List(10) { 7.5 }
        assertEquals(7.5, BenchmarkRunner.percentile(values, 50))
        assertEquals(7.5, BenchmarkRunner.percentile(values, 95))
        assertEquals(7.5, BenchmarkRunner.percentile(values, 99))
    }

    // =========================================================================
    // BenchmarkReport data integrity
    // =========================================================================

    @Test
    fun `BenchmarkReport stores all metrics correctly`() {
        val report = BenchmarkReport(
            modelName = "test-model",
            deviceName = "Google Pixel 8",
            chipFamily = "Tensor G3",
            ramGb = 8.0,
            osVersion = "14",
            ttftMs = 25.0,
            tpotMs = 12.0,
            tokensPerSecond = 83.33,
            p50LatencyMs = 11.5,
            p95LatencyMs = 18.0,
            p99LatencyMs = 22.0,
            memoryPeakBytes = 50_000_000L,
            inferenceCount = 11,
            modelLoadTimeMs = 150.0,
            coldInferenceMs = 25.0,
            warmInferenceMs = 12.0,
            batteryLevel = 85.0f,
            thermalState = "none",
        )

        assertEquals("test-model", report.modelName)
        assertEquals(11, report.inferenceCount)
        assertEquals(83.33, report.tokensPerSecond)
        assertEquals(85.0f, report.batteryLevel)
        assertEquals("none", report.thermalState)
        assertTrue(report.p50LatencyMs <= report.p95LatencyMs)
        assertTrue(report.p95LatencyMs <= report.p99LatencyMs)
    }
}
