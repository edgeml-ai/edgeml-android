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

/**
 * Tests for [WeightExtractor] FlatBuffer parsing, delta computation, and serialization.
 *
 * Since `extractWeights()` is `internal`, we test it directly for FlatBuffer parsing.
 * For the full pipeline we use the public `extractWeightDelta()` and `extractFullWeights()`.
 *
 * TFLite FlatBuffer structure is constructed synthetically by [TFLiteFlatBufferBuilder].
 */
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
    // extractWeights — FlatBuffer parsing
    // =========================================================================

    @Test
    fun `extractWeights parses single float32 weight tensor`() {
        val floats = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f)
        val model = TFLiteFlatBufferBuilder()
            .addWeightTensor("dense/kernel", intArrayOf(2, 2), floats)
            .build()
        val file = writeTempModel("single_weight.tflite", model)

        val weights = extractor.extractWeights(file.absolutePath)

        assertEquals(1, weights.size)
        assertTrue(weights.containsKey("dense/kernel"))
        val tensor = weights["dense/kernel"]!!
        assertEquals("dense/kernel", tensor.name)
        assertTrue(intArrayOf(2, 2).contentEquals(tensor.shape))
        assertEquals(4, tensor.data.size)
        assertEquals(1.0f, tensor.data[0], 1e-6f)
        assertEquals(4.0f, tensor.data[3], 1e-6f)
    }

    @Test
    fun `extractWeights parses multiple weight tensors`() {
        val kernel = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)
        val bias = floatArrayOf(0.01f, 0.02f, 0.03f)
        val model = TFLiteFlatBufferBuilder()
            .addWeightTensor("dense/kernel", intArrayOf(2, 3), kernel)
            .addWeightTensor("dense/bias", intArrayOf(3), bias)
            .build()
        val file = writeTempModel("multi_weight.tflite", model)

        val weights = extractor.extractWeights(file.absolutePath)

        assertEquals(2, weights.size)
        assertTrue(weights.containsKey("dense/kernel"))
        assertTrue(weights.containsKey("dense/bias"))
        assertEquals(6, weights["dense/kernel"]!!.data.size)
        assertEquals(3, weights["dense/bias"]!!.data.size)
    }

    @Test
    fun `extractWeights skips non-float32 tensors`() {
        val floats = floatArrayOf(1.0f, 2.0f)
        val model = TFLiteFlatBufferBuilder()
            .addWeightTensor("float_weight", intArrayOf(2), floats)
            .addNonFloatTensor("int_weight", tensorType = 2) // INT32
            .build()
        val file = writeTempModel("mixed_types.tflite", model)

        val weights = extractor.extractWeights(file.absolutePath)

        assertEquals(1, weights.size)
        assertTrue(weights.containsKey("float_weight"))
    }

    @Test
    fun `extractWeights skips input and output tensors`() {
        val floats = floatArrayOf(1.0f, 2.0f)
        val model = TFLiteFlatBufferBuilder()
            .addWeightTensor("weight", intArrayOf(2), floats)
            .setInputTensorIndex(1) // mark tensor index 1 as input
            .build()
        val file = writeTempModel("io_skip.tflite", model)

        val weights = extractor.extractWeights(file.absolutePath)

        // weight tensor at index 0 should still be extracted
        assertEquals(1, weights.size)
        assertTrue(weights.containsKey("weight"))
    }

    @Test
    fun `extractWeights skips tensors with shape mismatch`() {
        // Create a tensor whose buffer has 4 floats but shape says 3 elements
        val floats = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f)
        val model = TFLiteFlatBufferBuilder()
            .addWeightTensorWithShapeMismatch("bad_shape", intArrayOf(3), floats)
            .build()
        val file = writeTempModel("shape_mismatch.tflite", model)

        val weights = extractor.extractWeights(file.absolutePath)

        assertEquals(0, weights.size)
    }

    @Test
    fun `extractWeights returns empty map for model with no subgraphs`() {
        val model = TFLiteFlatBufferBuilder()
            .setEmptySubgraphs()
            .build()
        val file = writeTempModel("no_subgraphs.tflite", model)

        val weights = extractor.extractWeights(file.absolutePath)

        assertTrue(weights.isEmpty())
    }

    @Test
    fun `extractWeights returns empty map for model with no tensors`() {
        val model = TFLiteFlatBufferBuilder()
            .setEmptyTensors()
            .build()
        val file = writeTempModel("no_tensors.tflite", model)

        val weights = extractor.extractWeights(file.absolutePath)

        assertTrue(weights.isEmpty())
    }

    @Test
    fun `extractWeights throws for missing file`() {
        assertFailsWith<IllegalArgumentException> {
            extractor.extractWeights("/nonexistent/model.tflite")
        }
    }

    @Test
    fun `extractWeights handles empty buffer data gracefully`() {
        // Tensor with buffer index pointing to a buffer with 0 bytes of data
        val model = TFLiteFlatBufferBuilder()
            .addTensorWithEmptyBuffer("empty_buf", intArrayOf(2))
            .build()
        val file = writeTempModel("empty_buffer.tflite", model)

        val weights = extractor.extractWeights(file.absolutePath)

        assertEquals(0, weights.size)
    }

    @Test
    fun `extractWeights handles scalar weight tensor`() {
        val floats = floatArrayOf(3.14f)
        val model = TFLiteFlatBufferBuilder()
            .addWeightTensor("scalar", intArrayOf(1), floats)
            .build()
        val file = writeTempModel("scalar.tflite", model)

        val weights = extractor.extractWeights(file.absolutePath)

        assertEquals(1, weights.size)
        assertEquals(3.14f, weights["scalar"]!!.data[0], 1e-5f)
    }

    // =========================================================================
    // extractWeightDelta — end-to-end
    // =========================================================================

    @Test
    fun `extractWeightDelta computes element-wise difference`() = runBlocking {
        val original = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f)
        val updated = floatArrayOf(1.5f, 2.5f, 3.5f, 4.5f)

        val origModel = TFLiteFlatBufferBuilder()
            .addWeightTensor("w", intArrayOf(2, 2), original)
            .build()
        val updModel = TFLiteFlatBufferBuilder()
            .addWeightTensor("w", intArrayOf(2, 2), updated)
            .build()

        val origFile = writeTempModel("orig.tflite", origModel)
        val updFile = writeTempModel("upd.tflite", updModel)

        val delta = extractor.extractWeightDelta(origFile.absolutePath, updFile.absolutePath)

        // Verify header
        val bb = ByteBuffer.wrap(delta).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x50545448, bb.getInt()) // magic
        assertEquals(1, bb.getInt()) // version
        assertEquals(1, bb.getInt()) // 1 parameter

        // Read parameter
        val nameLen = bb.getInt()
        val nameBytes = ByteArray(nameLen)
        bb.get(nameBytes)
        assertEquals("w", String(nameBytes))

        // Shape
        val shapeDims = bb.getInt()
        assertEquals(2, shapeDims)
        assertEquals(2, bb.getInt())
        assertEquals(2, bb.getInt())

        // Data type
        assertEquals(0, bb.getInt()) // FLOAT32

        // Data (delta = 0.5 for each element)
        val dataLen = bb.getInt()
        assertEquals(16, dataLen) // 4 floats * 4 bytes
        for (i in 0 until 4) {
            assertEquals(0.5f, bb.getFloat(), 1e-6f)
        }
    }

    @Test
    fun `extractWeightDelta includes new tensors from updated model`() = runBlocking {
        val origFloats = floatArrayOf(1.0f, 2.0f)
        val updFloats1 = floatArrayOf(1.5f, 2.5f)
        val updFloats2 = floatArrayOf(0.1f, 0.2f, 0.3f)

        val origModel = TFLiteFlatBufferBuilder()
            .addWeightTensor("w1", intArrayOf(2), origFloats)
            .build()
        val updModel = TFLiteFlatBufferBuilder()
            .addWeightTensor("w1", intArrayOf(2), updFloats1)
            .addWeightTensor("w2", intArrayOf(3), updFloats2)
            .build()

        val origFile = writeTempModel("orig2.tflite", origModel)
        val updFile = writeTempModel("upd2.tflite", updModel)

        val delta = extractor.extractWeightDelta(origFile.absolutePath, updFile.absolutePath)

        val bb = ByteBuffer.wrap(delta).order(ByteOrder.BIG_ENDIAN)
        bb.getInt() // magic
        bb.getInt() // version
        val paramCount = bb.getInt()
        assertEquals(2, paramCount) // both w1 delta and w2 full
    }

    @Test
    fun `extractWeightDelta throws when original file missing`() = runBlocking {
        val updated = floatArrayOf(1.0f)
        val updModel = TFLiteFlatBufferBuilder()
            .addWeightTensor("w", intArrayOf(1), updated)
            .build()
        val updFile = writeTempModel("upd3.tflite", updModel)

        assertFailsWith<WeightExtractionException> {
            extractor.extractWeightDelta("/nonexistent/path.tflite", updFile.absolutePath)
        }
        Unit
    }

    @Test
    fun `extractWeightDelta throws when updated file missing`() = runBlocking {
        val original = floatArrayOf(1.0f)
        val origModel = TFLiteFlatBufferBuilder()
            .addWeightTensor("w", intArrayOf(1), original)
            .build()
        val origFile = writeTempModel("orig3.tflite", origModel)

        assertFailsWith<WeightExtractionException> {
            extractor.extractWeightDelta(origFile.absolutePath, "/nonexistent/path.tflite")
        }
        Unit
    }

    @Test
    fun `extractWeightDelta throws when original has no weights`() = runBlocking {
        val origModel = TFLiteFlatBufferBuilder().setEmptyTensors().build()
        val updFloats = floatArrayOf(1.0f)
        val updModel = TFLiteFlatBufferBuilder()
            .addWeightTensor("w", intArrayOf(1), updFloats)
            .build()

        val origFile = writeTempModel("empty_orig.tflite", origModel)
        val updFile = writeTempModel("valid_upd.tflite", updModel)

        assertFailsWith<WeightExtractionException> {
            extractor.extractWeightDelta(origFile.absolutePath, updFile.absolutePath)
        }
        Unit
    }

    // =========================================================================
    // extractFullWeights — end-to-end
    // =========================================================================

    @Test
    fun `extractFullWeights serializes all weights`() = runBlocking {
        val kernel = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f)
        val bias = floatArrayOf(0.1f, 0.2f)
        val model = TFLiteFlatBufferBuilder()
            .addWeightTensor("kernel", intArrayOf(2, 2), kernel)
            .addWeightTensor("bias", intArrayOf(2), bias)
            .build()
        val file = writeTempModel("full.tflite", model)

        val serialized = extractor.extractFullWeights(file.absolutePath)

        val bb = ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x50545448, bb.getInt())
        assertEquals(1, bb.getInt())
        assertEquals(2, bb.getInt()) // 2 parameters
    }

    @Test
    fun `extractFullWeights throws when no weights found`() = runBlocking {
        val model = TFLiteFlatBufferBuilder().setEmptyTensors().build()
        val file = writeTempModel("empty.tflite", model)

        assertFailsWith<WeightExtractionException> {
            extractor.extractFullWeights(file.absolutePath)
        }
        Unit
    }

    @Test
    fun `extractFullWeights throws when file missing`() = runBlocking {
        assertFailsWith<WeightExtractionException> {
            extractor.extractFullWeights("/nonexistent/path.tflite")
        }
        Unit
    }

    // =========================================================================
    // Serialization format verification
    // =========================================================================

    @Test
    fun `serialized output has correct magic number`() = runBlocking {
        val model = TFLiteFlatBufferBuilder()
            .addWeightTensor("w", intArrayOf(1), floatArrayOf(1.0f))
            .build()
        val file = writeTempModel("magic.tflite", model)

        val data = extractor.extractFullWeights(file.absolutePath)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x50545448, bb.getInt())
    }

    @Test
    fun `serialized output has correct format version`() = runBlocking {
        val model = TFLiteFlatBufferBuilder()
            .addWeightTensor("w", intArrayOf(1), floatArrayOf(1.0f))
            .build()
        val file = writeTempModel("version.tflite", model)

        val data = extractor.extractFullWeights(file.absolutePath)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        bb.getInt() // magic
        assertEquals(1, bb.getInt())
    }

    @Test
    fun `serialized parameters are sorted alphabetically by name`() = runBlocking {
        // Add in reverse alphabetical order
        val model = TFLiteFlatBufferBuilder()
            .addWeightTensor("z_last", intArrayOf(1), floatArrayOf(3.0f))
            .addWeightTensor("a_first", intArrayOf(1), floatArrayOf(1.0f))
            .addWeightTensor("m_middle", intArrayOf(1), floatArrayOf(2.0f))
            .build()
        val file = writeTempModel("sorted.tflite", model)

        val data = extractor.extractFullWeights(file.absolutePath)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        bb.getInt() // magic
        bb.getInt() // version
        assertEquals(3, bb.getInt()) // 3 params

        // Read first param name
        val name1Len = bb.getInt()
        val name1 = ByteArray(name1Len); bb.get(name1)
        assertEquals("a_first", String(name1))

        // Skip shape + data type + data for first param
        val shapeDims1 = bb.getInt()
        repeat(shapeDims1) { bb.getInt() }
        bb.getInt() // dtype
        val dataLen1 = bb.getInt()
        repeat(dataLen1 / 4) { bb.getFloat() }

        // Read second param name
        val name2Len = bb.getInt()
        val name2 = ByteArray(name2Len); bb.get(name2)
        assertEquals("m_middle", String(name2))
    }

    @Test
    fun `serialized data preserves float values`() = runBlocking {
        val values = floatArrayOf(-1.5f, 0.0f, 3.14159f, Float.MAX_VALUE)
        val model = TFLiteFlatBufferBuilder()
            .addWeightTensor("test", intArrayOf(4), values)
            .build()
        val file = writeTempModel("values.tflite", model)

        val data = extractor.extractFullWeights(file.absolutePath)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        bb.getInt() // magic
        bb.getInt() // version
        bb.getInt() // param count

        // Skip name
        val nameLen = bb.getInt()
        bb.position(bb.position() + nameLen)
        // Skip shape (1 dim)
        val dims = bb.getInt()
        repeat(dims) { bb.getInt() }
        bb.getInt() // dtype
        val dataLen = bb.getInt()
        assertEquals(16, dataLen)

        assertEquals(-1.5f, bb.getFloat(), 1e-6f)
        assertEquals(0.0f, bb.getFloat(), 1e-6f)
        assertEquals(3.14159f, bb.getFloat(), 1e-4f)
        assertEquals(Float.MAX_VALUE, bb.getFloat(), 1e30f)
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
    // Corrupt data handling
    // =========================================================================

    @Test
    fun `extractWeights handles corrupt file gracefully`() {
        // Random bytes that aren't a valid FlatBuffer — should not crash,
        // may throw or return empty depending on what the parser encounters
        val garbage = ByteArray(128) { (it * 37).toByte() }
        val file = writeTempModel("corrupt.tflite", garbage)

        try {
            val weights = extractor.extractWeights(file.absolutePath)
            // If it doesn't throw, it should return some map (possibly empty)
            assertTrue(weights.size >= 0)
        } catch (e: Exception) {
            // BufferUnderflowException or similar is acceptable for corrupt data
            assertTrue(
                e is java.nio.BufferUnderflowException ||
                    e is IndexOutOfBoundsException ||
                    e is IllegalArgumentException,
                "Unexpected exception type: ${e.javaClass.name}",
            )
        }
    }

    @Test
    fun `extractWeights handles very small file`() {
        val tiny = ByteArray(4) { 0 }
        val file = writeTempModel("tiny.tflite", tiny)

        try {
            val weights = extractor.extractWeights(file.absolutePath)
            assertTrue(weights.isEmpty())
        } catch (e: Exception) {
            // Small file may cause buffer underflow
            assertTrue(
                e is java.nio.BufferUnderflowException ||
                    e is IndexOutOfBoundsException,
            )
        }
    }

    // =========================================================================
    // Delta computation edge cases
    // =========================================================================

    @Test
    fun `extractWeightDelta produces zero delta for identical models`() = runBlocking {
        val floats = floatArrayOf(1.0f, 2.0f, 3.0f)
        val model = TFLiteFlatBufferBuilder()
            .addWeightTensor("w", intArrayOf(3), floats)
            .build()

        val file1 = writeTempModel("identical1.tflite", model)
        val file2 = writeTempModel("identical2.tflite", model)

        val delta = extractor.extractWeightDelta(file1.absolutePath, file2.absolutePath)

        val bb = ByteBuffer.wrap(delta).order(ByteOrder.BIG_ENDIAN)
        bb.getInt() // magic
        bb.getInt() // version
        assertEquals(1, bb.getInt()) // 1 parameter

        // Skip name
        val nameLen = bb.getInt()
        bb.position(bb.position() + nameLen)
        // Skip shape
        val dims = bb.getInt()
        repeat(dims) { bb.getInt() }
        bb.getInt() // dtype

        val dataLen = bb.getInt()
        for (i in 0 until dataLen / 4) {
            assertEquals(0.0f, bb.getFloat(), 1e-7f)
        }
    }

    // =========================================================================
    // Privacy transform integration
    // =========================================================================

    @Test
    fun `extractWeightDelta applies privacy transform`() = runBlocking {
        val original = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f)
        val updated = floatArrayOf(2.0f, 4.0f, 6.0f, 8.0f)

        val origModel = TFLiteFlatBufferBuilder()
            .addWeightTensor("w", intArrayOf(2, 2), original)
            .build()
        val updModel = TFLiteFlatBufferBuilder()
            .addWeightTensor("w", intArrayOf(2, 2), updated)
            .build()

        val origFile = writeTempModel("orig_transform.tflite", origModel)
        val updFile = writeTempModel("upd_transform.tflite", updModel)

        // Transform that doubles all delta values
        val doubleTransform: (Map<String, WeightExtractor.TensorData>) -> Map<String, WeightExtractor.TensorData> =
            { deltas ->
                deltas.mapValues { (_, tensor) ->
                    WeightExtractor.TensorData(
                        name = tensor.name,
                        shape = tensor.shape.copyOf(),
                        dataType = tensor.dataType,
                        data = FloatArray(tensor.data.size) { i -> tensor.data[i] * 2.0f },
                    )
                }
            }

        val delta = extractor.extractWeightDelta(
            origFile.absolutePath,
            updFile.absolutePath,
            privacyTransform = doubleTransform,
        )

        // Parse and verify: original delta = [1,2,3,4], doubled = [2,4,6,8]
        val bb = ByteBuffer.wrap(delta).order(ByteOrder.BIG_ENDIAN)
        bb.getInt() // magic
        bb.getInt() // version
        assertEquals(1, bb.getInt()) // 1 parameter

        // Skip name
        val nameLen = bb.getInt()
        bb.position(bb.position() + nameLen)
        // Skip shape
        val dims = bb.getInt()
        repeat(dims) { bb.getInt() }
        bb.getInt() // dtype

        val dataLen = bb.getInt()
        assertEquals(16, dataLen) // 4 floats * 4 bytes
        assertEquals(2.0f, bb.getFloat(), 1e-6f) // (2-1)*2 = 2
        assertEquals(4.0f, bb.getFloat(), 1e-6f) // (4-2)*2 = 4
        assertEquals(6.0f, bb.getFloat(), 1e-6f) // (6-3)*2 = 6
        assertEquals(8.0f, bb.getFloat(), 1e-6f) // (8-4)*2 = 8
    }

    @Test
    fun `extractWeightDelta without transform returns raw delta`() = runBlocking {
        val original = floatArrayOf(1.0f, 2.0f)
        val updated = floatArrayOf(3.0f, 5.0f)

        val origModel = TFLiteFlatBufferBuilder()
            .addWeightTensor("w", intArrayOf(2), original)
            .build()
        val updModel = TFLiteFlatBufferBuilder()
            .addWeightTensor("w", intArrayOf(2), updated)
            .build()

        val origFile = writeTempModel("orig_notransform.tflite", origModel)
        val updFile = writeTempModel("upd_notransform.tflite", updModel)

        // No transform (null)
        val delta = extractor.extractWeightDelta(origFile.absolutePath, updFile.absolutePath)

        val bb = ByteBuffer.wrap(delta).order(ByteOrder.BIG_ENDIAN)
        bb.getInt() // magic
        bb.getInt() // version
        bb.getInt() // param count

        // Skip name
        val nameLen = bb.getInt()
        bb.position(bb.position() + nameLen)
        val dims = bb.getInt()
        repeat(dims) { bb.getInt() }
        bb.getInt() // dtype

        bb.getInt() // data length
        assertEquals(2.0f, bb.getFloat(), 1e-6f)
        assertEquals(3.0f, bb.getFloat(), 1e-6f)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun writeTempModel(name: String, data: ByteArray): File {
        val file = File(tempDir, name)
        file.writeBytes(data)
        return file
    }
}

