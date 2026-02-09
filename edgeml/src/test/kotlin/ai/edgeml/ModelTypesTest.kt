package ai.edgeml.models

import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for model types.
 */
class ModelTypesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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

    // =========================================================================
    // InferenceOutput - additional
    // =========================================================================

    @Test
    fun `InferenceOutput argmax returns -1 for empty data`() {
        val output = InferenceOutput(
            data = floatArrayOf(),
            shape = intArrayOf(0),
            inferenceTimeMs = 0,
        )
        assertEquals(-1, output.argmax())
    }

    @Test
    fun `InferenceOutput argmax returns 0 for single element`() {
        val output = InferenceOutput(
            data = floatArrayOf(0.5f),
            shape = intArrayOf(1),
            inferenceTimeMs = 1,
        )
        assertEquals(0, output.argmax())
    }

    @Test
    fun `InferenceOutput topK with k greater than size returns all elements`() {
        val output = InferenceOutput(
            data = floatArrayOf(0.3f, 0.7f),
            shape = intArrayOf(2),
            inferenceTimeMs = 1,
        )
        val topK = output.topK(10)
        assertEquals(2, topK.size)
    }

    @Test
    fun `InferenceOutput equality compares data by content`() {
        val output1 = InferenceOutput(floatArrayOf(1f, 2f), intArrayOf(2), 10)
        val output2 = InferenceOutput(floatArrayOf(1f, 2f), intArrayOf(2), 10)

        assertEquals(output1, output2)
        assertEquals(output1.hashCode(), output2.hashCode())
    }

    @Test
    fun `InferenceOutput not equal when data differs`() {
        val output1 = InferenceOutput(floatArrayOf(1f, 2f), intArrayOf(2), 10)
        val output2 = InferenceOutput(floatArrayOf(3f, 4f), intArrayOf(2), 10)

        assertNotEquals(output1, output2)
    }

    @Test
    fun `InferenceOutput not equal when inferenceTimeMs differs`() {
        val output1 = InferenceOutput(floatArrayOf(1f), intArrayOf(1), 10)
        val output2 = InferenceOutput(floatArrayOf(1f), intArrayOf(1), 20)

        assertNotEquals(output1, output2)
    }

    // =========================================================================
    // InferenceInput
    // =========================================================================

    @Test
    fun `InferenceInput equality compares data by content`() {
        val input1 = InferenceInput(floatArrayOf(1f, 2f), intArrayOf(2))
        val input2 = InferenceInput(floatArrayOf(1f, 2f), intArrayOf(2))

        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `InferenceInput not equal when data differs`() {
        val input1 = InferenceInput(floatArrayOf(1f, 2f), intArrayOf(2))
        val input2 = InferenceInput(floatArrayOf(3f, 4f), intArrayOf(2))

        assertNotEquals(input1, input2)
    }

    @Test
    fun `InferenceInput not equal when shape differs`() {
        val input1 = InferenceInput(floatArrayOf(1f, 2f), intArrayOf(2))
        val input2 = InferenceInput(floatArrayOf(1f, 2f), intArrayOf(1, 2))

        assertNotEquals(input1, input2)
    }

    @Test
    fun `InferenceInput with name field`() {
        val input = InferenceInput(floatArrayOf(1f), intArrayOf(1), "input_0")
        assertEquals("input_0", input.name)
    }

    @Test
    fun `InferenceInput not equal when name differs`() {
        val input1 = InferenceInput(floatArrayOf(1f), intArrayOf(1), "a")
        val input2 = InferenceInput(floatArrayOf(1f), intArrayOf(1), "b")

        assertNotEquals(input1, input2)
    }

    // =========================================================================
    // DownloadProgress - additional
    // =========================================================================

    @Test
    fun `DownloadProgress at 100 percent`() {
        val progress = DownloadProgress("m1", "1.0", 1000, 1000)
        assertEquals(100, progress.progress)
    }

    @Test
    fun `DownloadProgress at 0 percent`() {
        val progress = DownloadProgress("m1", "1.0", 0, 1000)
        assertEquals(0, progress.progress)
    }

    @Test
    fun `DownloadProgress stores model metadata`() {
        val progress = DownloadProgress("model-abc", "2.1.0", 250, 500)
        assertEquals("model-abc", progress.modelId)
        assertEquals("2.1.0", progress.version)
        assertEquals(250L, progress.bytesDownloaded)
        assertEquals(500L, progress.totalBytes)
    }

    // =========================================================================
    // CacheStats - additional
    // =========================================================================

    @Test
    fun `CacheStats zero limit results in zero usage percent`() {
        val stats = CacheStats(0, 0, 0, null, emptyList())
        assertEquals(0, stats.usagePercent)
    }

    @Test
    fun `CacheStats availableBytes does not go negative`() {
        val stats = CacheStats(
            modelCount = 1,
            totalSizeBytes = 200,
            cacheSizeLimitBytes = 100,
            mostRecentModel = null,
            models = emptyList(),
        )
        assertEquals(0, stats.availableBytes)
    }

    @Test
    fun `CacheStats with 100 percent usage`() {
        val stats = CacheStats(
            modelCount = 1,
            totalSizeBytes = 100 * 1024 * 1024L,
            cacheSizeLimitBytes = 100 * 1024 * 1024L,
            mostRecentModel = null,
            models = emptyList(),
        )
        assertEquals(100, stats.usagePercent)
        assertEquals(0, stats.availableBytes)
    }

    // =========================================================================
    // CachedModel
    // =========================================================================

    @Test
    fun `CachedModel exists returns true when file exists`() {
        val file = tempFolder.newFile("model.tflite")
        val model = CachedModel(
            modelId = "m1",
            version = "1.0",
            filePath = file.absolutePath,
            checksum = "abc",
            sizeBytes = 100,
            format = "tensorflow_lite",
            downloadedAt = 1000L,
            verified = true,
        )

        assertTrue(model.exists())
    }

    @Test
    fun `CachedModel exists returns false when file missing`() {
        val model = CachedModel(
            modelId = "m1",
            version = "1.0",
            filePath = "/nonexistent/model.tflite",
            checksum = "abc",
            sizeBytes = 100,
            format = "tensorflow_lite",
            downloadedAt = 1000L,
        )

        assertFalse(model.exists())
    }

    @Test
    fun `CachedModel isValid requires file exists and verified`() {
        val file = tempFolder.newFile("valid.tflite")
        val validModel = CachedModel("m1", "1.0", file.absolutePath, "abc", 100, "tflite", 1000L, verified = true)
        assertTrue(validModel.isValid())

        val unverifiedModel = CachedModel("m1", "1.0", file.absolutePath, "abc", 100, "tflite", 1000L, verified = false)
        assertFalse(unverifiedModel.isValid())

        val missingModel = CachedModel("m1", "1.0", "/missing.tflite", "abc", 100, "tflite", 1000L, verified = true)
        assertFalse(missingModel.isValid())
    }

    @Test
    fun `CachedModel getFile returns correct File`() {
        val model = CachedModel("m1", "1.0", "/some/path/model.tflite", "abc", 100, "tflite", 1000L)
        assertEquals("/some/path/model.tflite", model.getFile().absolutePath)
    }

    @Test
    fun `CachedModel lastUsedAt defaults to downloadedAt`() {
        val model = CachedModel("m1", "1.0", "/path", "abc", 100, "tflite", 5000L)
        assertEquals(5000L, model.lastUsedAt)
    }

    // =========================================================================
    // DownloadState
    // =========================================================================

    @Test
    fun `DownloadState Idle is a singleton`() {
        assertEquals(DownloadState.Idle, DownloadState.Idle)
    }

    @Test
    fun `DownloadState CheckingForUpdates is a singleton`() {
        assertEquals(DownloadState.CheckingForUpdates, DownloadState.CheckingForUpdates)
    }

    @Test
    fun `DownloadState Verifying is a singleton`() {
        assertEquals(DownloadState.Verifying, DownloadState.Verifying)
    }

    @Test
    fun `DownloadState Downloading carries progress`() {
        val progress = DownloadProgress("m1", "1.0", 500, 1000)
        val state = DownloadState.Downloading(progress)
        assertEquals(progress, state.progress)
        assertEquals(50, state.progress.progress)
    }

    @Test
    fun `DownloadState Failed carries exception`() {
        val error = ModelDownloadException("fail", errorCode = ModelDownloadException.ErrorCode.NETWORK_ERROR)
        val state = DownloadState.Failed(error)
        assertEquals(error, state.error)
        assertEquals(ModelDownloadException.ErrorCode.NETWORK_ERROR, state.error.errorCode)
    }

    // =========================================================================
    // ModelDownloadException
    // =========================================================================

    @Test
    fun `ModelDownloadException carries error code`() {
        val exception = ModelDownloadException(
            "Checksum mismatch",
            errorCode = ModelDownloadException.ErrorCode.CHECKSUM_MISMATCH,
        )
        assertEquals("Checksum mismatch", exception.message)
        assertEquals(ModelDownloadException.ErrorCode.CHECKSUM_MISMATCH, exception.errorCode)
    }

    @Test
    fun `ModelDownloadException defaults to UNKNOWN error code`() {
        val exception = ModelDownloadException("something went wrong")
        assertEquals(ModelDownloadException.ErrorCode.UNKNOWN, exception.errorCode)
    }

    @Test
    fun `ModelDownloadException preserves cause`() {
        val cause = RuntimeException("disk full")
        val exception = ModelDownloadException("IO error", cause = cause, errorCode = ModelDownloadException.ErrorCode.IO_ERROR)
        assertEquals(cause, exception.cause)
    }

    @Test
    fun `ModelDownloadException ErrorCode has all expected values`() {
        val codes = ModelDownloadException.ErrorCode.entries
        assertEquals(8, codes.size)
        assertTrue(codes.contains(ModelDownloadException.ErrorCode.NETWORK_ERROR))
        assertTrue(codes.contains(ModelDownloadException.ErrorCode.NOT_FOUND))
        assertTrue(codes.contains(ModelDownloadException.ErrorCode.CHECKSUM_MISMATCH))
        assertTrue(codes.contains(ModelDownloadException.ErrorCode.INSUFFICIENT_STORAGE))
        assertTrue(codes.contains(ModelDownloadException.ErrorCode.IO_ERROR))
        assertTrue(codes.contains(ModelDownloadException.ErrorCode.UNAUTHORIZED))
        assertTrue(codes.contains(ModelDownloadException.ErrorCode.SERVER_ERROR))
        assertTrue(codes.contains(ModelDownloadException.ErrorCode.UNKNOWN))
    }
}
