package ai.octomil.wrapper

import ai.octomil.api.OctomilApi
import ai.octomil.api.dto.TelemetryV2BatchRequest
import ai.octomil.api.dto.TelemetryV2Event
import ai.octomil.api.dto.TelemetryV2Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the real HTTP [TelemetrySender] built by [Octomil.buildSender].
 */
class TelemetrySenderTest {

    private lateinit var mockApi: OctomilApi
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockApi = mockk()
        mockWebServer = MockWebServer()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    private fun makeBatch(
        eventCount: Int = 1,
        eventName: String = "inference.completed",
    ): TelemetryV2BatchRequest {
        val resource = TelemetryV2Resource(
            sdk = "android",
            sdkVersion = "1.0.0-test",
            deviceId = "test-device",
            platform = "android",
            orgId = "test-org",
        )
        val events = (0 until eventCount).map { i ->
            TelemetryV2Event(
                name = eventName,
                timestamp = "2026-02-27T14:00:00.000Z",
                attributes = mapOf(
                    "model.id" to JsonPrimitive("model-$i"),
                    "inference.duration_ms" to JsonPrimitive(5.0 + i),
                    "inference.success" to JsonPrimitive(true),
                ),
            )
        }
        return TelemetryV2BatchRequest(resource = resource, events = events)
    }

    // =========================================================================
    // sendBatch — success
    // =========================================================================

    @Test
    fun `sendBatch calls sendTelemetryV2 with correct payload`() = runTest {
        val requestSlot = slot<TelemetryV2BatchRequest>()
        coEvery { mockApi.sendTelemetryV2(capture(requestSlot)) } returns
            Response.success(Unit)

        val sender = buildSenderWithMockApi(mockApi)
        val batch = makeBatch(eventCount = 2)

        sender.sendBatch(batch)

        coVerify(exactly = 1) { mockApi.sendTelemetryV2(any()) }

        val captured = requestSlot.captured
        assertEquals(2, captured.events.size)
        assertEquals("inference.completed", captured.events[0].name)
        assertEquals("android", captured.resource.sdk)
    }

    // =========================================================================
    // sendBatch — HTTP failure throws
    // =========================================================================

    @Test
    fun `sendBatch throws IOException on non-2xx response`() = runTest {
        coEvery { mockApi.sendTelemetryV2(any()) } returns
            Response.error(500, okhttp3.ResponseBody.create(null, "Internal Server Error"))

        val sender = buildSenderWithMockApi(mockApi)

        assertFailsWith<IOException> {
            sender.sendBatch(makeBatch())
        }
    }

    // =========================================================================
    // sendBatch — funnel events
    // =========================================================================

    @Test
    fun `sendBatch handles funnel events`() = runTest {
        coEvery { mockApi.sendTelemetryV2(any()) } returns Response.success(Unit)

        val sender = buildSenderWithMockApi(mockApi)
        val batch = makeBatch(eventName = "funnel.pairing_started")

        sender.sendBatch(batch)

        coVerify(exactly = 1) { mockApi.sendTelemetryV2(any()) }
    }

    @Test
    fun `sendBatch throws IOException on 502 for funnel events`() = runTest {
        coEvery { mockApi.sendTelemetryV2(any()) } returns
            Response.error(502, okhttp3.ResponseBody.create(null, "Bad Gateway"))

        val sender = buildSenderWithMockApi(mockApi)

        assertFailsWith<IOException> {
            sender.sendBatch(makeBatch(eventName = "funnel.test"))
        }
    }

    // =========================================================================
    // Integration: buildTelemetryApi with MockWebServer
    // =========================================================================

    @Test
    fun `buildTelemetryApi sends auth header`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{}")
                .addHeader("Content-Type", "application/json"),
        )
        mockWebServer.start()

        val api = Octomil.buildTelemetryApi(
            serverUrl = mockWebServer.url("/").toString().trimEnd('/'),
            apiKey = "test-api-key-123",
        )

        val batch = makeBatch()
        val response = api.sendTelemetryV2(batch)
        assertTrue(response.isSuccessful)

        val recorded = mockWebServer.takeRequest()
        assertEquals("Bearer test-api-key-123", recorded.getHeader("Authorization"))
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.contains("api/v2/telemetry/events"))
    }

    // =========================================================================
    // Helper: wrap a mock OctomilApi in a TelemetrySender
    // =========================================================================

    private fun buildSenderWithMockApi(api: OctomilApi): TelemetrySender {
        return TelemetrySender { batch: TelemetryV2BatchRequest ->
            val response = api.sendTelemetryV2(batch)
            if (!response.isSuccessful) {
                throw IOException(
                    "Telemetry v2 batch upload failed: HTTP ${response.code()} ${response.message()}",
                )
            }
        }
    }
}