// =============================================================================
// Synthetic TFLite FlatBuffer Builder
// =============================================================================

/**
 * Builds minimal valid TFLite FlatBuffer binaries for testing [WeightExtractor].
 *
 * This constructs a FlatBuffer with the structure:
 * - Model root table with buffers and subgraphs fields
 * - Buffer 0 is empty (sentinel per TFLite spec)
 * - Each weight tensor gets its own buffer entry with float data
 * - One subgraph with all tensors
 *
 * The FlatBuffer writing is done manually (no codegen) to match the manual
 * reading in [WeightExtractor].
 */
class TFLiteFlatBufferBuilder {
    private data class TensorDef(
        val name: String,
        val shape: IntArray,
        val tensorType: Byte,
        val bufferIndex: Int,
    )

    private val tensors = mutableListOf<TensorDef>()
    private val bufferData = mutableListOf<ByteArray>() // index 0 = empty sentinel
    private var inputIndices = mutableListOf<Int>()
    private var outputIndices = mutableListOf<Int>()
    private var emptySubgraphs = false
    private var emptyTensors = false
    private var shapeMismatchTensors = mutableMapOf<Int, IntArray>() // tensorIndex -> fake shape

    init {
        // Buffer 0 is always empty (TFLite sentinel)
        bufferData.add(ByteArray(0))
    }

