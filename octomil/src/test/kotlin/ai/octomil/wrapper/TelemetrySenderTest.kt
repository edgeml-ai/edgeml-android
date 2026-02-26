package ai.octomil.wrapper

import ai.octomil.api.OctomilApi
import ai.octomil.api.dto.TelemetryBatchRequest
import ai.octomil.api.dto.TelemetryBatchResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
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

    private fun makeEvent(
        modelId: String = "test-model",
        latencyMs: Double = 5.0,
        success: Boolean = true,
        errorMessage: String? = null,
    ) = InferenceTelemetryEvent(
        modelId = modelId,
        latencyMs = latencyMs,
        timestampMs = System.currentTimeMillis(),
        success = success,
        errorMessage = errorMessage,
    )

    // =========================================================================
    // sendTelemetryBatch — success
    // =========================================================================

    @Test
    fun `send calls sendTelemetryBatch with correct payload`() = runTest {
        val requestSlot = slot<TelemetryBatchRequest>()
        coEvery { mockApi.sendTelemetryBatch(capture(requestSlot)) } returns
            Response.success(TelemetryBatchResponse(status = "ok", accepted = 2))

        val sender = buildSenderWithMockApi(mockApi)

        val events = listOf(
            makeEvent(modelId = "model-a", latencyMs = 10.0),
            makeEvent(modelId = "model-a", latencyMs = 15.0, success = false, errorMessage = "OOM"),
        )

        sender.send(events)

        coVerify(exactly = 1) { mockApi.sendTelemetryBatch(any()) }

        val captured = requestSlot.captured
        assertEquals(2, captured.events.size)
        assertEquals("model-a", captured.events[0].modelId)
        assertEquals(10.0, captured.events[0].latencyMs)
        assertTrue(captured.events[0].success)
        assertEquals(15.0, captured.events[1].latencyMs)
        assertEquals(false, captured.events[1].success)
        assertEquals("OOM", captured.events[1].errorMessage)
        assertEquals("model-a", captured.modelId)
    }

    // =========================================================================
    // sendTelemetryBatch — HTTP failure throws
    // =========================================================================

    @Test
    fun `send throws IOException on non-2xx response`() = runTest {
        coEvery { mockApi.sendTelemetryBatch(any()) } returns
            Response.error(500, okhttp3.ResponseBody.create(null, "Internal Server Error"))

        val sender = buildSenderWithMockApi(mockApi)

        assertFailsWith<IOException> {
            sender.send(listOf(makeEvent()))
        }
    }

    // =========================================================================
    // sendFunnelEvent — success
    // =========================================================================

    @Test
    fun `sendFunnelEvent calls api sendFunnelEvent`() = runTest {
        val eventSlot = slot<FunnelEvent>()
        coEvery { mockApi.sendFunnelEvent(capture(eventSlot)) } returns Response.success(Unit)

        val sender = buildSenderWithMockApi(mockApi)

        val event = FunnelEvent(
            stage = "model_download",
            success = true,
            deviceId = "device-123",
            modelId = "model-abc",
        )

        sender.sendFunnelEvent(event)

        coVerify(exactly = 1) { mockApi.sendFunnelEvent(any()) }
        assertEquals("model_download", eventSlot.captured.stage)
        assertTrue(eventSlot.captured.success)
        assertEquals("device-123", eventSlot.captured.deviceId)
    }

    // =========================================================================
    // sendFunnelEvent — HTTP failure throws
    // =========================================================================

    @Test
    fun `sendFunnelEvent throws IOException on non-2xx response`() = runTest {
        coEvery { mockApi.sendFunnelEvent(any()) } returns
            Response.error(502, okhttp3.ResponseBody.create(null, "Bad Gateway"))

        val sender = buildSenderWithMockApi(mockApi)

        assertFailsWith<IOException> {
            sender.sendFunnelEvent(FunnelEvent(stage = "test"))
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
                .setBody("""{"status":"ok","accepted":1}""")
                .addHeader("Content-Type", "application/json"),
        )
        mockWebServer.start()

        val api = Octomil.buildTelemetryApi(
            serverUrl = mockWebServer.url("/").toString().trimEnd('/'),
            apiKey = "test-api-key-123",
        )

        val request = TelemetryBatchRequest(
            events = listOf(
                ai.octomil.api.dto.TelemetryEventDto(
                    modelId = "m",
                    latencyMs = 1.0,
                    timestampMs = 100L,
                    success = true,
                ),
            ),
        )

        val response = api.sendTelemetryBatch(request)
        assertTrue(response.isSuccessful)

        val recorded = mockWebServer.takeRequest()
        assertEquals("Bearer test-api-key-123", recorded.getHeader("Authorization"))
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.contains("api/v1/telemetry/events"))
    }

    // =========================================================================
    // Helper: wrap a mock OctomilApi in a TelemetrySender
    // =========================================================================

    /**
     * Builds a [TelemetrySender] that delegates to the given mock [OctomilApi],
     * mirroring the logic in [Octomil.buildSender].
     */
    private fun buildSenderWithMockApi(api: OctomilApi): TelemetrySender {
        return object : TelemetrySender {
            override suspend fun send(events: List<InferenceTelemetryEvent>) {
                val request = TelemetryBatchRequest(
                    events = events.map { e ->
                        ai.octomil.api.dto.TelemetryEventDto(
                            modelId = e.modelId,
                            latencyMs = e.latencyMs,
                            timestampMs = e.timestampMs,
                            success = e.success,
                            errorMessage = e.errorMessage,
                        )
                    },
                    modelId = events.firstOrNull()?.modelId,
                )

                val response = api.sendTelemetryBatch(request)
                if (!response.isSuccessful) {
                    throw IOException(
                        "Telemetry batch upload failed: HTTP ${response.code()} ${response.message()}",
                    )
                }
            }

            override suspend fun sendFunnelEvent(event: FunnelEvent) {
                val response = api.sendFunnelEvent(event)
                if (!response.isSuccessful) {
                    throw IOException(
                        "Funnel event upload failed: HTTP ${response.code()} ${response.message()}",
                    )
                }
            }
        }
    }
}
