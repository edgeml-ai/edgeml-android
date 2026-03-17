package ai.octomil.models

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OctomilModelsTest {

    private lateinit var modelManager: ModelManager
    private lateinit var models: OctomilModels

    private fun cachedModel(
        modelId: String = "test-model",
        version: String = "1.0.0",
        valid: Boolean = true,
    ): CachedModel {
        val tmpFile = kotlin.io.path.createTempFile("model", ".tflite").toFile()
        if (!valid) tmpFile.delete()
        return CachedModel(
            modelId = modelId,
            version = version,
            filePath = tmpFile.absolutePath,
            checksum = "abc123",
            sizeBytes = 1024,
            format = "tensorflow_lite",
            downloadedAt = System.currentTimeMillis(),
            verified = valid,
        )
    }

    @Before
    fun setUp() {
        modelManager = mockk(relaxed = true)
        models = OctomilModels(modelManager)
    }

    // =========================================================================
    // status()
    // =========================================================================

    @Test
    fun `status returns NOT_CACHED when idle and no cached model`() {
        every { modelManager.currentDownloadState } returns DownloadState.Idle
        every { modelManager.hasCachedModel("my-model") } returns false

        val status = models.status("my-model")

        assertEquals(ModelStatus.NOT_CACHED, status)
    }

    @Test
    fun `status returns READY when idle and model is cached`() {
        every { modelManager.currentDownloadState } returns DownloadState.Idle
        every { modelManager.hasCachedModel("my-model") } returns true

        val status = models.status("my-model")

        assertEquals(ModelStatus.READY, status)
    }

    @Test
    fun `status returns DOWNLOADING when model is actively downloading`() {
        val progress = DownloadProgress(
            modelId = "my-model",
            version = "1.0.0",
            bytesDownloaded = 500,
            totalBytes = 1000,
        )
        every { modelManager.currentDownloadState } returns DownloadState.Downloading(progress)

        val status = models.status("my-model")

        assertEquals(ModelStatus.DOWNLOADING, status)
    }

    @Test
    fun `status returns cache status when different model is downloading`() {
        val progress = DownloadProgress(
            modelId = "other-model",
            version = "2.0.0",
            bytesDownloaded = 500,
            totalBytes = 1000,
        )
        every { modelManager.currentDownloadState } returns DownloadState.Downloading(progress)
        every { modelManager.hasCachedModel("my-model") } returns true

        val status = models.status("my-model")

        assertEquals(ModelStatus.READY, status)
    }

    @Test
    fun `status returns DOWNLOADING when verifying`() {
        every { modelManager.currentDownloadState } returns DownloadState.Verifying

        val status = models.status("my-model")

        assertEquals(ModelStatus.DOWNLOADING, status)
    }

    @Test
    fun `status returns ERROR when download failed`() {
        val error = ModelDownloadException("failed", downloadErrorCode = ModelDownloadException.ErrorCode.NETWORK_ERROR)
        every { modelManager.currentDownloadState } returns DownloadState.Failed(error)

        val status = models.status("my-model")

        assertEquals(ModelStatus.FAILED, status)
    }

    @Test
    fun `status returns READY when download completed for this model`() {
        val model = cachedModel(modelId = "my-model")
        every { modelManager.currentDownloadState } returns DownloadState.Completed(model)

        val status = models.status("my-model")

        assertEquals(ModelStatus.READY, status)
    }

    @Test
    fun `status returns READY when model is up to date`() {
        val model = cachedModel(modelId = "my-model")
        every { modelManager.currentDownloadState } returns DownloadState.UpToDate(model)

        val status = models.status("my-model")

        assertEquals(ModelStatus.READY, status)
    }

    @Test
    fun `status falls through to cache when completed model is different`() {
        val model = cachedModel(modelId = "other-model")
        every { modelManager.currentDownloadState } returns DownloadState.Completed(model)
        every { modelManager.hasCachedModel("my-model") } returns false

        val status = models.status("my-model")

        assertEquals(ModelStatus.NOT_CACHED, status)
    }

    // =========================================================================
    // load()
    // =========================================================================

    @Test
    fun `load returns cached model when already available`() = runTest {
        val model = cachedModel()
        coEvery { modelManager.getCachedModel("test-model", null) } returns model

        val result = models.load("test-model")

        assertEquals("test-model", result.modelId)
        assertEquals("1.0.0", result.version)
        coVerify { modelManager.touchModel("test-model", "1.0.0") }
    }

    @Test
    fun `load downloads when not cached`() = runTest {
        val model = cachedModel()
        coEvery { modelManager.getCachedModel("test-model", null) } returns null
        coEvery { modelManager.ensureModelAvailable(modelId = "test-model") } returns Result.success(model)

        val result = models.load("test-model")

        assertEquals("test-model", result.modelId)
        coVerify { modelManager.ensureModelAvailable(modelId = "test-model") }
    }

    @Test
    fun `load with specific version checks cache first`() = runTest {
        val model = cachedModel(version = "2.0.0")
        coEvery { modelManager.getCachedModel("test-model", "2.0.0") } returns model

        val result = models.load("test-model", version = "2.0.0")

        assertEquals("2.0.0", result.version)
        coVerify(exactly = 0) { modelManager.ensureModelAvailable(any(), any()) }
    }

    @Test(expected = ModelDownloadException::class)
    fun `load throws when download fails`() = runTest {
        coEvery { modelManager.getCachedModel("test-model", null) } returns null
        coEvery { modelManager.ensureModelAvailable(modelId = "test-model") } returns Result.failure(
            ModelDownloadException("failed", downloadErrorCode = ModelDownloadException.ErrorCode.NETWORK_ERROR),
        )

        models.load("test-model")
    }

    // =========================================================================
    // unload()
    // =========================================================================

    @Test
    fun `unload removes model from loaded set`() = runTest {
        val model = cachedModel()
        coEvery { modelManager.getCachedModel("test-model", null) } returns model

        models.load("test-model")
        models.unload("test-model")

        // No exception, unload is fire-and-forget
    }

    @Test
    fun `unload does not throw for model that was never loaded`() {
        // Should not throw
        models.unload("nonexistent-model")
    }

    // =========================================================================
    // list()
    // =========================================================================

    @Test
    fun `list returns all cached models`() = runTest {
        val model1 = cachedModel(modelId = "model-a")
        val model2 = cachedModel(modelId = "model-b")
        val stats = CacheStats(
            modelCount = 2,
            totalSizeBytes = 2048,
            cacheSizeLimitBytes = 10 * 1024 * 1024,
            mostRecentModel = model2,
            models = listOf(model1, model2),
        )
        coEvery { modelManager.getCacheStats() } returns stats

        val result = models.list()

        assertEquals(2, result.size)
        assertEquals("model-a", result[0].modelId)
        assertEquals("model-b", result[1].modelId)
    }

    @Test
    fun `list returns empty when no models cached`() = runTest {
        val stats = CacheStats(
            modelCount = 0,
            totalSizeBytes = 0,
            cacheSizeLimitBytes = 10 * 1024 * 1024,
            mostRecentModel = null,
            models = emptyList(),
        )
        coEvery { modelManager.getCacheStats() } returns stats

        val result = models.list()

        assertTrue(result.isEmpty())
    }

    // =========================================================================
    // clearCache()
    // =========================================================================

    @Test
    fun `clearCache delegates to modelManager`() = runTest {
        models.clearCache()

        coVerify { modelManager.clearCache() }
    }

    @Test
    fun `clearCache resets loaded models`() = runTest {
        val model = cachedModel()
        coEvery { modelManager.getCachedModel("test-model", null) } returns model
        models.load("test-model")

        models.clearCache()

        coVerify { modelManager.clearCache() }
    }
}