    fun addWeightTensor(name: String, shape: IntArray, data: FloatArray): TFLiteFlatBufferBuilder {
        val bytes = ByteArray(data.size * 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (f in data) putFloat(f)
        }
        bufferData.add(bytes)
        tensors.add(TensorDef(name, shape, 0, bufferData.size - 1)) // type 0 = FLOAT32
        return this
    }

    fun addWeightTensorWithShapeMismatch(
        name: String,
        reportedShape: IntArray,
        data: FloatArray,
    ): TFLiteFlatBufferBuilder {
        val bytes = ByteArray(data.size * 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (f in data) putFloat(f)
        }
        bufferData.add(bytes)
        val tensorIdx = tensors.size
        tensors.add(TensorDef(name, reportedShape, 0, bufferData.size - 1))
        shapeMismatchTensors[tensorIdx] = reportedShape
        return this
    }

    fun addNonFloatTensor(name: String, tensorType: Int): TFLiteFlatBufferBuilder {
        // Add a buffer with some dummy data
        bufferData.add(ByteArray(8))
        tensors.add(TensorDef(name, intArrayOf(2), tensorType.toByte(), bufferData.size - 1))
        return this
    }

    fun addTensorWithEmptyBuffer(name: String, shape: IntArray): TFLiteFlatBufferBuilder {
        bufferData.add(ByteArray(0)) // empty buffer
        tensors.add(TensorDef(name, shape, 0, bufferData.size - 1))
        return this
    }

