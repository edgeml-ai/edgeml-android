package ai.octomil.wrapper

import ai.octomil.api.OctomilApi
import ai.octomil.api.dto.ModelDownloadResponse
import ai.octomil.api.dto.ModelResolveResponse
import ai.octomil.api.dto.AnyValue
import ai.octomil.api.dto.ExportLogsServiceRequest
import ai.octomil.api.dto.TelemetryV2Event
import ai.octomil.api.dto.VersionResolutionResponse
import ai.octomil.models.ModelDownloadException
import ai.octomil.models.ModelManager
import ai.octomil.storage.SecureStorage
import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests that v2 telemetry events are emitted from the correct call sites
 * in ModelManager (deploy events, experiment.assigned).
 *
 * Sets up a real TelemetryQueue with a capturing sender so we can assert
 * on the events that flow through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryV2CallSiteTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var cacheDir: File
    private lateinit var context: Context
    private lateinit var api: OctomilApi
    private lateinit var storage: SecureStorage
    private lateinit var modelManager: ModelManager
    private lateinit var config: ai.octomil.config.OctomilConfig
    private lateinit var telemetryQueue: TelemetryQueue
    private val capturedBatches = mutableListOf<ExportLogsServiceRequest>()

    @Before
    fun setUp() {
        cacheDir = File(System.getProperty("java.io.tmpdir"), "octomil_callsite_test_${System.nanoTime()}")
        cacheDir.mkdirs()

        config = ai.octomil.config.OctomilConfig(
            auth = ai.octomil.config.AuthConfig.OrgApiKey(apiKey = "test-token", orgId = "test-org", serverUrl = "https://test.octomil.com"),
            modelId = "test-model",
            enableBackgroundSync = false,
            enableHeartbeat = false,
            enableGpuAcceleration = false,
            enableEncryptedStorage = false,
        )

        context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns cacheDir

        api = mockk<OctomilApi>()
        storage = mockk<SecureStorage>(relaxed = true)

        coEvery { storage.getServerDeviceId() } returns "device-uuid-123"

        // Mock resolveModelFormat — called between version resolution and download
        coEvery { api.resolveModelFormat(any(), any(), any()) } returns
            Response.success(
                ModelResolveResponse(
                    modelId = "test-model",
                    version = "1.0.0",
                    format = "tensorflow_lite",
                ),
            )

        // Set up a TelemetryQueue with a capturing sender as the shared instance
        val sender = TelemetrySender { batch -> capturedBatches.add(batch) }
        telemetryQueue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
            dispatcher = testDispatcher,
            orgId = "test-org",
            deviceId = "device-uuid-123",
        )
        telemetryQueue.start()

        modelManager = ModelManager(context, config, api, storage, testDispatcher)
    }

    @After
    fun tearDown() {
        telemetryQueue.close()
        cacheDir.deleteRecursively()
    }

    // =========================================================================
    // Deploy events
    // =========================================================================

    @Test
    fun `ensureModelAvailable emits deploy_started and deploy_completed on success`() = runTest(testDispatcher) {
        val modelBytes = "deploy-telemetry-test".toByteArray()
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
                    downloadUrl = server.url("/model.tflite").toString(),
                    expiresAt = "2099-01-01T00:00:00Z",
                    checksum = checksum,
                    sizeBytes = modelBytes.size.toLong(),
                ),
            )

        val result = modelManager.ensureModelAvailable()
        assertTrue(result.isSuccess, "ensureModelAvailable should succeed")

        // Flush the telemetry queue to send captured events
        telemetryQueue.flush()
        advanceUntilIdle()

        // Collect all v2 events from the queue
        val allEvents = collectPendingV2Events()

        val deployStarted = allEvents.filter { it.name == "deploy.started" }
        val deployCompleted = allEvents.filter { it.name == "deploy.completed" }

        assertEquals(1, deployStarted.size, "Expected exactly one deploy.started event")
        assertEquals(1, deployCompleted.size, "Expected exactly one deploy.completed event")

        // Verify deploy.started attributes
        val startedAttrs = deployStarted[0].attributes
        assertEquals(JsonPrimitive("test-model"), startedAttrs["model.id"])
        assertEquals(JsonPrimitive("1.0.0"), startedAttrs["model.version"])

        // Verify deploy.completed attributes
        val completedAttrs = deployCompleted[0].attributes
        assertEquals(JsonPrimitive("test-model"), completedAttrs["model.id"])
        assertEquals(JsonPrimitive("1.0.0"), completedAttrs["model.version"])
        assertNotNull(completedAttrs["deploy.duration_ms"], "deploy.completed should have duration_ms")

        server.shutdown()
    }

    @Test
    fun `ensureModelAvailable emits deploy_failed on ModelDownloadException`() = runTest(testDispatcher) {
        val modelBytes = "bad-model".toByteArray()

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

        // Return a bad checksum to force a ModelDownloadException
        coEvery { api.getModelDownloadUrl(any(), any(), any()) } returns
            Response.success(
                ModelDownloadResponse(
                    downloadUrl = server.url("/model.tflite").toString(),
                    expiresAt = "2099-01-01T00:00:00Z",
                    checksum = "deadbeef",
                    sizeBytes = modelBytes.size.toLong(),
                ),
            )

        val result = modelManager.ensureModelAvailable()
        assertTrue(result.isFailure)

        // Flush the telemetry queue
        telemetryQueue.flush()
        advanceUntilIdle()

        val allEvents = collectPendingV2Events()

        val deployStarted = allEvents.filter { it.name == "deploy.started" }
        val deployFailed = allEvents.filter { it.name == "deploy.failed" }

        assertEquals(1, deployStarted.size, "Expected deploy.started before failure")
        assertEquals(1, deployFailed.size, "Expected exactly one deploy.failed event")

        val failedAttrs = deployFailed[0].attributes
        assertEquals(JsonPrimitive("test-model"), failedAttrs["model.id"])
        assertNotNull(failedAttrs["error.message"], "deploy.failed should have error.message")
        assertEquals(
            JsonPrimitive(ModelDownloadException.ErrorCode.CHECKSUM_MISMATCH.name),
            failedAttrs["error.code"],
        )

        server.shutdown()
    }

    @Test
    fun `ensureModelAvailable does not emit deploy events when model is cached`() = runTest(testDispatcher) {
        val modelBytes = "cached-telemetry".toByteArray()
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
                    downloadUrl = server.url("/model.tflite").toString(),
                    expiresAt = "2099-01-01T00:00:00Z",
                    checksum = checksum,
                    sizeBytes = modelBytes.size.toLong(),
                ),
            )

        // First call downloads
        modelManager.ensureModelAvailable()
        telemetryQueue.flush()
        advanceUntilIdle()
        capturedBatches.clear()

        // Second call should hit cache — no deploy events
        modelManager.ensureModelAvailable()
        telemetryQueue.flush()
        advanceUntilIdle()

        val allEvents = collectPendingV2Events()
        val deployEvents = allEvents.filter { it.name.startsWith("deploy.") }
        assertTrue(deployEvents.isEmpty(), "No deploy events should be emitted for cached model")

        server.shutdown()
    }

    // =========================================================================
    // Experiment assigned
    // =========================================================================

    @Test
    fun `resolveVersion emits experiment_assigned when experiment_id present`() = runTest(testDispatcher) {
        val modelBytes = "exp-model".toByteArray()
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
            Response.success(
                VersionResolutionResponse(
                    version = "2.0.0",
                    source = "experiment",
                    experimentId = "exp-abc-123",
                ),
            )

        coEvery { api.getModelDownloadUrl(any(), any(), any()) } returns
            Response.success(
                ModelDownloadResponse(
                    downloadUrl = server.url("/model.tflite").toString(),
                    expiresAt = "2099-01-01T00:00:00Z",
                    checksum = checksum,
                    sizeBytes = modelBytes.size.toLong(),
                ),
            )

        modelManager.ensureModelAvailable()
        telemetryQueue.flush()
        advanceUntilIdle()

        val allEvents = collectPendingV2Events()
        val expEvents = allEvents.filter { it.name == "experiment.assigned" }

        assertEquals(1, expEvents.size, "Expected exactly one experiment.assigned event")

        val attrs = expEvents[0].attributes
        assertEquals(JsonPrimitive("test-model"), attrs["model.id"])
        assertEquals(JsonPrimitive("exp-abc-123"), attrs["experiment.id"])
        assertEquals(JsonPrimitive("experiment"), attrs["experiment.source"])

        server.shutdown()
    }

    @Test
    fun `resolveVersion does not emit experiment_assigned when no experiment_id`() = runTest(testDispatcher) {
        val modelBytes = "no-exp".toByteArray()
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
            Response.success(
                VersionResolutionResponse(
                    version = "1.0.0",
                    source = "direct",
                    experimentId = null,
                ),
            )

        coEvery { api.getModelDownloadUrl(any(), any(), any()) } returns
            Response.success(
                ModelDownloadResponse(
                    downloadUrl = server.url("/model.tflite").toString(),
                    expiresAt = "2099-01-01T00:00:00Z",
                    checksum = checksum,
                    sizeBytes = modelBytes.size.toLong(),
                ),
            )

        modelManager.ensureModelAvailable()
        telemetryQueue.flush()
        advanceUntilIdle()

        val allEvents = collectPendingV2Events()
        val expEvents = allEvents.filter { it.name == "experiment.assigned" }
        assertTrue(expEvents.isEmpty(), "No experiment.assigned event when experimentId is null")

        server.shutdown()
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Collect all events from captured OTLP batches, converting back to
     * TelemetryV2Event for test assertion compatibility.
     */
    private fun collectPendingV2Events(): List<TelemetryV2Event> =
        capturedBatches.flatMap { req ->
            req.resourceLogs.flatMap { rl ->
                rl.scopeLogs.flatMap { sl ->
                    sl.logRecords.map { record ->
                        val name = (record.body as? AnyValue.StringValue)?.stringValue ?: ""
                        val attrs = (record.attributes ?: emptyList()).associate { kv ->
                            kv.key to when (val v = kv.value) {
                                is AnyValue.StringValue -> JsonPrimitive(v.stringValue)
                                is AnyValue.IntValue -> JsonPrimitive(v.intValue)
                                is AnyValue.DoubleValue -> JsonPrimitive(v.doubleValue)
                                is AnyValue.BoolValue -> JsonPrimitive(v.boolValue)
                            }
                        }
                        TelemetryV2Event(
                            name = name,
                            timestamp = record.timeUnixNano,
                            attributes = attrs,
                            traceId = record.traceId,
                            spanId = record.spanId,
                        )
                    }
                }
            }
        }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
