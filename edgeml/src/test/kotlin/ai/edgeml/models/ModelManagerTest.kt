package ai.edgeml.models

import ai.edgeml.api.EdgeMLApi
import ai.edgeml.api.dto.ModelDownloadResponse
import ai.edgeml.api.dto.VersionResolutionResponse
import ai.edgeml.storage.SecureStorage
import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var cacheDir: File
    private lateinit var context: Context
    private lateinit var api: EdgeMLApi
    private lateinit var storage: SecureStorage
    private lateinit var modelManager: ModelManager
    private lateinit var config: ai.edgeml.config.EdgeMLConfig

    private fun testConfig(
        modelId: String = "test-model",
        modelCacheSizeBytes: Long = 10 * 1024 * 1024L,
    ) = ai.edgeml.config.EdgeMLConfig(
        serverUrl = "https://test.edgeml.ai",
        deviceAccessToken = "test-token",
        orgId = "test-org",
        modelId = modelId,
        enableBackgroundSync = false,
        enableHeartbeat = false,
        enableGpuAcceleration = false,
        enableEncryptedStorage = false,
        modelCacheSizeBytes = modelCacheSizeBytes,
    )

    @Before
    fun setUp() {
        cacheDir = File(System.getProperty("java.io.tmpdir"), "edgeml_model_test_${System.nanoTime()}")
        cacheDir.mkdirs()

        config = testConfig()

        context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns cacheDir

        api = mockk<EdgeMLApi>()
        storage = mockk<SecureStorage>(relaxed = true)

        coEvery { storage.getServerDeviceId() } returns "device-uuid-123"

        modelManager = ModelManager(context, config, api, storage, testDispatcher)
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    // =========================================================================
    // Cache stats
    // =========================================================================

    @Test
    fun `getCacheStats returns empty when no models cached`() = runTest(testDispatcher) {
        val stats = modelManager.getCacheStats()

        assertEquals(0, stats.modelCount)
        assertEquals(0L, stats.totalSizeBytes)
        assertEquals(config.modelCacheSizeBytes, stats.cacheSizeLimitBytes)
        assertNull(stats.mostRecentModel)
        assertTrue(stats.models.isEmpty())
    }

    // =========================================================================
    // getCachedModel
    // =========================================================================

    @Test
    fun `getCachedModel returns null when nothing cached`() = runTest(testDispatcher) {
        val result = modelManager.getCachedModel()
        assertNull(result)
    }

    // =========================================================================
    // Version resolution errors
    // =========================================================================

    @Test
    fun `ensureModelAvailable fails with UNAUTHORIZED on 401`() = runTest(testDispatcher) {
        coEvery { api.getDeviceVersion(any(), any(), any()) } returns
            Response.error(401, okhttp3.ResponseBody.create(null, ""))

        val result = modelManager.ensureModelAvailable()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as ModelDownloadException
        assertEquals(ModelDownloadException.ErrorCode.UNAUTHORIZED, error.errorCode)
    }

    @Test
    fun `ensureModelAvailable fails with NOT_FOUND on 404`() = runTest(testDispatcher) {
        coEvery { api.getDeviceVersion(any(), any(), any()) } returns
            Response.error(404, okhttp3.ResponseBody.create(null, ""))

        val result = modelManager.ensureModelAvailable()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as ModelDownloadException
        assertEquals(ModelDownloadException.ErrorCode.NOT_FOUND, error.errorCode)
    }

    @Test
    fun `ensureModelAvailable fails with SERVER_ERROR on 500`() = runTest(testDispatcher) {
        coEvery { api.getDeviceVersion(any(), any(), any()) } returns
            Response.error(500, okhttp3.ResponseBody.create(null, ""))

        val result = modelManager.ensureModelAvailable()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as ModelDownloadException
        assertEquals(ModelDownloadException.ErrorCode.SERVER_ERROR, error.errorCode)
    }

    @Test
    fun `ensureModelAvailable fails when device not registered`() = runTest(testDispatcher) {
        coEvery { storage.getServerDeviceId() } returns null

        val result = modelManager.ensureModelAvailable()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as ModelDownloadException
        assertEquals(ModelDownloadException.ErrorCode.UNAUTHORIZED, error.errorCode)
    }

    // =========================================================================
    // Download with MockWebServer
    // =========================================================================

    @Test
    fun `ensureModelAvailable downloads and caches model`() = runTest(testDispatcher) {
        val modelBytes = "fake-model-data-for-testing".toByteArray()
        val checksum = sha256Hex(modelBytes)

        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(modelBytes))
                .addHeader("Content-Length", modelBytes.size.toString()),
        )
        server.start()

        val downloadUrl = server.url("/model.tflite").toString()

        coEvery { api.getDeviceVersion(any(), any(), any()) } returns
            Response.success(VersionResolutionResponse(version = "1.0.0", source = "direct"))

        coEvery { api.getModelDownloadUrl(any(), any(), any()) } returns
            Response.success(
                ModelDownloadResponse(
                    downloadUrl = downloadUrl,
                    expiresAt = "2099-01-01T00:00:00Z",
                    checksum = checksum,
                    sizeBytes = modelBytes.size.toLong(),
                ),
            )

        val result = modelManager.ensureModelAvailable()

        assertTrue(result.isSuccess)
        val cachedModel = result.getOrNull()
        assertNotNull(cachedModel)
        assertEquals("test-model", cachedModel.modelId)
        assertEquals("1.0.0", cachedModel.version)
        assertTrue(cachedModel.verified)
        assertTrue(File(cachedModel.filePath).exists())

        coVerify { storage.setCurrentModelVersion("1.0.0") }
        coVerify { storage.setModelChecksum(checksum) }

        server.shutdown()
    }

    @Test
    fun `ensureModelAvailable returns cached model on second call`() = runTest(testDispatcher) {
        val modelBytes = "cached-model-data".toByteArray()
        val checksum = sha256Hex(modelBytes)

        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(modelBytes))
                .addHeader("Content-Length", modelBytes.size.toString()),
        )
        server.start()

        val downloadUrl = server.url("/model.tflite").toString()

        coEvery { api.getDeviceVersion(any(), any(), any()) } returns
            Response.success(VersionResolutionResponse(version = "1.0.0", source = "direct"))

        coEvery { api.getModelDownloadUrl(any(), any(), any()) } returns
            Response.success(
                ModelDownloadResponse(
                    downloadUrl = downloadUrl,
                    expiresAt = "2099-01-01T00:00:00Z",
                    checksum = checksum,
                    sizeBytes = modelBytes.size.toLong(),
                ),
            )

        // First call downloads
        val result1 = modelManager.ensureModelAvailable()
        assertTrue(result1.isSuccess)

        // Second call should return cached (UpToDate)
        val result2 = modelManager.ensureModelAvailable()
        assertTrue(result2.isSuccess)

        // Only one download URL fetch should have occurred
        coVerify(exactly = 1) { api.getModelDownloadUrl(any(), any(), any()) }

        server.shutdown()
    }

    @Test
    fun `forceDownload re-downloads even when cached`() = runTest(testDispatcher) {
        val modelBytes = "original-model".toByteArray()
        val checksum = sha256Hex(modelBytes)

        val server = MockWebServer()
        // Two downloads expected
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(okio.Buffer().write(modelBytes))
                    .addHeader("Content-Length", modelBytes.size.toString()),
            )
        }
        server.start()

        val downloadUrl = server.url("/model.tflite").toString()

        coEvery { api.getDeviceVersion(any(), any(), any()) } returns
            Response.success(VersionResolutionResponse(version = "1.0.0", source = "direct"))

        coEvery { api.getModelDownloadUrl(any(), any(), any()) } returns
            Response.success(
                ModelDownloadResponse(
                    downloadUrl = downloadUrl,
                    expiresAt = "2099-01-01T00:00:00Z",
                    checksum = checksum,
                    sizeBytes = modelBytes.size.toLong(),
                ),
            )

        modelManager.ensureModelAvailable()
        modelManager.ensureModelAvailable(forceDownload = true)

        coVerify(exactly = 2) { api.getModelDownloadUrl(any(), any(), any()) }

        server.shutdown()
    }

    // =========================================================================
    // Checksum verification
    // =========================================================================

    @Test
    fun `ensureModelAvailable fails on checksum mismatch`() = runTest(testDispatcher) {
        val modelBytes = "model-data".toByteArray()

        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(modelBytes))
                .addHeader("Content-Length", modelBytes.size.toString()),
        )
        server.start()

        coEvery { api.getDeviceVersion(any(), any(), any()) } returns
            Response.success(VersionResolutionResponse(version = "1.0.0", source = "direct"))

        coEvery { api.getModelDownloadUrl(any(), any(), any()) } returns
            Response.success(
                ModelDownloadResponse(
                    downloadUrl = server.url("/model.tflite").toString(),
                    expiresAt = "2099-01-01T00:00:00Z",
                    checksum = "wrong-checksum",
                    sizeBytes = modelBytes.size.toLong(),
                ),
            )

        val result = modelManager.ensureModelAvailable()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as ModelDownloadException
        assertEquals(ModelDownloadException.ErrorCode.CHECKSUM_MISMATCH, error.errorCode)

        server.shutdown()
    }

    // =========================================================================
    // deleteModel / clearCache
    // =========================================================================

    @Test
    fun `deleteModel removes specific model from cache`() = runTest(testDispatcher) {
        val modelBytes = "del-model".toByteArray()
        val checksum = sha256Hex(modelBytes)

        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(modelBytes))
                .addHeader("Content-Length", modelBytes.size.toString()),
        )
        server.start()

        coEvery { api.getDeviceVersion(any(), any(), any()) } returns
            Response.success(VersionResolutionResponse(version = "1.0.0", source = "direct"))

        coEvery { api.getModelDownloadUrl(any(), any(), any()) } returns
            Response.success(
                ModelDownloadResponse(
                    downloadUrl = server.url("/m.tflite").toString(),
                    expiresAt = "2099-01-01T00:00:00Z",
                    checksum = checksum,
                    sizeBytes = modelBytes.size.toLong(),
                ),
            )

        modelManager.ensureModelAvailable()
        val stats1 = modelManager.getCacheStats()
        assertEquals(1, stats1.modelCount)

        val deleted = modelManager.deleteModel("test-model", "1.0.0")
        assertTrue(deleted)

        val stats2 = modelManager.getCacheStats()
        assertEquals(0, stats2.modelCount)

        server.shutdown()
    }

    @Test
    fun `deleteModel returns false for nonexistent model`() = runTest(testDispatcher) {
        val deleted = modelManager.deleteModel("no-such", "0.0.0")
        assertFalse(deleted)
    }

    @Test
    fun `clearCache removes all models`() = runTest(testDispatcher) {
        val modelBytes = "clear-me".toByteArray()
        val checksum = sha256Hex(modelBytes)

        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(modelBytes))
                .addHeader("Content-Length", modelBytes.size.toString()),
        )
        server.start()

        coEvery { api.getDeviceVersion(any(), any(), any()) } returns
            Response.success(VersionResolutionResponse(version = "2.0.0", source = "direct"))

        coEvery { api.getModelDownloadUrl(any(), any(), any()) } returns
            Response.success(
                ModelDownloadResponse(
                    downloadUrl = server.url("/m.tflite").toString(),
                    expiresAt = "2099-01-01T00:00:00Z",
                    checksum = checksum,
                    sizeBytes = modelBytes.size.toLong(),
                ),
            )

        modelManager.ensureModelAvailable()
        modelManager.clearCache()

        val stats = modelManager.getCacheStats()
        assertEquals(0, stats.modelCount)

        server.shutdown()
    }

    // =========================================================================
    // Download state tracking
    // =========================================================================

    @Test
    fun `download state starts as Idle`() = runTest(testDispatcher) {
        val state = modelManager.downloadState.first()
        assertTrue(state is DownloadState.Idle)
    }

    // =========================================================================
    // Download URL fetch failure
    // =========================================================================

    @Test
    fun `ensureModelAvailable fails when download URL returns error`() = runTest(testDispatcher) {
        coEvery { api.getDeviceVersion(any(), any(), any()) } returns
            Response.success(VersionResolutionResponse(version = "1.0.0", source = "direct"))

        coEvery { api.getModelDownloadUrl(any(), any(), any()) } returns
            Response.error(500, okhttp3.ResponseBody.create(null, ""))

        val result = modelManager.ensureModelAvailable()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as ModelDownloadException
        assertEquals(ModelDownloadException.ErrorCode.NETWORK_ERROR, error.errorCode)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
