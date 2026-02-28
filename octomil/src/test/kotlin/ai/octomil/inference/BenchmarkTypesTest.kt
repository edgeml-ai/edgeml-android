package ai.octomil.inference

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BenchmarkTypesTest {

    private val json = Json { ignoreUnknownKeys = true }

    // =========================================================================
    // BenchmarkResult.ok computed property
    // =========================================================================

    @Test
    fun `ok is true when error is null`() {
        val result = BenchmarkResult(
            engineName = "tflite",
            tokensPerSecond = 42.0,
            ttftMs = 10.0,
            memoryMb = 128.0,
        )
        assertTrue(result.ok)
        assertNull(result.error)
    }

    @Test
    fun `ok is false when error is present`() {
        val result = BenchmarkResult(
            engineName = "tflite",
            tokensPerSecond = 0.0,
            ttftMs = 0.0,
            memoryMb = 0.0,
            error = "model not found",
        )
        assertFalse(result.ok)
    }

    // =========================================================================
    // BenchmarkResult serialization round-trip
    // =========================================================================

    @Test
    fun `BenchmarkResult serializes to snake_case and round-trips`() {
        val original = BenchmarkResult(
            engineName = "nnapi",
            tokensPerSecond = 55.5,
            ttftMs = 12.3,
            memoryMb = 256.0,
            error = null,
            metadata = mapOf("model_name" to "gemma-2b"),
        )
        val encoded = json.encodeToString(BenchmarkResult.serializer(), original)
        assertTrue(encoded.contains("\"engine_name\""))
        assertTrue(encoded.contains("\"tokens_per_second\""))
        assertTrue(encoded.contains("\"ttft_ms\""))
        assertTrue(encoded.contains("\"memory_mb\""))

        val decoded = json.decodeFromString(BenchmarkResult.serializer(), encoded)
        assertEquals(original, decoded)
        assertTrue(decoded.ok)
    }

    @Test
    fun `BenchmarkResult with error round-trips`() {
        val original = BenchmarkResult(
            engineName = "gpu",
            tokensPerSecond = 0.0,
            ttftMs = 0.0,
            memoryMb = 0.0,
            error = "delegate unavailable",
        )
        val encoded = json.encodeToString(BenchmarkResult.serializer(), original)
        val decoded = json.decodeFromString(BenchmarkResult.serializer(), encoded)
        assertEquals(original, decoded)
        assertFalse(decoded.ok)
    }

    // =========================================================================
    // DetectionResult serialization
    // =========================================================================

    @Test
    fun `DetectionResult round-trips`() {
        val original = DetectionResult(engine = "tflite", available = true, info = "v2.14")
        val encoded = json.encodeToString(DetectionResult.serializer(), original)
        val decoded = json.decodeFromString(DetectionResult.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // =========================================================================
    // RankedEngine serialization
    // =========================================================================

    @Test
    fun `RankedEngine round-trips`() {
        val result = BenchmarkResult(
            engineName = "tflite",
            tokensPerSecond = 30.0,
            ttftMs = 15.0,
            memoryMb = 64.0,
        )
        val original = RankedEngine(engine = "tflite", result = result)
        val encoded = json.encodeToString(RankedEngine.serializer(), original)
        val decoded = json.decodeFromString(RankedEngine.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // =========================================================================
    // InferenceMetrics serialization
    // =========================================================================

    @Test
    fun `InferenceMetrics round-trips`() {
        val original = InferenceMetrics(
            ttfcMs = 50.0,
            promptTokens = 128,
            totalTokens = 256,
            tokensPerSecond = 40.0,
            totalDurationMs = 6400.0,
            cacheHit = true,
            attentionBackend = "flash",
        )
        val encoded = json.encodeToString(InferenceMetrics.serializer(), original)
        assertTrue(encoded.contains("\"ttfc_ms\""))
        assertTrue(encoded.contains("\"attention_backend\""))
        val decoded = json.decodeFromString(InferenceMetrics.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // =========================================================================
    // GenerationChunk serialization
    // =========================================================================

    @Test
    fun `GenerationChunk round-trips with null finishReason`() {
        val original = GenerationChunk(
            text = "Hello",
            tokenCount = 1,
            tokensPerSecond = 35.0,
            finishReason = null,
        )
        val encoded = json.encodeToString(GenerationChunk.serializer(), original)
        val decoded = json.decodeFromString(GenerationChunk.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `GenerationChunk round-trips with finishReason`() {
        val original = GenerationChunk(
            text = " world",
            tokenCount = 1,
            tokensPerSecond = 35.0,
            finishReason = "stop",
        )
        val encoded = json.encodeToString(GenerationChunk.serializer(), original)
        assertTrue(encoded.contains("\"finish_reason\""))
        val decoded = json.decodeFromString(GenerationChunk.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // =========================================================================
    // CacheStats serialization
    // =========================================================================

    @Test
    fun `CacheStats round-trips`() {
        val original = CacheStats(
            hits = 90,
            misses = 10,
            hitRate = 0.9,
            entries = 50,
            memoryMb = 512.0,
        )
        val encoded = json.encodeToString(CacheStats.serializer(), original)
        assertTrue(encoded.contains("\"hit_rate\""))
        assertTrue(encoded.contains("\"memory_mb\""))
        val decoded = json.decodeFromString(CacheStats.serializer(), encoded)
        assertEquals(original, decoded)
    }
}
