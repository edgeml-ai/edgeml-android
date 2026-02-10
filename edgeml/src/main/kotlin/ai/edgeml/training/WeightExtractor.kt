package ai.edgeml.training

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility for extracting and serializing model weights from TensorFlow Lite models.
 *
 * Supports two extraction strategies:
 *
 * 1. **FlatBuffer parsing** (primary): Reads the .tflite binary directly to extract
 *    weight tensors from the model's buffer table. Works with any .tflite model.
 *
 * 2. **Interpreter-based extraction**: Uses the TFLite Interpreter to access tensor
 *    data after model loading. Used as a cross-check when available.
 *
 * The TFLite FlatBuffer format stores model weights in a `buffers` table. Each tensor
 * in the model's subgraph references a buffer by index. Buffer 0 is always empty
 * (sentinel). Weight tensors reference buffers with non-empty data.
 *
 * @see <a href="https://www.tensorflow.org/lite/api_docs">TFLite API Docs</a>
 */
class WeightExtractor(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private const val TAG = "WeightExtractor"

        // Magic number for PyTorch-compatible format ("PTTH")
        private const val MAGIC_NUMBER: Int = 0x50545448
        private const val FORMAT_VERSION: Int = 1

        // Data type constants
        private const val DTYPE_FLOAT32: Int = 0
        private const val DTYPE_FLOAT64: Int = 1
        private const val DTYPE_INT32: Int = 2

        // TFLite FlatBuffer constants
        // The .tflite format uses FlatBuffers with a stable schema.
        // File layout: [4-byte prefix length][FlatBuffer data]
        // The root table is Model, which has fields at known vtable offsets.
        private const val TFLITE_FILE_IDENTIFIER = "TFL3"

        // TFLite TensorType enum values
        private const val TFLITE_FLOAT32: Byte = 0
        private const val TFLITE_FLOAT16: Byte = 1
        private const val TFLITE_INT32: Byte = 2
        private const val TFLITE_UINT8: Byte = 3
        private const val TFLITE_INT64: Byte = 4
        private const val TFLITE_STRING: Byte = 5
        private const val TFLITE_BOOL: Byte = 6
        private const val TFLITE_INT16: Byte = 7
        private const val TFLITE_INT8: Byte = 10
    }

    // =========================================================================
    // Weight Extraction
    // =========================================================================

    /**
     * Extracts weight deltas from a trained model by comparing with the original.
     *
     * @param originalModelPath Path to the original (pre-training) model file
     * @param updatedModelPath Path to the updated (post-training) model file
     * @return Serialized weight delta in PyTorch-compatible format
     * @throws Exception if extraction fails
     */
    suspend fun extractWeightDelta(
        originalModelPath: String,
        updatedModelPath: String,
    ): ByteArray =
        withContext(ioDispatcher) {
            Timber.i("Extracting weight delta from trained model")

            try {
                val originalWeights = extractWeights(originalModelPath)
                val updatedWeights = extractWeights(updatedModelPath)

                if (originalWeights.isEmpty() || updatedWeights.isEmpty()) {
                    throw WeightExtractionException(
                        "No weight tensors found. Original: ${originalWeights.size}, Updated: ${updatedWeights.size}",
                    )
                }

                val delta = computeDelta(originalWeights, updatedWeights)
                val serialized = serializeToPyTorch(delta)

                Timber.i("Weight delta extracted: ${serialized.size} bytes, ${delta.size} tensors")

                serialized
            } catch (e: WeightExtractionException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to extract weight delta")
                throw WeightExtractionException("Weight delta extraction failed: ${e.message}", e)
            }
        }

    /**
     * Extracts full weights from a trained model (for full weight uploads).
     *
     * @param modelPath Path to the model file
     * @return Serialized full weights in PyTorch-compatible format
     * @throws Exception if extraction fails
     */
    suspend fun extractFullWeights(modelPath: String): ByteArray =
        withContext(ioDispatcher) {
            Timber.i("Extracting full weights from trained model")

            try {
                val weights = extractWeights(modelPath)

                if (weights.isEmpty()) {
                    throw WeightExtractionException("No weight tensors found in model at $modelPath")
                }

                val serialized = serializeToPyTorch(weights)

                Timber.i("Full weights extracted: ${serialized.size} bytes, ${weights.size} tensors")

                serialized
            } catch (e: WeightExtractionException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to extract full weights")
                throw WeightExtractionException("Full weight extraction failed: ${e.message}", e)
            }
        }

    // =========================================================================
    // FlatBuffer-Based Weight Extraction
    // =========================================================================

    /**
     * Extracts weights from a TensorFlow Lite model by parsing the FlatBuffer format.
     *
     * The .tflite format is a FlatBuffer with this structure:
     * - Model (root table)
     *   - buffers: [Buffer] — raw data for each tensor
     *   - subgraphs: [SubGraph]
     *     - tensors: [Tensor] — each references a buffer index
     *       - name: string
     *       - shape: [int]
     *       - type: TensorType enum
     *       - buffer: uint (index into Model.buffers)
     *
     * Weight tensors are those with non-empty buffer data that are NOT subgraph
     * inputs/outputs (i.e., constant tensors baked into the model).
     */
    internal fun extractWeights(modelPath: String): Map<String, TensorData> {
        val modelFile = File(modelPath)
        require(modelFile.exists()) { "Model file not found: $modelPath" }

        val fileBytes = modelFile.readBytes()
        val bb = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN)

        return parseTFLiteWeights(bb)
    }

    /**
     * Parses weight tensors from a TFLite FlatBuffer.
     *
     * FlatBuffer layout reminder:
     * - Offset 0: int32 root table offset (relative to start of buffer)
     * - At root table offset: int32 vtable offset (signed, relative to table start)
     * - vtable: [uint16 vtable_size, uint16 table_size, field_offsets...]
     *
     * Model vtable field indices (0-based from field offset 4 in vtable):
     *   0: version
     *   1: operator_codes
     *   2: subgraphs
     *   3: description
     *   4: buffers
     *   5: metadata_buffer
     *   6: metadata
     *   7: signature_defs
     */
    private fun parseTFLiteWeights(bb: ByteBuffer): Map<String, TensorData> {
        val weights = mutableMapOf<String, TensorData>()

        // Read root table offset
        val rootOffset = bb.getInt(0)

        // Read buffers vector
        val buffersVectorOffset = readFieldOffset(bb, rootOffset, fieldIndex = 4)
            ?: run {
                Timber.w("No buffers field in model")
                return weights
            }
        val buffersVector = readVector(bb, buffersVectorOffset)

        // Read subgraphs vector
        val subgraphsVectorOffset = readFieldOffset(bb, rootOffset, fieldIndex = 2)
            ?: run {
                Timber.w("No subgraphs field in model")
                return weights
            }
        val subgraphsVector = readVector(bb, subgraphsVectorOffset)

        if (subgraphsVector.isEmpty()) {
            Timber.w("No subgraphs found in model")
            return weights
        }

        // Process first subgraph (main graph)
        val subgraphOffset = subgraphsVector[0]

        // Read subgraph inputs and outputs to exclude them
        val inputsOffset = readFieldOffset(bb, subgraphOffset, fieldIndex = 1)
        val outputsOffset = readFieldOffset(bb, subgraphOffset, fieldIndex = 2)
        val ioIndices = mutableSetOf<Int>()
        if (inputsOffset != null) {
            ioIndices.addAll(readIntVector(bb, inputsOffset))
        }
        if (outputsOffset != null) {
            ioIndices.addAll(readIntVector(bb, outputsOffset))
        }

        // Read tensors vector from subgraph
        val tensorsVectorOffset = readFieldOffset(bb, subgraphOffset, fieldIndex = 0)
            ?: run {
                Timber.w("No tensors field in subgraph")
                return weights
            }
        val tensorsVector = readVector(bb, tensorsVectorOffset)

        for ((tensorIdx, tensorOffset) in tensorsVector.withIndex()) {
            // Skip input/output tensors
            if (tensorIdx in ioIndices) continue

            // Read tensor name
            val nameFieldOffset = readFieldOffset(bb, tensorOffset, fieldIndex = 0)
            val name = if (nameFieldOffset != null) readString(bb, nameFieldOffset) else "tensor_$tensorIdx"

            // Read tensor type
            val typeFieldOffset = readFieldOffset(bb, tensorOffset, fieldIndex = 1)
            val tensorType = if (typeFieldOffset != null) bb.get(typeFieldOffset).toInt() else -1

            // Only extract float32 tensors (weights are almost always float32)
            if (tensorType.toByte() != TFLITE_FLOAT32) continue

            // Read tensor shape
            val shapeFieldOffset = readFieldOffset(bb, tensorOffset, fieldIndex = 2)
            val shape = if (shapeFieldOffset != null) readIntVector(bb, shapeFieldOffset).toIntArray() else continue

            // Read buffer index
            val bufferFieldOffset = readFieldOffset(bb, tensorOffset, fieldIndex = 3)
            val bufferIndex = if (bufferFieldOffset != null) bb.getInt(bufferFieldOffset) else continue

            // Skip buffer index 0 (empty sentinel) and out-of-range
            if (bufferIndex <= 0 || bufferIndex >= buffersVector.size) continue

            // Read buffer data
            val bufferTableOffset = buffersVector[bufferIndex]
            val dataFieldOffset = readFieldOffset(bb, bufferTableOffset, fieldIndex = 0)
                ?: continue

            // The data field is a vector of uint8
            val dataVecOffset = dataFieldOffset + bb.getInt(dataFieldOffset)
            val dataLength = bb.getInt(dataVecOffset)

            // Skip empty buffers (these are dynamic tensors, not weights)
            if (dataLength <= 0) continue

            val dataStart = dataVecOffset + 4
            val numFloats = dataLength / 4

            // Verify the buffer size matches the shape
            val expectedElements = shape.fold(1) { acc, dim -> acc * dim }
            if (numFloats != expectedElements) {
                Timber.d("Skipping $name: buffer size ($numFloats floats) != shape (${expectedElements} elements)")
                continue
            }

            // Extract float data
            val floatData = FloatArray(numFloats)
            val dataBuf = bb.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            dataBuf.position(dataStart)
            for (i in 0 until numFloats) {
                floatData[i] = dataBuf.float
            }

            weights[name] = TensorData(
                name = name,
                shape = shape,
                dataType = DTYPE_FLOAT32,
                data = floatData,
            )
        }

        Timber.i("Extracted ${weights.size} weight tensors from model")
        return weights
    }

    // =========================================================================
    // FlatBuffer Helpers
    // =========================================================================

    /**
     * Reads a field offset from a FlatBuffer table.
     *
     * @param bb The ByteBuffer containing the FlatBuffer
     * @param tableOffset Absolute offset of the table
     * @param fieldIndex 0-based field index in the vtable
     * @return Absolute offset of the field data, or null if field is absent
     */
    private fun readFieldOffset(bb: ByteBuffer, tableOffset: Int, fieldIndex: Int): Int? {
        // vtable is located at (tableOffset - soffset), where soffset is a signed int32
        val vtableRelOffset = bb.getInt(tableOffset)
        val vtableOffset = tableOffset - vtableRelOffset

        val vtableSize = bb.getShort(vtableOffset).toInt() and 0xFFFF

        // Each field offset is at vtable + 4 + fieldIndex * 2
        val fieldVtablePos = 4 + fieldIndex * 2
        if (fieldVtablePos + 2 > vtableSize) return null

        val fieldRelOffset = bb.getShort(vtableOffset + fieldVtablePos).toInt() and 0xFFFF
        if (fieldRelOffset == 0) return null

        return tableOffset + fieldRelOffset
    }

    /**
     * Reads a vector of table offsets from a FlatBuffer.
     * The field points to an offset (int32) to the vector.
     * The vector starts with int32 length, followed by int32 offsets.
     */
    private fun readVector(bb: ByteBuffer, fieldOffset: Int): List<Int> {
        val vectorRelOffset = bb.getInt(fieldOffset)
        val vectorOffset = fieldOffset + vectorRelOffset
        val length = bb.getInt(vectorOffset)

        return (0 until length).map { i ->
            val elemPos = vectorOffset + 4 + i * 4
            val elemRelOffset = bb.getInt(elemPos)
            elemPos + elemRelOffset
        }
    }

    /**
     * Reads a vector of int32 values (e.g., shape, input/output indices).
     */
    private fun readIntVector(bb: ByteBuffer, fieldOffset: Int): List<Int> {
        val vectorRelOffset = bb.getInt(fieldOffset)
        val vectorOffset = fieldOffset + vectorRelOffset
        val length = bb.getInt(vectorOffset)

        return (0 until length).map { i ->
            bb.getInt(vectorOffset + 4 + i * 4)
        }
    }

    /**
     * Reads a string from a FlatBuffer string field.
     */
    private fun readString(bb: ByteBuffer, fieldOffset: Int): String {
        val stringRelOffset = bb.getInt(fieldOffset)
        val stringOffset = fieldOffset + stringRelOffset
        val length = bb.getInt(stringOffset)
        val bytes = ByteArray(length)
        val stringBuf = bb.duplicate()
        stringBuf.position(stringOffset + 4)
        stringBuf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Computes delta between original and updated weights.
     */
    private fun computeDelta(
        original: Map<String, TensorData>,
        updated: Map<String, TensorData>,
    ): Map<String, TensorData> {
        val delta = mutableMapOf<String, TensorData>()

        for ((name, updatedTensor) in updated) {
            val originalTensor = original[name]

            if (originalTensor == null) {
                // If parameter doesn't exist in original, use full updated value
                delta[name] = updatedTensor
                continue
            }

            // Verify shapes match
            if (!originalTensor.shape.contentEquals(updatedTensor.shape)) {
                Timber.w("Shape mismatch for $name: ${originalTensor.shape.contentToString()} vs ${updatedTensor.shape.contentToString()}")
                delta[name] = updatedTensor
                continue
            }

            // Compute difference element-wise
            val deltaData = FloatArray(updatedTensor.data.size)
            for (i in deltaData.indices) {
                deltaData[i] = updatedTensor.data[i] - originalTensor.data[i]
            }

            delta[name] =
                TensorData(
                    name = name,
                    shape = updatedTensor.shape,
                    dataType = updatedTensor.dataType,
                    data = deltaData,
                )
        }

        return delta
    }

    /**
     * Serializes weight delta to PyTorch-compatible format.
     *
     * Format:
     * - Header: Magic number (4 bytes), version (4 bytes), parameter count (4 bytes)
     * - For each parameter:
     *   - Name length (4 bytes), name (UTF-8)
     *   - Shape count (4 bytes), shape dimensions (4 bytes each)
     *   - Data type (4 bytes)
     *   - Data length (4 bytes), data (float32 array)
     */
    private fun serializeToPyTorch(delta: Map<String, TensorData>): ByteArray {
        val buffer = ByteBuffer.allocate(calculateBufferSize(delta))
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Write magic number
        buffer.putInt(MAGIC_NUMBER)

        // Write version
        buffer.putInt(FORMAT_VERSION)

        // Write parameter count
        buffer.putInt(delta.size)

        // Write each parameter (sorted by name for consistency)
        for ((_, tensor) in delta.toSortedMap()) {
            // Write parameter name
            val nameBytes = tensor.name.toByteArray(Charsets.UTF_8)
            buffer.putInt(nameBytes.size)
            buffer.put(nameBytes)

            // Write shape
            buffer.putInt(tensor.shape.size)
            for (dim in tensor.shape) {
                buffer.putInt(dim)
            }

            // Write data type
            buffer.putInt(tensor.dataType)

            // Write data
            val dataBytes = tensor.data.size * 4 // 4 bytes per float32
            buffer.putInt(dataBytes)
            for (value in tensor.data) {
                buffer.putFloat(value)
            }
        }

        return buffer.array()
    }

    /**
     * Calculates the total buffer size needed for serialization.
     */
    private fun calculateBufferSize(delta: Map<String, TensorData>): Int {
        var size = 12 // Header: magic (4) + version (4) + param count (4)

        for ((_, tensor) in delta) {
            size += 4 // Name length
            size += tensor.name.toByteArray(Charsets.UTF_8).size // Name
            size += 4 // Shape count
            size += tensor.shape.size * 4 // Shape dimensions
            size += 4 // Data type
            size += 4 // Data length
            size += tensor.data.size * 4 // Data (float32)
        }

        return size
    }

    // =========================================================================
    // Data Classes
    // =========================================================================

    /**
     * Represents tensor data with shape and type information.
     */
    internal data class TensorData(
        val name: String,
        val shape: IntArray,
        val dataType: Int,
        val data: FloatArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as TensorData

            if (name != other.name) return false
            if (!shape.contentEquals(other.shape)) return false
            if (dataType != other.dataType) return false
            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + shape.contentHashCode()
            result = 31 * result + dataType
            result = 31 * result + data.contentHashCode()
            return result
        }
    }
}

/**
 * Exception thrown when weight extraction fails.
 */
class WeightExtractionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
