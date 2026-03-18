package ai.octomil.pairing

import ai.octomil.api.OctomilApi
import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [PairingManager] pairing flow logic.
 *
 * Uses mock API and DeviceCapabilities to test:
 * - connect() sends device info and returns session
 * - waitForDeployment() polls and returns when ready
 * - waitForDeployment() throws on timeout
 * - waitForDeployment() throws on expired/cancelled sessions
 * - executeDeployment() downloads and persists model (no benchmark)
 * - pair() orchestrates the full flow (no benchmark submission)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingManagerTest {

    private lateinit var api: OctomilApi
    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var pairingManager: PairingManager

    private val testDeviceRequest = DeviceConnectRequest(
        deviceId = "test-device-id",
        platform = "android",
        deviceName = "Test Manufacturer Test Model",
        chipFamily = "test-hardware",
        ramGb = 4.0,
        osVersion = "14",
        npuAvailable = false,
        gpuAvailable = true,
    )

    @Before
    fun setUp() {
        api = mockk<OctomilApi>(relaxed = true)
        context = mockk<Context>(relaxed = true)

        cacheDir = File(System.getProperty("java.io.tmpdir"), "octomil_pairing_test_${System.nanoTime()}")
        cacheDir.mkdirs()

        every { context.cacheDir } returns cacheDir
        every { context.filesDir } returns cacheDir // Use same dir for persistence in tests
        every { context.applicationContext } returns context

        // Mock DeviceCapabilities
        mockkObject(DeviceCapabilities)
        every { DeviceCapabilities.collect(any()) } returns testDeviceRequest

        pairingManager = PairingManager(
            api = api,
            context = context,
            pollIntervalMs = 10L, // Fast polling for tests
        )
    }

    @After
    fun tearDown() {
        unmockkObject(DeviceCapabilities)
        cacheDir.deleteRecursively()
    }

    // =========================================================================
    // connect()
    // =========================================================================

    @Test
    fun `connect sends device info and returns session`() = runTest {
        val expectedSession = PairingSession(
            id = "session-1",
            code = "ABC123",
            modelName = "mobilenet-v2",
            modelVersion = null,
            status = PairingStatus.CONNECTED,
        )

        coEvery { api.connectToPairing("ABC123", any()) } returns Response.success(expectedSession)

        val session = pairingManager.connect("ABC123")

        assertEquals("session-1", session.id)
        assertEquals("ABC123", session.code)
        assertEquals(PairingStatus.CONNECTED, session.status)

        coVerify { api.connectToPairing("ABC123", testDeviceRequest) }
    }

    @Test
    fun `connect throws PairingException on HTTP 404`() = runTest {
        coEvery { api.connectToPairing("INVALID", any()) } returns
            Response.error(404, okhttp3.ResponseBody.create(null, ""))

        val ex = assertFailsWith<PairingException> {
            pairingManager.connect("INVALID")
        }

        assertEquals(PairingException.ErrorCode.NOT_FOUND, ex.pairingErrorCode)
    }

    @Test
    fun `connect throws PairingException on HTTP 500`() = runTest {
        coEvery { api.connectToPairing("CODE", any()) } returns
            Response.error(500, okhttp3.ResponseBody.create(null, ""))

        val ex = assertFailsWith<PairingException> {
            pairingManager.connect("CODE")
        }

        assertEquals(PairingException.ErrorCode.SERVER_ERROR, ex.pairingErrorCode)
    }

    @Test
    fun `connect throws PairingException on empty body`() = runTest {
        coEvery { api.connectToPairing("CODE", any()) } returns Response.success(null)

        val ex = assertFailsWith<PairingException> {
            pairingManager.connect("CODE")
        }

        assertEquals(PairingException.ErrorCode.SERVER_ERROR, ex.pairingErrorCode)
    }

    // =========================================================================
    // waitForDeployment()
    // =========================================================================

    @Test
    fun `waitForDeployment returns immediately when session is DEPLOYING`() = runTest {
        val session = PairingSession(
            id = "session-1",
            code = "ABC123",
            modelName = "mobilenet-v2",
            modelVersion = "1.0.0",
            status = PairingStatus.DEPLOYING,
            downloadUrl = "https://example.com/model.tflite",
            downloadFormat = "tensorflow_lite",
            downloadSizeBytes = 5_000_000L,
            quantization = "float16",
            executor = "gpu",
        )

        coEvery { api.getPairingSession("ABC123") } returns Response.success(session)

        val deployment = pairingManager.waitForDeployment("ABC123", timeoutMs = 5_000L)

        assertEquals("mobilenet-v2", deployment.modelName)
        assertEquals("1.0.0", deployment.modelVersion)
        assertEquals("https://example.com/model.tflite", deployment.downloadUrl)
        assertEquals("tensorflow_lite", deployment.format)
        assertEquals("float16", deployment.quantization)
        assertEquals("gpu", deployment.executor)
    }

    @Test
    fun `waitForDeployment returns when session transitions from CONNECTED to DEPLOYING`() = runTest {
        val connectedSession = PairingSession(
            id = "session-1",
            code = "ABC123",
            modelName = "mobilenet-v2",
            status = PairingStatus.CONNECTED,
        )

        val deployingSession = PairingSession(
            id = "session-1",
            code = "ABC123",
            modelName = "mobilenet-v2",
            modelVersion = "1.0.0",
            status = PairingStatus.DEPLOYING,
            downloadUrl = "https://example.com/model.tflite",
            downloadFormat = "tensorflow_lite",
        )

        coEvery { api.getPairingSession("ABC123") } returnsMany listOf(
            Response.success(connectedSession),
            Response.success(connectedSession),
            Response.success(deployingSession),
        )

        val deployment = pairingManager.waitForDeployment("ABC123", timeoutMs = 5_000L)

        assertEquals("mobilenet-v2", deployment.modelName)
        assertEquals("https://example.com/model.tflite", deployment.downloadUrl)
    }

    @Test
    fun `waitForDeployment throws on EXPIRED session`() = runTest {
        val expiredSession = PairingSession(
            id = "session-1",
            code = "ABC123",
            modelName = "mobilenet-v2",
            status = PairingStatus.EXPIRED,
        )

        coEvery { api.getPairingSession("ABC123") } returns Response.success(expiredSession)

        val ex = assertFailsWith<PairingException> {
            pairingManager.waitForDeployment("ABC123", timeoutMs = 5_000L)
        }

        assertEquals(PairingException.ErrorCode.EXPIRED, ex.pairingErrorCode)
    }

    @Test
    fun `waitForDeployment throws on CANCELLED session`() = runTest {
        val cancelledSession = PairingSession(
            id = "session-1",
            code = "ABC123",
            modelName = "mobilenet-v2",
            status = PairingStatus.CANCELLED,
        )

        coEvery { api.getPairingSession("ABC123") } returns Response.success(cancelledSession)

        val ex = assertFailsWith<PairingException> {
            pairingManager.waitForDeployment("ABC123", timeoutMs = 5_000L)
        }

        assertEquals(PairingException.ErrorCode.CANCELLED, ex.pairingErrorCode)
    }

    @Test
    fun `waitForDeployment throws TIMEOUT when deadline exceeded`() = runTest {
        val pendingSession = PairingSession(
            id = "session-1",
            code = "ABC123",
            modelName = "mobilenet-v2",
            status = PairingStatus.PENDING,
        )

        coEvery { api.getPairingSession("ABC123") } returns Response.success(pendingSession)

        val ex = assertFailsWith<PairingException> {
            pairingManager.waitForDeployment("ABC123", timeoutMs = 50L)
        }

        assertEquals(PairingException.ErrorCode.TIMEOUT, ex.pairingErrorCode)
    }

    @Test
    fun `waitForDeployment throws on HTTP error during poll`() = runTest {
        coEvery { api.getPairingSession("ABC123") } returns
            Response.error(500, okhttp3.ResponseBody.create(null, ""))

        val ex = assertFailsWith<PairingException> {
            pairingManager.waitForDeployment("ABC123", timeoutMs = 5_000L)
        }

        assertEquals(PairingException.ErrorCode.SERVER_ERROR, ex.pairingErrorCode)
    }

    @Test
    fun `waitForDeployment throws when DEPLOYING but no download URL`() = runTest {
        val session = PairingSession(
            id = "session-1",
            code = "ABC123",
            modelName = "mobilenet-v2",
            modelVersion = "1.0.0",
            status = PairingStatus.DEPLOYING,
            downloadUrl = null, // missing!
        )

        coEvery { api.getPairingSession("ABC123") } returns Response.success(session)

        val ex = assertFailsWith<PairingException> {
            pairingManager.waitForDeployment("ABC123", timeoutMs = 5_000L)
        }

        assertEquals(PairingException.ErrorCode.SERVER_ERROR, ex.pairingErrorCode)
        assertTrue(ex.message!!.contains("download URL is missing"))
    }

    // =========================================================================
    // executeDeployment() — download + persist only (no benchmark)
    // =========================================================================

    @Test
    fun `executeDeployment downloads and persists model`() = runTest {
        val mockServer = MockWebServer()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("fake-model-binary-content"),
        )
        mockServer.start()

        val deployment = DeploymentInfo(
            modelName = "test-model",
            modelVersion = "1.0.0",
            downloadUrl = mockServer.url("/model.tflite").toString(),
            format = "tensorflow_lite",
            quantization = null,
            executor = null,
            sizeBytes = 26L,
        )

        val result = pairingManager.executeDeployment(deployment)

        assertEquals("test-model", result.modelName)
        assertEquals("1.0.0", result.modelVersion)
        assertNotNull(result.modelFilePath)

        mockServer.shutdown()
    }

    // =========================================================================
    // pair() (full flow — connect, wait, download, persist; no benchmark)
    // =========================================================================

    @Test
    fun `pair orchestrates full flow - connect, wait, download, persist`() = runTest {
        val connectedSession = PairingSession(
            id = "session-1",
            code = "ABC123",
            modelName = "mobilenet-v2",
            modelVersion = null,
            status = PairingStatus.CONNECTED,
        )

        val deployingSession = PairingSession(
            id = "session-1",
            code = "ABC123",
            modelName = "mobilenet-v2",
            modelVersion = "1.0.0",
            status = PairingStatus.DEPLOYING,
            downloadUrl = "http://localhost:9999/model.tflite", // Will be overridden by mock
            downloadFormat = "tensorflow_lite",
            downloadSizeBytes = 100L,
        )

        coEvery { api.connectToPairing("ABC123", any()) } returns Response.success(connectedSession)
        coEvery { api.getPairingSession("ABC123") } returns Response.success(deployingSession)

        val mockServer = MockWebServer()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("fake-model-bytes"),
        )
        mockServer.start()

        // Update the deploying session to point to our mock server
        val deployingSessionWithUrl = deployingSession.copy(
            downloadUrl = mockServer.url("/model.tflite").toString(),
        )
        coEvery { api.getPairingSession("ABC123") } returns Response.success(deployingSessionWithUrl)

        val result = pairingManager.pair("ABC123", timeoutMs = 5_000L)

        assertEquals("mobilenet-v2", result.modelName)
        assertEquals("1.0.0", result.modelVersion)
        assertNotNull(result.modelFilePath)

        // Verify pairing API calls were made (but NOT submitBenchmark)
        coVerify { api.connectToPairing("ABC123", any()) }
        coVerify { api.getPairingSession("ABC123") }
        coVerify(exactly = 0) { api.submitBenchmark(any(), any()) }

        mockServer.shutdown()
    }

    // =========================================================================
    // Multi-resource executeDeployment()
    // =========================================================================

    @Test
    fun `executeDeployment downloads all resources when resources list is present`() = runTest {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("model-binary-content"))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{\"vocab\":true}"))
        mockServer.start()

        val deployment = DeploymentInfo(
            modelName = "multi-model",
            modelVersion = "1.0.0",
            downloadUrl = "",
            format = "gguf",
            sizeBytes = 100L,
            resources = listOf(
                DownloadResource(
                    kind = "model",
                    uri = mockServer.url("/model.gguf").toString(),
                    filename = "model.gguf",
                    loadOrder = 0,
                    sizeBytes = 20L,
                ),
                DownloadResource(
                    kind = "tokenizer",
                    uri = mockServer.url("/tokenizer.json").toString(),
                    filename = "tokenizer.json",
                    loadOrder = 1,
                    sizeBytes = 14L,
                ),
            ),
        )

        val result = pairingManager.executeDeployment(deployment)

        assertEquals("multi-model", result.modelName)
        assertNotNull(result.modelFilePath)
        assertTrue(result.modelFilePath!!.contains("model.gguf"))

        // Verify both files were downloaded (2 requests to mock server)
        assertEquals(2, mockServer.requestCount)

        mockServer.shutdown()
    }

    @Test
    fun `executeDeployment falls back to single download when resources is empty`() = runTest {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("single-model"))
        mockServer.start()

        val deployment = DeploymentInfo(
            modelName = "single-model",
            modelVersion = "1.0.0",
            downloadUrl = mockServer.url("/model.tflite").toString(),
            format = "tensorflow_lite",
            sizeBytes = 12L,
            resources = emptyList(),
        )

        val result = pairingManager.executeDeployment(deployment)

        assertEquals("single-model", result.modelName)
        // Single download = 1 request
        assertEquals(1, mockServer.requestCount)

        mockServer.shutdown()
    }

    @Test
    fun `executeDeployment uses first resource by load_order when no model kind`() = runTest {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("weights-data"))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("config-data"))
        mockServer.start()

        val deployment = DeploymentInfo(
            modelName = "custom-model",
            modelVersion = "2.0.0",
            downloadUrl = "",
            format = "custom",
            resources = listOf(
                DownloadResource(
                    kind = "config",
                    uri = mockServer.url("/config.json").toString(),
                    filename = "config.json",
                    loadOrder = 1,
                ),
                DownloadResource(
                    kind = "weights",
                    uri = mockServer.url("/weights.bin").toString(),
                    filename = "weights.bin",
                    loadOrder = 0,
                ),
            ),
        )

        val result = pairingManager.executeDeployment(deployment)

        assertEquals("custom-model", result.modelName)
        // weights.bin has load_order=0, so it's the primary (first sorted)
        assertTrue(result.modelFilePath!!.contains("weights.bin"))

        mockServer.shutdown()
    }

    @Test
    fun `executeDeployment cleans up on multi-resource download failure`() = runTest {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("model-data"))
        mockServer.enqueue(MockResponse().setResponseCode(500)) // second resource fails
        mockServer.start()

        val deployment = DeploymentInfo(
            modelName = "fail-model",
            modelVersion = "1.0.0",
            downloadUrl = "",
            format = "gguf",
            resources = listOf(
                DownloadResource(
                    kind = "model",
                    uri = mockServer.url("/model.gguf").toString(),
                    filename = "model.gguf",
                    loadOrder = 0,
                ),
                DownloadResource(
                    kind = "tokenizer",
                    uri = mockServer.url("/tokenizer.json").toString(),
                    filename = "tokenizer.json",
                    loadOrder = 1,
                ),
            ),
        )

        val ex = assertFailsWith<PairingException> {
            pairingManager.executeDeployment(deployment)
        }

        assertEquals(PairingException.ErrorCode.DOWNLOAD_FAILED, ex.pairingErrorCode)

        mockServer.shutdown()
    }

    // =========================================================================
    // waitForDeployment() with resources
    // =========================================================================

    @Test
    fun `waitForDeployment returns deployment with resources when present`() = runTest {
        val session = PairingSession(
            id = "session-mr",
            code = "MR123",
            modelName = "multi-res-model",
            modelVersion = "1.0.0",
            status = PairingStatus.DEPLOYING,
            downloadUrl = null,
            downloadFormat = "gguf",
            resources = listOf(
                DownloadResource(
                    kind = "model",
                    uri = "https://cdn.example.com/model.gguf",
                    filename = "model.gguf",
                    loadOrder = 0,
                    sizeBytes = 4_000_000_000L,
                ),
            ),
        )

        coEvery { api.getPairingSession("MR123") } returns Response.success(session)

        val deployment = pairingManager.waitForDeployment("MR123", timeoutMs = 5_000L)

        assertEquals("multi-res-model", deployment.modelName)
        assertNotNull(deployment.resources)
        assertEquals(1, deployment.resources!!.size)
        assertEquals("model", deployment.resources!![0].kind)
    }

    @Test
    fun `waitForDeployment allows null downloadUrl when resources present`() = runTest {
        val session = PairingSession(
            id = "session-no-url",
            code = "NOURL",
            modelName = "res-only-model",
            modelVersion = "1.0.0",
            status = PairingStatus.DEPLOYING,
            downloadUrl = null,
            resources = listOf(
                DownloadResource(
                    kind = "model",
                    uri = "https://cdn.example.com/model.bin",
                    filename = "model.bin",
                    loadOrder = 0,
                ),
            ),
        )

        coEvery { api.getPairingSession("NOURL") } returns Response.success(session)

        val deployment = pairingManager.waitForDeployment("NOURL", timeoutMs = 5_000L)

        assertEquals("", deployment.downloadUrl) // empty fallback, not used
        assertNotNull(deployment.resources)
    }

    // =========================================================================
    // downloadModel()
    // =========================================================================

    @Test
    fun `downloadModel creates file with correct extension for tflite`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("fake-tflite-model"),
        )
        mockServer.start()

        val deployment = DeploymentInfo(
            modelName = "my-model",
            modelVersion = "2.0.0",
            downloadUrl = mockServer.url("/model.tflite").toString(),
            format = "tensorflow_lite",
        )

        val file = pairingManager.downloadModel(deployment)

        assertTrue(file.exists())
        assertTrue(file.name.endsWith(".tflite"))
        assertEquals("fake-tflite-model", file.readText())

        file.delete()
        mockServer.shutdown()
    }

    @Test
    fun `downloadModel throws PairingException on HTTP error`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(MockResponse().setResponseCode(403))
        mockServer.start()

        val deployment = DeploymentInfo(
            modelName = "my-model",
            modelVersion = "1.0.0",
            downloadUrl = mockServer.url("/model.tflite").toString(),
            format = "tensorflow_lite",
        )

        val ex = assertFailsWith<PairingException> {
            pairingManager.downloadModel(deployment)
        }

        assertEquals(PairingException.ErrorCode.DOWNLOAD_FAILED, ex.pairingErrorCode)

        mockServer.shutdown()
    }

    // =========================================================================
    // PairingException.ErrorCode mapping
    // =========================================================================

    @Test
    fun `ErrorCode fromHttpStatus maps correctly`() {
        assertEquals(PairingException.ErrorCode.UNAUTHORIZED, PairingException.ErrorCode.fromHttpStatus(401))
        assertEquals(PairingException.ErrorCode.UNAUTHORIZED, PairingException.ErrorCode.fromHttpStatus(403))
        assertEquals(PairingException.ErrorCode.NOT_FOUND, PairingException.ErrorCode.fromHttpStatus(404))
        assertEquals(PairingException.ErrorCode.TIMEOUT, PairingException.ErrorCode.fromHttpStatus(408))
        assertEquals(PairingException.ErrorCode.EXPIRED, PairingException.ErrorCode.fromHttpStatus(410))
        assertEquals(PairingException.ErrorCode.SERVER_ERROR, PairingException.ErrorCode.fromHttpStatus(500))
        assertEquals(PairingException.ErrorCode.SERVER_ERROR, PairingException.ErrorCode.fromHttpStatus(502))
        assertEquals(PairingException.ErrorCode.SERVER_ERROR, PairingException.ErrorCode.fromHttpStatus(503))
        assertEquals(PairingException.ErrorCode.NETWORK_ERROR, PairingException.ErrorCode.fromHttpStatus(400))
        assertEquals(PairingException.ErrorCode.NETWORK_ERROR, PairingException.ErrorCode.fromHttpStatus(429))
    }
}
