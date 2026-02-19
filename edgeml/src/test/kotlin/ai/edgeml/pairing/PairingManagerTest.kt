package ai.edgeml.pairing

import ai.edgeml.api.EdgeMLApi
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
 * - submitBenchmark() sends report to server
 * - pair() orchestrates the full flow
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingManagerTest {

    private lateinit var api: EdgeMLApi
    private lateinit var context: Context
    private lateinit var benchmarkRunner: BenchmarkRunner
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

    private val testBenchmarkReport = BenchmarkReport(
        modelName = "test-model",
        deviceName = "Test Manufacturer Test Model",
        chipFamily = "test-hardware",
        ramGb = 4.0,
        osVersion = "14",
        ttftMs = 25.0,
        tpotMs = 12.0,
        tokensPerSecond = 83.3,
        p50LatencyMs = 11.0,
        p95LatencyMs = 18.0,
        p99LatencyMs = 22.0,
        memoryPeakBytes = 50_000_000L,
        inferenceCount = 11,
        modelLoadTimeMs = 150.0,
        coldInferenceMs = 25.0,
        warmInferenceMs = 12.0,
        batteryLevel = null,
        thermalState = null,
    )

    @Before
    fun setUp() {
        api = mockk<EdgeMLApi>(relaxed = true)
        context = mockk<Context>(relaxed = true)
        benchmarkRunner = mockk<BenchmarkRunner>(relaxed = true)

        cacheDir = File(System.getProperty("java.io.tmpdir"), "edgeml_pairing_test_${System.nanoTime()}")
        cacheDir.mkdirs()

        every { context.cacheDir } returns cacheDir
        every { context.applicationContext } returns context

        // Mock DeviceCapabilities
        mockkObject(DeviceCapabilities)
        every { DeviceCapabilities.collect(any()) } returns testDeviceRequest

        pairingManager = PairingManager(
            api = api,
            context = context,
            benchmarkRunner = benchmarkRunner,
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

        assertEquals(PairingException.ErrorCode.NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `connect throws PairingException on HTTP 500`() = runTest {
        coEvery { api.connectToPairing("CODE", any()) } returns
            Response.error(500, okhttp3.ResponseBody.create(null, ""))

        val ex = assertFailsWith<PairingException> {
            pairingManager.connect("CODE")
        }

        assertEquals(PairingException.ErrorCode.SERVER_ERROR, ex.errorCode)
    }

    @Test
    fun `connect throws PairingException on empty body`() = runTest {
        coEvery { api.connectToPairing("CODE", any()) } returns Response.success(null)

        val ex = assertFailsWith<PairingException> {
            pairingManager.connect("CODE")
        }

        assertEquals(PairingException.ErrorCode.SERVER_ERROR, ex.errorCode)
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

        assertEquals(PairingException.ErrorCode.EXPIRED, ex.errorCode)
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

        assertEquals(PairingException.ErrorCode.CANCELLED, ex.errorCode)
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

        assertEquals(PairingException.ErrorCode.TIMEOUT, ex.errorCode)
    }

    @Test
    fun `waitForDeployment throws on HTTP error during poll`() = runTest {
        coEvery { api.getPairingSession("ABC123") } returns
            Response.error(500, okhttp3.ResponseBody.create(null, ""))

        val ex = assertFailsWith<PairingException> {
            pairingManager.waitForDeployment("ABC123", timeoutMs = 5_000L)
        }

        assertEquals(PairingException.ErrorCode.SERVER_ERROR, ex.errorCode)
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

        assertEquals(PairingException.ErrorCode.SERVER_ERROR, ex.errorCode)
        assertTrue(ex.message!!.contains("download URL is missing"))
    }

    // =========================================================================
    // executeDeployment()
    // =========================================================================

    @Test
    fun `executeDeployment downloads model and runs benchmark`() = runTest {
        // Set up a MockWebServer to serve a fake model file
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

        every { benchmarkRunner.run(any(), eq("test-model"), any()) } returns testBenchmarkReport

        val report = pairingManager.executeDeployment(deployment)

        assertEquals("test-model", report.modelName)
        assertEquals(11, report.inferenceCount)

        mockServer.shutdown()
    }

    // =========================================================================
    // submitBenchmark()
    // =========================================================================

    @Test
    fun `submitBenchmark sends report to API`() = runTest {
        coEvery { api.submitBenchmark("ABC123", any()) } returns Response.success(Unit)

        pairingManager.submitBenchmark("ABC123", testBenchmarkReport)

        coVerify { api.submitBenchmark("ABC123", testBenchmarkReport) }
    }

    @Test
    fun `submitBenchmark does not throw on server error`() = runTest {
        // Submit failure is non-fatal
        coEvery { api.submitBenchmark("ABC123", any()) } returns
            Response.error(500, okhttp3.ResponseBody.create(null, ""))

        // Should not throw
        pairingManager.submitBenchmark("ABC123", testBenchmarkReport)
    }

    // =========================================================================
    // pair() (full flow)
    // =========================================================================

    @Test
    fun `pair orchestrates full flow - connect, wait, download, benchmark, report`() = runTest {
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
        coEvery { api.submitBenchmark("ABC123", any()) } returns Response.success(Unit)

        // Mock the internal downloadModel call via benchmarkRunner
        every { benchmarkRunner.run(any(), eq("mobilenet-v2"), any()) } returns testBenchmarkReport.copy(
            modelName = "mobilenet-v2",
        )

        // We need to also mock the download. Since pair() calls executeDeployment()
        // which calls downloadModel() internally, we use a MockWebServer.
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

        val report = pairingManager.pair("ABC123", timeoutMs = 5_000L)

        assertEquals("mobilenet-v2", report.modelName)

        // Verify all API calls were made
        coVerify { api.connectToPairing("ABC123", any()) }
        coVerify { api.getPairingSession("ABC123") }
        coVerify { api.submitBenchmark("ABC123", any()) }

        mockServer.shutdown()
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

        assertEquals(PairingException.ErrorCode.DOWNLOAD_FAILED, ex.errorCode)

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
