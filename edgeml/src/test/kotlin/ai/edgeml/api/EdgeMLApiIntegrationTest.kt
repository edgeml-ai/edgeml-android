package ai.edgeml.api

import ai.edgeml.api.dto.DevicePolicyResponse
import ai.edgeml.api.dto.DeviceRegistrationRequest
import ai.edgeml.api.dto.DeviceRegistrationResponse
import ai.edgeml.api.dto.GradientUpdateRequest
import ai.edgeml.api.dto.GradientUpdateResponse
import ai.edgeml.api.dto.GroupMembership
import ai.edgeml.api.dto.GroupMembershipsResponse
import ai.edgeml.api.dto.HealthResponse
import ai.edgeml.api.dto.HeartbeatRequest
import ai.edgeml.api.dto.HeartbeatResponse
import ai.edgeml.api.dto.InferenceEventRequest
import ai.edgeml.api.dto.InferenceEventResponse
import ai.edgeml.api.dto.ModelDownloadResponse
import ai.edgeml.api.dto.ModelResponse
import ai.edgeml.api.dto.ModelVersionResponse
import ai.edgeml.api.dto.TrainingEventRequest
import ai.edgeml.api.dto.TrainingMetrics
import ai.edgeml.api.dto.VersionResolutionResponse
import ai.edgeml.config.EdgeMLConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for EdgeML API using MockWebServer.
 *
 * Validates that requests are sent with correct paths, headers,
 * and bodies, and that responses are deserialized correctly.
 */
class EdgeMLApiIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var api: EdgeMLApi
    private lateinit var config: EdgeMLConfig

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        config =
            EdgeMLConfig(
                serverUrl = server.url("/").toString().trimEnd('/'),
                deviceAccessToken = "test-token-123",
                orgId = "test-org",
                modelId = "test-model",
                debugMode = false,
            )

        api = EdgeMLApiFactory.create(config)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // =========================================================================
    // Health Check
    // =========================================================================

    @Test
    fun `healthCheck sends GET to correct path`() =
        runBlocking {
            val body = json.encodeToString(HealthResponse("healthy", "1.0.0", "2024-01-01T00:00:00Z"))
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val response = api.healthCheck()

            assertTrue(response.isSuccessful)
            assertEquals("healthy", response.body()?.status)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/health", request.path)
        }

    // =========================================================================
    // Device Registration
    // =========================================================================

    @Test
    fun `registerDevice sends correct request body`() =
        runBlocking {
            val responseBody =
                json.encodeToString(
                    DeviceRegistrationResponse(
                        id = "server-uuid",
                        deviceIdentifier = "device-1",
                        orgId = "test-org",
                        platform = "android",
                        status = "active",
                        createdAt = "2024-01-01T00:00:00Z",
                        updatedAt = "2024-01-01T00:00:00Z",
                        apiToken = "new-token",
                    ),
                )
            server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

            val request =
                DeviceRegistrationRequest(
                    deviceIdentifier = "device-1",
                    orgId = "test-org",
                    osVersion = "Android 14",
                    sdkVersion = "1.0.0",
                )
            val response = api.registerDevice(request)

            assertTrue(response.isSuccessful)
            assertEquals("server-uuid", response.body()?.id)

            val sentRequest = server.takeRequest()
            assertEquals("POST", sentRequest.method)
            assertEquals("/api/v1/devices/register", sentRequest.path)

            val sentBody = sentRequest.body.readUtf8()
            assertTrue(sentBody.contains("device-1"))
            assertTrue(sentBody.contains("test-org"))
        }

    @Test
    fun `registerDevice includes auth headers`() =
        runBlocking {
            val responseBody =
                json.encodeToString(
                    DeviceRegistrationResponse(
                        id = "uuid-1",
                        deviceIdentifier = "d1",
                        orgId = "o1",
                        platform = "android",
                        status = "active",
                        createdAt = "2024-01-01T00:00:00Z",
                        updatedAt = "2024-01-01T00:00:00Z",
                    ),
                )
            server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

            val request =
                DeviceRegistrationRequest(
                    deviceIdentifier = "d1",
                    orgId = "o1",
                    osVersion = "14",
                    sdkVersion = "1.0",
                )
            api.registerDevice(request)

            val sentRequest = server.takeRequest()
            assertEquals("Bearer test-token-123", sentRequest.getHeader("Authorization"))
            assertEquals("test-org", sentRequest.getHeader("X-Org-Id"))
        }

    // =========================================================================
    // Heartbeat
    // =========================================================================

    @Test
    fun `sendHeartbeat sends to correct device path`() =
        runBlocking {
            val body = json.encodeToString(HeartbeatResponse(acknowledged = true))
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val request = HeartbeatRequest(sdkVersion = "1.0.0", batteryLevel = 85)
            val response = api.sendHeartbeat("device-uuid", request)

            assertTrue(response.isSuccessful)
            assertTrue(response.body()?.acknowledged == true)

            val sentRequest = server.takeRequest()
            assertEquals("POST", sentRequest.method)
            assertEquals("/api/v1/devices/device-uuid/heartbeat", sentRequest.path)
        }

    // =========================================================================
    // Device Groups
    // =========================================================================

    @Test
    fun `getDeviceGroups sends to correct path`() =
        runBlocking {
            val body =
                json.encodeToString(
                    GroupMembershipsResponse(
                        memberships =
                            listOf(
                                GroupMembership("m1", "d1", "g1", "beta", "manual", "2024-01-01T00:00:00Z"),
                            ),
                        count = 1,
                    ),
                )
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val response = api.getDeviceGroups("my-device")

            assertTrue(response.isSuccessful)
            assertEquals(1, response.body()?.count)

            val sentRequest = server.takeRequest()
            assertEquals("GET", sentRequest.method)
            assertEquals("/api/v1/devices/my-device/groups", sentRequest.path)
        }

    // =========================================================================
    // Model Catalog
    // =========================================================================

    @Test
    fun `getModel sends to correct path`() =
        runBlocking {
            val body =
                json.encodeToString(
                    ModelResponse(
                        "m1",
                        "o1",
                        "test-model",
                        "tensorflow_lite",
                        "classification",
                        null,
                        3,
                        "2024-01-01T00:00:00Z",
                        "2024-01-01T00:00:00Z",
                    ),
                )
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val response = api.getModel("m1")

            assertTrue(response.isSuccessful)
            assertEquals("test-model", response.body()?.name)

            val sentRequest = server.takeRequest()
            assertEquals("GET", sentRequest.method)
            assertEquals("/api/v1/models/m1", sentRequest.path)
        }

    @Test
    fun `getModelVersion sends to correct path`() =
        runBlocking {
            val body =
                json.encodeToString(
                    ModelVersionResponse(
                        "v1",
                        "m1",
                        "1.0.0",
                        "published",
                        "s3://path",
                        "tensorflow_lite",
                        "abc",
                        1000,
                        null,
                        null,
                        "2024-01-01T00:00:00Z",
                        "2024-01-01T00:00:00Z",
                    ),
                )
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val response = api.getModelVersion("m1", "1.0.0")

            assertTrue(response.isSuccessful)
            assertEquals("1.0.0", response.body()?.version)

            val sentRequest = server.takeRequest()
            assertEquals("/api/v1/models/m1/versions/1.0.0", sentRequest.path)
        }

    @Test
    fun `getModelDownloadUrl sends correct path and format query`() =
        runBlocking {
            val body =
                json.encodeToString(
                    ModelDownloadResponse("https://cdn.example.com/model.tflite", "2024-01-01T01:00:00Z", "sha256:abc", 5000),
                )
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val response = api.getModelDownloadUrl("m1", "1.0.0", "tensorflow_lite")

            assertTrue(response.isSuccessful)

            val sentRequest = server.takeRequest()
            assertTrue(sentRequest.path!!.startsWith("/api/v1/models/m1/versions/1.0.0/download"))
            assertTrue(sentRequest.path!!.contains("format=tensorflow_lite"))
        }

    // =========================================================================
    // Inference Events
    // =========================================================================

    @Test
    fun `reportInferenceEvent sends to correct path`() =
        runBlocking {
            val body = json.encodeToString(InferenceEventResponse("accepted", "session-1"))
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val request =
                InferenceEventRequest(
                    deviceId = "d1",
                    modelId = "m1",
                    version = "1.0",
                    modality = "text",
                    sessionId = "session-1",
                    eventType = "generation_started",
                    timestampMs = 1000,
                )
            val response = api.reportInferenceEvent(request)

            assertTrue(response.isSuccessful)
            assertEquals("session-1", response.body()?.sessionId)

            val sentRequest = server.takeRequest()
            assertEquals("POST", sentRequest.method)
            assertEquals("/api/v1/inference/events", sentRequest.path)
        }

    // =========================================================================
    // Device Policy
    // =========================================================================

    @Test
    fun `getDevicePolicy sends to correct path`() =
        runBlocking {
            val body = json.encodeToString(DevicePolicyResponse(30, "wifi_only"))
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val response = api.getDevicePolicy("org-1")

            assertTrue(response.isSuccessful)
            assertEquals(30, response.body()?.batteryThreshold)

            val sentRequest = server.takeRequest()
            assertEquals("GET", sentRequest.method)
            assertEquals("/api/v1/settings/org/org-1/device-policy", sentRequest.path)
        }

    // =========================================================================
    // Error Handling
    // =========================================================================

    @Test
    fun `API returns error response for 404`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not found"}"""))

            val response = api.getModel("nonexistent")

            assertEquals(404, response.code())
        }

    @Test
    fun `API returns error response for 500`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"Internal error"}"""))

            val response = api.healthCheck()

            assertEquals(500, response.code())
        }

    // =========================================================================
    // Training Events
    // =========================================================================

    @Test
    fun `reportTrainingEvent sends to correct path`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(""))

            val request =
                TrainingEventRequest(
                    deviceId = "d1",
                    modelId = "m1",
                    version = "1.0",
                    eventType = "training_completed",
                    timestamp = "2024-01-01T00:00:00Z",
                )
            api.reportTrainingEvent("exp-1", request)

            val sentRequest = server.takeRequest()
            assertEquals("POST", sentRequest.method)
            assertEquals("/api/v1/experiments/exp-1/events", sentRequest.path)
        }

    @Test
    fun `submitGradients sends to correct path`() =
        runBlocking {
            val body = json.encodeToString(GradientUpdateResponse(true, "round-1"))
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val request =
                GradientUpdateRequest(
                    deviceId = "d1",
                    modelId = "m1",
                    version = "1.0",
                    roundId = "round-1",
                    numSamples = 100,
                    trainingTimeMs = 5000,
                    metrics = TrainingMetrics(loss = 0.05, numBatches = 4),
                )
            val response = api.submitGradients("exp-1", request)

            assertTrue(response.isSuccessful)

            val sentRequest = server.takeRequest()
            assertEquals("POST", sentRequest.method)
            assertEquals("/api/v1/experiments/exp-1/gradients", sentRequest.path)
        }

    // =========================================================================
    // Version Resolution
    // =========================================================================

    @Test
    fun `getDeviceVersion sends correct path with device and model`() =
        runBlocking {
            val body = json.encodeToString(VersionResolutionResponse("1.0.0", "default"))
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val response = api.getDeviceVersion("d1", "m1")

            assertTrue(response.isSuccessful)
            assertEquals("1.0.0", response.body()?.version)

            val sentRequest = server.takeRequest()
            assertEquals("GET", sentRequest.method)
            assertEquals("/devices/d1/models/m1/version?include_bucket=false", sentRequest.path)
        }

    @Test
    fun `getLatestVersion sends correct path`() =
        runBlocking {
            val body =
                json.encodeToString(
                    ModelVersionResponse(
                        "v1",
                        "m1",
                        "2.0.0",
                        "published",
                        "s3://path",
                        "tensorflow_lite",
                        "abc",
                        1000,
                        null,
                        null,
                        "2024-01-01T00:00:00Z",
                        "2024-01-01T00:00:00Z",
                    ),
                )
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val response = api.getLatestVersion("m1")

            assertTrue(response.isSuccessful)
            assertEquals("2.0.0", response.body()?.version)

            val sentRequest = server.takeRequest()
            assertTrue(sentRequest.path!!.startsWith("/api/v1/models/m1/versions/latest"))
        }
}