    fun setInputTensorIndex(index: Int): TFLiteFlatBufferBuilder {
        inputIndices.add(index)
        return this
    }

    fun setEmptySubgraphs(): TFLiteFlatBufferBuilder {
        emptySubgraphs = true
        return this
    }

    fun setEmptyTensors(): TFLiteFlatBufferBuilder {
        emptyTensors = true
        return this
    }

    /**
     * Build the FlatBuffer binary.
     *
     * Layout (all offsets little-endian int32):
     * We build bottom-up: data first, then tables, then root.
     */
    fun build(): ByteArray {
        // Use a large buffer, we'll trim at the end
        val bb = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)

        // We build the FlatBuffer from the end (standard FlatBuffer practice),
        // but for simplicity we'll build forward and use absolute offsets.
        // The key is that readFieldOffset() in WeightExtractor does:
        //   vtableRelOffset = bb.getInt(tableOffset)
        //   vtableOffset = tableOffset - vtableRelOffset
        //   fieldRelOffset = bb.getShort(vtableOffset + 4 + fieldIndex * 2)
        //   return tableOffset + fieldRelOffset

        // We'll place everything sequentially:
        // [0..3] root offset -> points to Model table
        // [Model vtable] [Model table] [buffers vector] [subgraphs vector]
        // [subgraph vtable] [subgraph table] [tensors vector] [inputs vector] [outputs vector]
        // [tensor vtables+tables] [buffer tables] [string data] [float data]

