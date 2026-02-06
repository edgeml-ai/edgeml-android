package ai.edgeml.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for model types.
 */
class ModelTypesTest {

    @Test
    fun `InferenceOutput argmax returns correct index`() {
        val output = InferenceOutput(
            data = floatArrayOf(0.1f, 0.3f, 0.6f, 0.0f),
            shape = intArrayOf(4),
            inferenceTimeMs = 10,
        )

        assertEquals(2, output.argmax())
    }

    @Test
    fun `InferenceOutput topK returns correct results`() {
        val output = InferenceOutput(
            data = floatArrayOf(0.1f, 0.6f, 0.2f, 0.1f),
            shape = intArrayOf(4),
            inferenceTimeMs = 10,
        )

        val topK = output.topK(2)
        assertEquals(2, topK.size)
        assertEquals(1, topK[0].first)
        assertEquals(0.6f, topK[0].second)
        assertEquals(2, topK[1].first)
        assertEquals(0.2f, topK[1].second)
    }

    @Test
    fun `DownloadProgress calculates percentage correctly`() {
        val progress = DownloadProgress(
            modelId = "model-1",
            version = "1.0.0",
            bytesDownloaded = 500,
            totalBytes = 1000,
        )

        assertEquals(50, progress.progress)
    }

    @Test
    fun `DownloadProgress handles zero total bytes`() {
        val progress = DownloadProgress(
            modelId = "model-1",
            version = "1.0.0",
            bytesDownloaded = 500,
            totalBytes = 0,
        )

        assertEquals(0, progress.progress)
    }

    @Test
    fun `CacheStats calculates usage correctly`() {
        val stats = CacheStats(
            modelCount = 2,
            totalSizeBytes = 50 * 1024 * 1024L,
            cacheSizeLimitBytes = 100 * 1024 * 1024L,
            mostRecentModel = null,
            models = emptyList(),
        )

        assertEquals(50, stats.usagePercent)
        assertEquals(50 * 1024 * 1024L, stats.availableBytes)
    }
}
