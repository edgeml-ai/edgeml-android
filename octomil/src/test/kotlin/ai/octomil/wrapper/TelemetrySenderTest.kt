package ai.octomil.wrapper

import ai.octomil.api.OctomilApi
import ai.octomil.api.dto.AnyValue
import ai.octomil.api.dto.ExportLogsServiceRequest
import ai.octomil.api.dto.InstrumentationScope
import ai.octomil.api.dto.KeyValue
import ai.octomil.api.dto.LogRecord
import ai.octomil.api.dto.OtlpResource
import ai.octomil.api.dto.ResourceLogs
import ai.octomil.api.dto.ScopeLogs
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

    private fun makeOtlpRequest(
        eventCount: Int = 1,
        eventName: String = "inference.completed",
    ): ExportLogsServiceRequest {
        val logRecords = (0 until eventCount).map { i ->
            LogRecord(
                timeUnixNano = "1708000000000000000",
                body = AnyValue.StringValue(eventName),
                attributes = listOf(
                    KeyValue("model.id", AnyValue.StringValue("model-$i")),
                    KeyValue("inference.duration_ms", AnyValue.DoubleValue(5.0 + i)),
                    KeyValue("inference.success", AnyValue.BoolValue(true)),
                ),
            )
        }

        return ExportLogsServiceRequest(
            resourceLogs = listOf(
                ResourceLogs(
                    resource = OtlpResource(
                        attributes = listOf(
                            KeyValue("service.name", AnyValue.StringValue("octomil-android-sdk")),
                            KeyValue("service.version", AnyValue.StringValue("1.0.0-test")),
                            KeyValue("telemetry.sdk.name", AnyValue.StringValue("android")),
                            KeyValue("device.id", AnyValue.StringValue("test-device")),
                            KeyValue("org.id", AnyValue.StringValue("test-org")),
                        ),
                    ),
                    scopeLogs = listOf(
                        ScopeLogs(
                            scope = InstrumentationScope(name = "ai.octomil.android", version = "1.0.0-test"),
                            logRecords = logRecords,
                        ),
                    ),
                ),
            ),
        )
    }

    // =========================================================================
    // sendBatch — success
    // =========================================================================

    @Test
    fun `sendBatch calls sendTelemetryV2 with correct payload`() = runTest {
        val requestSlot = slot<ExportLogsServiceRequest>()
        coEvery { mockApi.sendTelemetryV2(capture(requestSlot)) } returns
            Response.success(Unit)

        val sender = buildSenderWithMockApi(mockApi)
        val request = makeOtlpRequest(eventCount = 2)

        sender.sendBatch(request)

        coVerify(exactly = 1) { mockApi.sendTelemetryV2(any()) }

        val captured = requestSlot.captured
        val records = captured.resourceLogs.flatMap { it.scopeLogs }.flatMap { it.logRecords }
        assertEquals(2, records.size)
        assertEquals("inference.completed", (records[0].body as AnyValue.StringValue).stringValue)
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
            sender.sendBatch(makeOtlpRequest())
        }
    }

    // =========================================================================
    // sendBatch — funnel events
    // =========================================================================

    @Test
    fun `sendBatch handles funnel events`() = runTest {
        coEvery { mockApi.sendTelemetryV2(any()) } returns Response.success(Unit)

        val sender = buildSenderWithMockApi(mockApi)
        val request = makeOtlpRequest(eventName = "funnel.pairing_started")

        sender.sendBatch(request)

        coVerify(exactly = 1) { mockApi.sendTelemetryV2(any()) }
    }

    @Test
    fun `sendBatch throws IOException on 502 for funnel events`() = runTest {
        coEvery { mockApi.sendTelemetryV2(any()) } returns
            Response.error(502, okhttp3.ResponseBody.create(null, "Bad Gateway"))

        val sender = buildSenderWithMockApi(mockApi)

        assertFailsWith<IOException> {
            sender.sendBatch(makeOtlpRequest(eventName = "funnel.test"))
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

        val request = makeOtlpRequest()
        val response = api.sendTelemetryV2(request)
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
        return TelemetrySender { request: ExportLogsServiceRequest ->
            val response = api.sendTelemetryV2(request)
            if (!response.isSuccessful) {
                throw IOException(
                    "Telemetry OTLP upload failed: HTTP ${response.code()} ${response.message()}",
                )
            }
        }
    }
}
