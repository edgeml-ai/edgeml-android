package ai.edgeml.training

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WeightExtractorTest {

    private lateinit var tempDir: File
    private lateinit var extractor: WeightExtractor

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "weight_extractor_test_${System.nanoTime()}")
        tempDir.mkdirs()
        extractor = WeightExtractor(ioDispatcher = Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // extractWeightDelta
    // =========================================================================

    @Test
    fun `extractWeightDelta returns valid bytes for existing files`() = runBlocking {
        val original = createTempModelFile("original.tflite")
        val updated = createTempModelFile("updated.tflite")

        val delta = extractor.extractWeightDelta(original.absolutePath, updated.absolutePath)

        // Since extractWeights returns empty maps, the delta should be a header-only payload
        // Header: magic (4) + version (4) + param count (4) = 12 bytes
        assertEquals(12, delta.size)
        verifyHeader(delta, parameterCount = 0)
    }

    @Test
    fun `extractWeightDelta throws when original file missing`() = runBlocking {
        val updated = createTempModelFile("updated.tflite")

        assertFailsWith<WeightExtractionException> {
            extractor.extractWeightDelta("/nonexistent/path.tflite", updated.absolutePath)
        }
        Unit
    }

    @Test
    fun `extractWeightDelta throws when updated file missing`() = runBlocking {
        val original = createTempModelFile("original.tflite")

        assertFailsWith<WeightExtractionException> {
            extractor.extractWeightDelta(original.absolutePath, "/nonexistent/path.tflite")
        }
        Unit
    }

    // =========================================================================
    // extractFullWeights
    // =========================================================================

    @Test
    fun `extractFullWeights returns valid bytes for existing file`() = runBlocking {
        val model = createTempModelFile("model.tflite")

        val weights = extractor.extractFullWeights(model.absolutePath)

        assertEquals(12, weights.size)
        verifyHeader(weights, parameterCount = 0)
    }

    @Test
    fun `extractFullWeights throws when file missing`() = runBlocking {
        assertFailsWith<WeightExtractionException> {
            extractor.extractFullWeights("/nonexistent/path.tflite")
        }
        Unit
    }

    // =========================================================================
    // Serialization format
    // =========================================================================

    @Test
    fun `serialized output starts with correct magic number`() = runBlocking {
        val model = createTempModelFile("model.tflite")
        val weights = extractor.extractFullWeights(model.absolutePath)

        val buffer = ByteBuffer.wrap(weights).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x50545448, buffer.getInt()) // "PTTH"
    }

    @Test
    fun `serialized output has correct format version`() = runBlocking {
        val model = createTempModelFile("model.tflite")
        val weights = extractor.extractFullWeights(model.absolutePath)

        val buffer = ByteBuffer.wrap(weights).order(ByteOrder.BIG_ENDIAN)
        buffer.getInt() // skip magic
        assertEquals(1, buffer.getInt()) // version
    }

    // =========================================================================
    // WeightExtractionException
    // =========================================================================

    @Test
    fun `WeightExtractionException carries message and cause`() {
        val cause = RuntimeException("disk full")
        val exception = WeightExtractionException("extraction failed", cause)

        assertEquals("extraction failed", exception.message)
        assertEquals(cause, exception.cause)
    }

    @Test
    fun `WeightExtractionException works without cause`() {
        val exception = WeightExtractionException("no cause")
        assertEquals("no cause", exception.message)
        assertEquals(null, exception.cause)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun createTempModelFile(name: String): File {
        val file = File(tempDir, name)
        // Write minimal content (not a real TFLite model, but enough for the extractor)
        file.writeBytes(ByteArray(64) { it.toByte() })
        return file
    }

    private fun verifyHeader(data: ByteArray, parameterCount: Int) {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x50545448, buffer.getInt(), "Magic number mismatch")
        assertEquals(1, buffer.getInt(), "Format version mismatch")
        assertEquals(parameterCount, buffer.getInt(), "Parameter count mismatch")
    }
}
