package ai.edgeml.runtime

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for runtime adaptation API model serialization and deserialization.
 *
 * Verifies that [AdaptationRequest], [AdaptationRecommendation], [FallbackRequest],
 * and [FallbackRecommendation] correctly serialize to/from JSON with the expected
 * snake_case field names (via @SerialName).
 */
class RuntimeModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // =========================================================================
    // AdaptationRequest
    // =========================================================================

    @Test
    fun `AdaptationRequest serializes with snake_case field names`() {
        val request = AdaptationRequest(
            deviceId = "device-123",
            modelId = "model-456",
            batteryLevel = 45,
            thermalState = "nominal",
            currentFormat = "tensorflow_lite",
            currentExecutor = "gpu",
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"device_id\""), "Should use snake_case: device_id")
        assertTrue(encoded.contains("\"model_id\""), "Should use snake_case: model_id")
        assertTrue(encoded.contains("\"battery_level\""), "Should use snake_case: battery_level")
        assertTrue(encoded.contains("\"thermal_state\""), "Should use snake_case: thermal_state")
        assertTrue(encoded.contains("\"current_format\""), "Should use snake_case: current_format")
        assertTrue(encoded.contains("\"current_executor\""), "Should use snake_case: current_executor")
    }

    @Test
    fun `AdaptationRequest round-trips through JSON`() {
        val request = AdaptationRequest(
            deviceId = "device-abc",
            modelId = "model-xyz",
            batteryLevel = 72,
            thermalState = "fair",
            currentFormat = "tensorflow_lite",
            currentExecutor = "nnapi",
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<AdaptationRequest>(encoded)

        assertEquals(request, decoded)
    }

    @Test
    fun `AdaptationRequest deserializes from server JSON`() {
        val raw = """
        {
            "device_id": "d-1",
            "model_id": "m-1",
            "battery_level": 90,
            "thermal_state": "nominal",
            "current_format": "tensorflow_lite",
            "current_executor": "xnnpack"
        }
        """.trimIndent()

        val request = json.decodeFromString<AdaptationRequest>(raw)

        assertEquals("d-1", request.deviceId)
        assertEquals("m-1", request.modelId)
        assertEquals(90, request.batteryLevel)
        assertEquals("nominal", request.thermalState)
        assertEquals("tensorflow_lite", request.currentFormat)
        assertEquals("xnnpack", request.currentExecutor)
    }

    // =========================================================================
    // AdaptationRecommendation
    // =========================================================================

    @Test
    fun `AdaptationRecommendation serializes with snake_case field names`() {
        val rec = AdaptationRecommendation(
            recommendedExecutor = "gpu",
            recommendedComputeUnits = "gpu_and_cpu",
            throttleInference = false,
            reduceBatchSize = true,
        )

        val encoded = json.encodeToString(rec)

        assertTrue(encoded.contains("\"recommended_executor\""))
        assertTrue(encoded.contains("\"recommended_compute_units\""))
        assertTrue(encoded.contains("\"throttle_inference\""))
        assertTrue(encoded.contains("\"reduce_batch_size\""))
    }

    @Test
    fun `AdaptationRecommendation round-trips through JSON`() {
        val rec = AdaptationRecommendation(
            recommendedExecutor = "nnapi",
            recommendedComputeUnits = "npu",
            throttleInference = true,
            reduceBatchSize = false,
        )

        val encoded = json.encodeToString(rec)
        val decoded = json.decodeFromString<AdaptationRecommendation>(encoded)

        assertEquals(rec, decoded)
    }

    @Test
    fun `AdaptationRecommendation deserializes from server JSON`() {
        val raw = """
        {
            "recommended_executor": "xnnpack",
            "recommended_compute_units": "cpu_only",
            "throttle_inference": true,
            "reduce_batch_size": true
        }
        """.trimIndent()

        val rec = json.decodeFromString<AdaptationRecommendation>(raw)

        assertEquals("xnnpack", rec.recommendedExecutor)
        assertEquals("cpu_only", rec.recommendedComputeUnits)
        assertTrue(rec.throttleInference)
        assertTrue(rec.reduceBatchSize)
    }

    // =========================================================================
    // FallbackRequest
    // =========================================================================

    @Test
    fun `FallbackRequest serializes with snake_case field names`() {
        val request = FallbackRequest(
            deviceId = "device-123",
            modelId = "model-456",
            version = "1.2.0",
            failedFormat = "tensorflow_lite",
            failedExecutor = "nnapi",
            errorMessage = "NNAPI delegate not supported on this device",
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"device_id\""))
        assertTrue(encoded.contains("\"model_id\""))
        assertTrue(encoded.contains("\"version\""))
        assertTrue(encoded.contains("\"failed_format\""))
        assertTrue(encoded.contains("\"failed_executor\""))
        assertTrue(encoded.contains("\"error_message\""))
    }

    @Test
    fun `FallbackRequest round-trips through JSON`() {
        val request = FallbackRequest(
            deviceId = "device-abc",
            modelId = "model-xyz",
            version = "2.0.0",
            failedFormat = "onnx",
            failedExecutor = "gpu",
            errorMessage = "GPU delegate initialization failed",
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<FallbackRequest>(encoded)

        assertEquals(request, decoded)
    }

    @Test
    fun `FallbackRequest deserializes from server JSON`() {
        val raw = """
        {
            "device_id": "d-2",
            "model_id": "m-2",
            "version": "3.1.0",
            "failed_format": "coreml",
            "failed_executor": "ane",
            "error_message": "ANE not available"
        }
        """.trimIndent()

        val request = json.decodeFromString<FallbackRequest>(raw)

        assertEquals("d-2", request.deviceId)
        assertEquals("m-2", request.modelId)
        assertEquals("3.1.0", request.version)
        assertEquals("coreml", request.failedFormat)
        assertEquals("ane", request.failedExecutor)
        assertEquals("ANE not available", request.errorMessage)
    }

    // =========================================================================
    // FallbackRecommendation
    // =========================================================================

    @Test
    fun `FallbackRecommendation serializes with snake_case field names`() {
        val rec = FallbackRecommendation(
            fallbackFormat = "tensorflow_lite",
            fallbackExecutor = "xnnpack",
            downloadURL = "https://s3.example.com/model-fallback.tflite",
            runtimeConfig = mapOf("num_threads" to "2"),
        )

        val encoded = json.encodeToString(rec)

        assertTrue(encoded.contains("\"fallback_format\""))
        assertTrue(encoded.contains("\"fallback_executor\""))
        assertTrue(encoded.contains("\"download_url\""))
        assertTrue(encoded.contains("\"runtime_config\""))
    }

    @Test
    fun `FallbackRecommendation round-trips through JSON`() {
        val rec = FallbackRecommendation(
            fallbackFormat = "tensorflow_lite",
            fallbackExecutor = "gpu",
            downloadURL = "https://cdn.example.com/model-gpu.tflite",
            runtimeConfig = mapOf("enable_fp16" to "true", "num_threads" to "4"),
        )

        val encoded = json.encodeToString(rec)
        val decoded = json.decodeFromString<FallbackRecommendation>(encoded)

        assertEquals(rec, decoded)
    }

    @Test
    fun `FallbackRecommendation handles null runtimeConfig`() {
        val rec = FallbackRecommendation(
            fallbackFormat = "tensorflow_lite",
            fallbackExecutor = "xnnpack",
            downloadURL = "https://cdn.example.com/model-cpu.tflite",
            runtimeConfig = null,
        )

        val encoded = json.encodeToString(rec)
        val decoded = json.decodeFromString<FallbackRecommendation>(encoded)

        assertEquals(rec.fallbackFormat, decoded.fallbackFormat)
        assertEquals(rec.fallbackExecutor, decoded.fallbackExecutor)
        assertEquals(rec.downloadURL, decoded.downloadURL)
        assertNull(decoded.runtimeConfig)
    }

    @Test
    fun `FallbackRecommendation deserializes from server JSON`() {
        val raw = """
        {
            "fallback_format": "tensorflow_lite",
            "fallback_executor": "xnnpack",
            "download_url": "https://s3.example.com/model-cpu.tflite",
            "runtime_config": {
                "num_threads": "2",
                "enable_fp16": "false"
            }
        }
        """.trimIndent()

        val rec = json.decodeFromString<FallbackRecommendation>(raw)

        assertEquals("tensorflow_lite", rec.fallbackFormat)
        assertEquals("xnnpack", rec.fallbackExecutor)
        assertEquals("https://s3.example.com/model-cpu.tflite", rec.downloadURL)
        assertEquals(mapOf("num_threads" to "2", "enable_fp16" to "false"), rec.runtimeConfig)
    }

    @Test
    fun `FallbackRecommendation deserializes without optional runtimeConfig`() {
        val raw = """
        {
            "fallback_format": "tensorflow_lite",
            "fallback_executor": "gpu",
            "download_url": "https://cdn.example.com/model.tflite"
        }
        """.trimIndent()

        val rec = json.decodeFromString<FallbackRecommendation>(raw)

        assertEquals("tensorflow_lite", rec.fallbackFormat)
        assertEquals("gpu", rec.fallbackExecutor)
        assertEquals("https://cdn.example.com/model.tflite", rec.downloadURL)
        assertNull(rec.runtimeConfig)
    }
}