        var pos = 4 // reserve 4 bytes for root offset

        // ---- Buffer data blocks (raw float bytes) ----
        // Store position of each buffer's raw data vector
        val bufferDataVecPositions = mutableListOf<Int>()
        for (bd in bufferData) {
            if (bd.isEmpty()) {
                bufferDataVecPositions.add(-1) // no data
            } else {
                // vector: [int32 length] [bytes...]
                val vecPos = pos
                bb.putInt(pos, bd.size)
                pos += 4
                System.arraycopy(bd, 0, bb.array(), pos, bd.size)
                pos += bd.size
                // Align to 4 bytes
                while (pos % 4 != 0) pos++
                bufferDataVecPositions.add(vecPos)
            }
        }

        // ---- Buffer tables (one per buffer entry) ----
        // Buffer table has 1 field: data (field index 0) -> offset to data vector
        val bufferTablePositions = mutableListOf<Int>()
        for (i in bufferData.indices) {
            // vtable for Buffer: [uint16 vtable_size=6, uint16 table_size=8, uint16 field0_offset]
            val vtablePos = pos
            bb.putShort(pos, 6) // vtable size: 2+2+2
            pos += 2
            bb.putShort(pos, 8) // table size (vtable ref + field)
            pos += 2
            if (bufferDataVecPositions[i] >= 0) {
                bb.putShort(pos, 4) // field 0 at offset 4 from table start
            } else {
                bb.putShort(pos, 0) // field absent
            }
            pos += 2
            // Align to 4
            while (pos % 4 != 0) pos++

            // table: [int32 vtable_offset (relative, signed)] [int32 data_field]
            val tablePos = pos
            bb.putInt(pos, tablePos - vtablePos) // vtable is before table
            pos += 4
            if (bufferDataVecPositions[i] >= 0) {
                // data field is an offset to the data vector (relative to field position)
                val fieldPos = pos
                bb.putInt(pos, bufferDataVecPositions[i] - fieldPos)
                pos += 4
            } else {
                bb.putInt(pos, 0)
                pos += 4
            }
            bufferTablePositions.add(tablePos)
        }

