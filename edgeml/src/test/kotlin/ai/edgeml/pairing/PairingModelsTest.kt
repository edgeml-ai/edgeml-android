package ai.edgeml.pairing

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for pairing data model serialization and deserialization.
 */
class PairingModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // =========================================================================
    // PairingSession
    // =========================================================================

    @Test
    fun `PairingSession deserializes from server JSON`() {
        val raw = """
        {
            "id": "session-123",
            "code": "ABC123",
            "model_name": "mobilenet-v2",
            "model_version": "1.2.0",
            "status": "connected",
            "download_url": "https://s3.example.com/model.tflite",
            "download_format": "tensorflow_lite",
            "download_size_bytes": 4200000,
            "device_tier": "flagship",
            "quantization": "float16",
            "executor": "gpu"
        }
        """.trimIndent()

        val session = json.decodeFromString<PairingSession>(raw)

        assertEquals("session-123", session.id)
        assertEquals("ABC123", session.code)
        assertEquals("mobilenet-v2", session.modelName)
        assertEquals("1.2.0", session.modelVersion)
        assertEquals(PairingStatus.CONNECTED, session.status)
        assertEquals("https://s3.example.com/model.tflite", session.downloadUrl)
        assertEquals("tensorflow_lite", session.downloadFormat)
        assertEquals(4200000L, session.downloadSizeBytes)
        assertEquals("flagship", session.deviceTier)
        assertEquals("float16", session.quantization)
        assertEquals("gpu", session.executor)
    }

    @Test
    fun `PairingSession handles minimal JSON with null optionals`() {
        val raw = """
        {
            "id": "session-456",
            "code": "XYZ789",
            "model_name": "tiny-model",
            "status": "pending"
        }
        """.trimIndent()

        val session = json.decodeFromString<PairingSession>(raw)

        assertEquals("session-456", session.id)
        assertEquals("XYZ789", session.code)
        assertEquals("tiny-model", session.modelName)
        assertNull(session.modelVersion)
        assertEquals(PairingStatus.PENDING, session.status)
        assertNull(session.downloadUrl)
        assertNull(session.downloadFormat)
        assertNull(session.downloadSizeBytes)
    }

    @Test
    fun `PairingSession round-trips through JSON`() {
        val original = PairingSession(
            id = "s1",
            code = "CODE",
            modelName = "test-model",
            modelVersion = "2.0.0",
            status = PairingStatus.DEPLOYING,
            downloadUrl = "https://example.com/download",
            downloadFormat = "tensorflow_lite",
            downloadSizeBytes = 1024L,
            deviceTier = "mid_range",
            quantization = "int8",
            executor = "cpu",
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<PairingSession>(encoded)

        assertEquals(original, decoded)
    }

    // =========================================================================
    // PairingStatus
    // =========================================================================

    @Test
    fun `PairingStatus deserializes all server values`() {
        val statuses = listOf(
            "pending" to PairingStatus.PENDING,
            "connected" to PairingStatus.CONNECTED,
            "deploying" to PairingStatus.DEPLOYING,
            "done" to PairingStatus.DONE,
            "expired" to PairingStatus.EXPIRED,
            "cancelled" to PairingStatus.CANCELLED,
        )

        for ((serverValue, expected) in statuses) {
            val rawJson = """{"status":"$serverValue","id":"s","code":"c","model_name":"m"}"""
            val session = json.decodeFromString<PairingSession>(rawJson)
            assertEquals(expected, session.status, "Failed for status: $serverValue")
        }
    }

    // =========================================================================
    // DeploymentInfo
    // =========================================================================

    @Test
    fun `DeploymentInfo serializes correctly`() {
        val info = DeploymentInfo(
            modelName = "mobilenet-v2",
            modelVersion = "1.0.0",
            downloadUrl = "https://s3.example.com/model.tflite",
            format = "tensorflow_lite",
            quantization = "float16",
            executor = "gpu",
            sizeBytes = 5_000_000L,
        )

        val encoded = json.encodeToString(info)
        val decoded = json.decodeFromString<DeploymentInfo>(encoded)

        assertEquals(info, decoded)
    }

    // =========================================================================
    // BenchmarkReport
    // =========================================================================

    @Test
    fun `BenchmarkReport serializes all fields`() {
        val report = BenchmarkReport(
            modelName = "test-model",
            deviceName = "Google Pixel 8",
            chipFamily = "Tensor G3",
            ramGb = 8.0,
            osVersion = "14",
            ttftMs = 25.5,
            tpotMs = 12.3,
            tokensPerSecond = 81.3,
            p50LatencyMs = 11.0,
            p95LatencyMs = 18.5,
            p99LatencyMs = 22.0,
            memoryPeakBytes = 50_000_000L,
            inferenceCount = 11,
            modelLoadTimeMs = 150.0,
            coldInferenceMs = 25.5,
            warmInferenceMs = 12.3,
            batteryLevel = 85.0f,
            thermalState = "none",
        )

        val encoded = json.encodeToString(report)
        val decoded = json.decodeFromString<BenchmarkReport>(encoded)

        assertEquals(report, decoded)
    }

    @Test
    fun `BenchmarkReport handles null optional fields`() {
        val report = BenchmarkReport(
            modelName = "test-model",
            deviceName = "Samsung Galaxy A52",
            chipFamily = "exynos",
            ramGb = 4.0,
            osVersion = "13",
            ttftMs = 50.0,
            tpotMs = 30.0,
            tokensPerSecond = 33.3,
            p50LatencyMs = 28.0,
            p95LatencyMs = 45.0,
            p99LatencyMs = 55.0,
            memoryPeakBytes = 30_000_000L,
            inferenceCount = 11,
            modelLoadTimeMs = 300.0,
            coldInferenceMs = 50.0,
            warmInferenceMs = 30.0,
            batteryLevel = null,
            thermalState = null,
        )

        val encoded = json.encodeToString(report)
        val decoded = json.decodeFromString<BenchmarkReport>(encoded)

        assertEquals(report, decoded)
        assertNull(decoded.batteryLevel)
        assertNull(decoded.thermalState)
    }

    // =========================================================================
    // DeviceConnectRequest
    // =========================================================================

    @Test
    fun `DeviceConnectRequest serializes with default platform`() {
        val request = DeviceConnectRequest(
            deviceId = "abc123",
            deviceName = "Google Pixel 8 Pro",
            chipFamily = "Tensor G3",
            ramGb = 12.0,
            osVersion = "14",
            npuAvailable = true,
            gpuAvailable = true,
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<DeviceConnectRequest>(encoded)

        assertEquals("android", decoded.platform)
        assertEquals(request, decoded)
    }

    @Test
    fun `DeviceConnectRequest handles minimal fields`() {
        val request = DeviceConnectRequest(
            deviceId = "device-1",
            deviceName = "Unknown Device",
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<DeviceConnectRequest>(encoded)

        assertEquals("device-1", decoded.deviceId)
        assertEquals("android", decoded.platform)
        assertNull(decoded.chipFamily)
        assertNull(decoded.ramGb)
    }
}
