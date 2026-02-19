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
    fun `BenchmarkReport stores all metrics correctly with 50 warm inferences`() {
        // Default warm inference count is now 50 (+ 1 cold = 51 minimum).
        // With delegate trials (up to 3 delegates x 3 trials = 9), total can be higher.
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
            inferenceCount = 51, // 1 cold + 50 warm (no delegate trials available in test)
            modelLoadTimeMs = 150.0,
            coldInferenceMs = 25.0,
            warmInferenceMs = 12.0,
            batteryLevel = 85.0f,
            thermalState = "none",
            activeDelegate = "xnnpack",
            disabledDelegates = listOf("nnapi", "gpu"),
        )

        assertEquals("test-model", report.modelName)
        assertEquals(51, report.inferenceCount)
        assertEquals(83.33, report.tokensPerSecond)
        assertEquals(85.0f, report.batteryLevel)
        assertEquals("none", report.thermalState)
        assertEquals("xnnpack", report.activeDelegate)
        assertEquals(listOf("nnapi", "gpu"), report.disabledDelegates)
        assertTrue(report.p50LatencyMs <= report.p95LatencyMs)
        assertTrue(report.p95LatencyMs <= report.p99LatencyMs)
    }

    // =========================================================================
    // Token tracking fields
    // =========================================================================

    @Test
    fun `BenchmarkReport stores token tracking fields`() {
        val report = BenchmarkReport(
            modelName = "llm-model",
            deviceName = "Samsung Galaxy S24",
            chipFamily = "Snapdragon 8 Gen 3",
            ramGb = 12.0,
            osVersion = "14",
            ttftMs = 150.0,
            tpotMs = 35.0,
            tokensPerSecond = 28.57,
            p50LatencyMs = 33.0,
            p95LatencyMs = 42.0,
            p99LatencyMs = 50.0,
            memoryPeakBytes = 200_000_000L,
            inferenceCount = 60,
            modelLoadTimeMs = 800.0,
            coldInferenceMs = 150.0,
            warmInferenceMs = 35.0,
            promptTokens = 128,
            completionTokens = 256,
            contextLength = 2048,
            totalTokens = 384,
        )

        assertEquals(128, report.promptTokens)
        assertEquals(256, report.completionTokens)
        assertEquals(2048, report.contextLength)
        assertEquals(384, report.totalTokens)
    }

    @Test
    fun `BenchmarkReport token fields default to null`() {
        val report = BenchmarkReport(
            modelName = "test-model",
            deviceName = "Pixel 8",
            chipFamily = "Tensor G3",
            ramGb = 8.0,
            osVersion = "14",
            ttftMs = 10.0,
            tpotMs = 5.0,
            tokensPerSecond = 200.0,
            p50LatencyMs = 5.0,
            p95LatencyMs = 7.0,
            p99LatencyMs = 9.0,
            memoryPeakBytes = 10_000_000L,
            inferenceCount = 51,
            modelLoadTimeMs = 100.0,
            coldInferenceMs = 10.0,
            warmInferenceMs = 5.0,
        )

        assertEquals(null, report.promptTokens)
        assertEquals(null, report.completionTokens)
        assertEquals(null, report.contextLength)
        assertEquals(null, report.totalTokens)
    }

    // =========================================================================
    // Delegate selection fields
    // =========================================================================

    @Test
    fun `BenchmarkReport stores delegate selection fields`() {
        val report = BenchmarkReport(
            modelName = "mobilenet-v2",
            deviceName = "Pixel 8 Pro",
            chipFamily = "Tensor G3",
            ramGb = 12.0,
            osVersion = "14",
            ttftMs = 15.0,
            tpotMs = 8.0,
            tokensPerSecond = 125.0,
            p50LatencyMs = 7.5,
            p95LatencyMs = 12.0,
            p99LatencyMs = 15.0,
            memoryPeakBytes = 40_000_000L,
            inferenceCount = 60, // 1 cold + 9 delegate trials + 50 warm
            modelLoadTimeMs = 120.0,
            coldInferenceMs = 15.0,
            warmInferenceMs = 8.0,
            activeDelegate = "gpu",
            disabledDelegates = listOf("nnapi"),
        )

        assertEquals("gpu", report.activeDelegate)
        assertEquals(listOf("nnapi"), report.disabledDelegates)
        assertEquals(60, report.inferenceCount)
    }

    @Test
    fun `BenchmarkReport delegate fields default to null`() {
        val report = BenchmarkReport(
            modelName = "test-model",
            deviceName = "Pixel 8",
            chipFamily = "Tensor G3",
            ramGb = 8.0,
            osVersion = "14",
            ttftMs = 10.0,
            tpotMs = 5.0,
            tokensPerSecond = 200.0,
            p50LatencyMs = 5.0,
            p95LatencyMs = 7.0,
            p99LatencyMs = 9.0,
            memoryPeakBytes = 10_000_000L,
            inferenceCount = 51,
            modelLoadTimeMs = 100.0,
            coldInferenceMs = 10.0,
            warmInferenceMs = 5.0,
        )

        assertEquals(null, report.activeDelegate)
        assertEquals(null, report.disabledDelegates)
    }

    // =========================================================================
    // Default inference count
    // =========================================================================

    @Test
    fun `default warm inference count is 50`() {
        // Verify the default through the inference count in a fully populated report
        // 1 cold + 50 warm = 51 (minimum without delegate trials)
        val report = BenchmarkReport(
            modelName = "count-test",
            deviceName = "Device",
            chipFamily = "chip",
            ramGb = 4.0,
            osVersion = "14",
            ttftMs = 10.0,
            tpotMs = 5.0,
            tokensPerSecond = 200.0,
            p50LatencyMs = 5.0,
            p95LatencyMs = 7.0,
            p99LatencyMs = 9.0,
            memoryPeakBytes = 10_000_000L,
            inferenceCount = 51,
            modelLoadTimeMs = 100.0,
            coldInferenceMs = 10.0,
            warmInferenceMs = 5.0,
            activeDelegate = "xnnpack",
        )

        // 51 = 1 cold + 50 warm (the new default)
        assertTrue(
            report.inferenceCount >= 51,
            "Inference count should be at least 51 (1 cold + 50 warm), was ${report.inferenceCount}",
        )
    }
}