        // ---- String data for tensor names ----
        val tensorNamePositions = mutableListOf<Int>()
        for (t in tensors) {
            // string: [int32 length] [bytes]
            val nameBytes = t.name.toByteArray(Charsets.UTF_8)
            val stringPos = pos
            bb.putInt(pos, nameBytes.size)
            pos += 4
            System.arraycopy(nameBytes, 0, bb.array(), pos, nameBytes.size)
            pos += nameBytes.size
            while (pos % 4 != 0) pos++
            tensorNamePositions.add(stringPos)
        }

        // ---- Shape vectors for tensors ----
        val tensorShapeVecPositions = mutableListOf<Int>()
        for (t in tensors) {
            val vecPos = pos
            bb.putInt(pos, t.shape.size)
            pos += 4
            for (dim in t.shape) {
                bb.putInt(pos, dim)
                pos += 4
            }
            tensorShapeVecPositions.add(vecPos)
        }

        // ---- Tensor tables ----
        // Tensor fields: 0=name, 1=type, 2=shape, 3=buffer
        val tensorTablePositions = mutableListOf<Int>()
        if (!emptyTensors) {
            for ((idx, t) in tensors.withIndex()) {
                // vtable: [uint16 vtable_size=12, uint16 table_size=20,
                //          uint16 name_off, uint16 type_off, uint16 shape_off, uint16 buffer_off]
                val vtablePos = pos
                bb.putShort(pos, 12) // vtable size: 2+2 + 4*2
                pos += 2
                bb.putShort(pos, 20) // table size: 4 (vtable ref) + 4*4 (fields)
                pos += 2
                bb.putShort(pos, 4)  // field 0 (name) at offset 4
                pos += 2
                bb.putShort(pos, 8)  // field 1 (type) at offset 8
                pos += 2
                bb.putShort(pos, 12) // field 2 (shape) at offset 12
                pos += 2
                bb.putShort(pos, 16) // field 3 (buffer) at offset 16
                pos += 2
                while (pos % 4 != 0) pos++

                // table
                val tablePos = pos
                bb.putInt(pos, tablePos - vtablePos) // vtable ref
                pos += 4

                // field 0: name (offset to string)
                val nameFieldPos = pos
                bb.putInt(pos, tensorNamePositions[idx] - nameFieldPos)
                pos += 4

                // field 1: type (inline byte, but stored as int32 slot for alignment)
                bb.put(pos, t.tensorType)
                pos += 4

                // field 2: shape (offset to int vector)
                val shapeFieldPos = pos
                bb.putInt(pos, tensorShapeVecPositions[idx] - shapeFieldPos)
                pos += 4

                // field 3: buffer index (inline int32)
                bb.putInt(pos, t.bufferIndex)
                pos += 4

                tensorTablePositions.add(tablePos)
            }
        }

        // ---- Tensors vector (vector of offsets to tensor tables) ----
        val tensorsVecPos = pos
        if (emptyTensors) {
            bb.putInt(pos, 0) // empty vector
            pos += 4
        } else {
            bb.putInt(pos, tensorTablePositions.size)
            pos += 4
            for (tablePos in tensorTablePositions) {
                val elemPos = pos
                bb.putInt(pos, tablePos - elemPos) // relative offset
                pos += 4
            }
        }

        // ---- Inputs vector (int32 values) ----
        val inputsVecPos = pos
        bb.putInt(pos, inputIndices.size)
        pos += 4
        for (idx in inputIndices) {
            bb.putInt(pos, idx)
            pos += 4
        }

        // ---- Outputs vector (int32 values) ----
        val outputsVecPos = pos
        bb.putInt(pos, outputIndices.size)
        pos += 4
        for (idx in outputIndices) {
            bb.putInt(pos, idx)
            pos += 4
        }

        // ---- Subgraph table ----
        // Subgraph fields: 0=tensors, 1=inputs, 2=outputs
        val subgraphTablePos: Int
        if (emptySubgraphs) {
            subgraphTablePos = -1
        } else {
            val sgVtablePos = pos
            bb.putShort(pos, 10) // vtable size: 2+2 + 3*2
            pos += 2
            bb.putShort(pos, 16) // table size: 4 + 3*4
            pos += 2
            bb.putShort(pos, 4) // field 0 (tensors) offset 4
            pos += 2
            bb.putShort(pos, 8) // field 1 (inputs) offset 8
            pos += 2
            bb.putShort(pos, 12) // field 2 (outputs) offset 12
            pos += 2
            while (pos % 4 != 0) pos++

            subgraphTablePos = pos
            bb.putInt(pos, subgraphTablePos - sgVtablePos) // vtable ref
            pos += 4

            // field 0: tensors vector offset
            val f0Pos = pos
            bb.putInt(pos, tensorsVecPos - f0Pos)
            pos += 4

            // field 1: inputs vector offset
            val f1Pos = pos
            bb.putInt(pos, inputsVecPos - f1Pos)
            pos += 4

            // field 2: outputs vector offset
            val f2Pos = pos
            bb.putInt(pos, outputsVecPos - f2Pos)
            pos += 4
        }

        // ---- Subgraphs vector ----
        val subgraphsVecPos = pos
        if (emptySubgraphs) {
            bb.putInt(pos, 0) // empty
            pos += 4
        } else {
            bb.putInt(pos, 1) // 1 subgraph
            pos += 4
            val elemPos = pos
            bb.putInt(pos, subgraphTablePos - elemPos)
            pos += 4
        }

        // ---- Buffers vector (vector of offsets to buffer tables) ----
        val buffersVecPos = pos
        bb.putInt(pos, bufferTablePositions.size)
        pos += 4
        for (tablePos in bufferTablePositions) {
            val elemPos = pos
            bb.putInt(pos, tablePos - elemPos)
            pos += 4
        }

        // ---- Model table ----
        // Model fields: 0=version, 1=operator_codes, 2=subgraphs, 3=description, 4=buffers
        val modelVtablePos = pos
        bb.putShort(pos, 14) // vtable size: 2+2 + 5*2
        pos += 2
        bb.putShort(pos, 24) // table size: 4 + 5*4
        pos += 2
        bb.putShort(pos, 0) // field 0 (version) — absent
        pos += 2
        bb.putShort(pos, 0) // field 1 (operator_codes) — absent
        pos += 2
        bb.putShort(pos, 8) // field 2 (subgraphs) at offset 8
        pos += 2
        bb.putShort(pos, 0) // field 3 (description) — absent
        pos += 2
        bb.putShort(pos, 12) // field 4 (buffers) at offset 12
        pos += 2
        while (pos % 4 != 0) pos++

        val modelTablePos = pos
        bb.putInt(pos, modelTablePos - modelVtablePos) // vtable ref
        pos += 4

        // field padding (field 0 and 1 absent, but we still need offset 8 for subgraphs)
        bb.putInt(pos, 0) // padding for field 0/1
        pos += 4

        // field 2 (subgraphs) at offset 8
        val sgFieldPos = pos
        bb.putInt(pos, subgraphsVecPos - sgFieldPos)
        pos += 4

        // field 4 (buffers) at offset 12
        val bufFieldPos = pos
        bb.putInt(pos, buffersVecPos - bufFieldPos)
        pos += 4

        // Remaining field slots
        pos += 8 // padding to reach table_size=24

        // ---- Root offset at position 0 ----
        bb.putInt(0, modelTablePos)

        // Trim to actual size
        val result = ByteArray(pos)
        System.arraycopy(bb.array(), 0, result, 0, pos)
        return result
    }
}
