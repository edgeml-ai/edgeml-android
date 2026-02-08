package ai.edgeml.training

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Utility for extracting and serializing model weights from TensorFlow Lite models.
 *
 * ## Implementation Approach
 *
 * Uses FlatBuffer parsing to directly access model tensors and their buffers.
 * This approach is compatible with TensorFlow Lite 2.4+ and LiteRT, where the
 * legacy direct tensor introspection APIs (getTensor, Tensor.read) were removed.
 *
 * ## LiteRT Migration Notes
 *
 * As of late 2025, TensorFlow Lite is transitioning to LiteRT with significant
 * API changes focused on performance. The FlatBuffer schema classes in
 * `org.tensorflow.lite.schema` remain available and provide stable access to
 * model structure and weights.
 *
 * ## Weight Extraction Process
 *
 * 1. Parse .tflite file using FlatBuffer Model schema
 * 2. Access subgraph tensors and their associated buffers
 * 3. Extract float32 weight data from non-empty buffers
 * 4. Compute deltas or serialize full weights in PyTorch-compatible format
 *
 * @see org.tensorflow.lite.schema.Model
 * @see org.tensorflow.lite.schema.SubGraph
 */
class WeightExtractor {

    companion object {
        private const val TAG = "WeightExtractor"

        // Magic number for PyTorch-compatible format ("PTTH")
        private const val MAGIC_NUMBER: Int = 0x50545448
        private const val FORMAT_VERSION: Int = 1

        // Data type constants
        private const val DTYPE_FLOAT32: Int = 0
        private const val DTYPE_FLOAT64: Int = 1
        private const val DTYPE_INT32: Int = 2
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
    ): ByteArray = withContext(Dispatchers.IO) {
        Timber.i("Extracting weight delta from trained model")

        try {
            // Extract weights from both models
            val originalWeights = extractWeights(originalModelPath)
            val updatedWeights = extractWeights(updatedModelPath)

            // Compute delta (updated - original)
            val delta = computeDelta(originalWeights, updatedWeights)

            // Serialize to PyTorch format
            val serialized = serializeToPyTorch(delta)

            Timber.i("Weight delta extracted: ${serialized.size} bytes")

            serialized
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
    suspend fun extractFullWeights(
        modelPath: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        Timber.i("Extracting full weights from trained model")

        try {
            // Extract weights
            val weights = extractWeights(modelPath)

            // Serialize to PyTorch format
            val serialized = serializeToPyTorch(weights)

            Timber.i("Full weights extracted: ${serialized.size} bytes")

            serialized
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract full weights")
            throw WeightExtractionException("Full weight extraction failed: ${e.message}", e)
        }
    }

    // =========================================================================
    // Private Methods
    // =========================================================================

    /**
     * Extracts weights from a TensorFlow Lite model using FlatBuffer parsing.
     *
     * Parses the .tflite file format directly to access model tensors and their buffers.
     * This approach works with TFLite 2.4+ where direct tensor introspection was removed.
     */
    private fun extractWeights(modelPath: String): Map<String, TensorData> {
        val weights = mutableMapOf<String, TensorData>()

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            throw IllegalArgumentException("Model file not found: $modelPath")
        }

        try {
            // Load model file as ByteBuffer
            val modelBuffer = loadModelFile(modelFile)

            // Parse FlatBuffer schema - Model is the root type
            val model = org.tensorflow.lite.schema.Model.getRootAsModel(modelBuffer)

            // Get the first subgraph (main computation graph)
            if (model.subgraphsLength() == 0) {
                Timber.w("Model has no subgraphs")
                return weights
            }

            val subgraph = model.subgraphs(0)
            val tensorCount = subgraph.tensorsLength()

            Timber.d("Extracting weights from $tensorCount tensors")

            // Iterate through all tensors in the subgraph
            for (i in 0 until tensorCount) {
                val tensor = subgraph.tensors(i)
                val tensorName = tensor.name() ?: "tensor_$i"

                // Get buffer index (points to weight data)
                val bufferIndex = tensor.buffer()

                // Buffer 0 is reserved and empty in TFLite
                if (bufferIndex == 0) {
                    continue
                }

                // Get the buffer containing tensor data
                if (bufferIndex >= model.buffersLength()) {
                    Timber.w("Invalid buffer index $bufferIndex for tensor $tensorName")
                    continue
                }

                val buffer = model.buffers(bufferIndex)
                val dataBuffer = buffer.dataAsByteBuffer()

                // Skip tensors without data (e.g., inputs, outputs, intermediates)
                if (dataBuffer == null || dataBuffer.remaining() == 0) {
                    continue
                }

                // Extract tensor shape
                val shapeLength = tensor.shapeLength()
                val shape = IntArray(shapeLength) { tensor.shape(it) }

                // Only process FLOAT32 tensors (most common for weights)
                val tensorType = tensor.type()
                if (tensorType != org.tensorflow.lite.schema.TensorType.FLOAT32) {
                    Timber.d("Skipping non-float32 tensor: $tensorName (type=$tensorType)")
                    continue
                }

                // Parse float data from buffer
                dataBuffer.order(ByteOrder.LITTLE_ENDIAN)
                val floatBuffer = dataBuffer.asFloatBuffer()
                val floatData = FloatArray(floatBuffer.remaining())
                floatBuffer.get(floatData)

                // Store extracted tensor
                weights[tensorName] = TensorData(
                    name = tensorName,
                    shape = shape,
                    dataType = DTYPE_FLOAT32,
                    data = floatData
                )

                Timber.d("Extracted tensor: $tensorName, shape=${shape.contentToString()}, values=${floatData.size}")
            }

            Timber.i("Successfully extracted ${weights.size} weight tensors")

        } catch (e: Exception) {
            Timber.e(e, "Failed to extract weights using FlatBuffer parsing")
            throw WeightExtractionException("FlatBuffer weight extraction failed: ${e.message}", e)
        }

        return weights
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

            delta[name] = TensorData(
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

    /**
     * Loads a model file into a memory-mapped buffer.
     */
    private fun loadModelFile(file: File): java.nio.MappedByteBuffer {
        return file.inputStream().channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
    }

    // =========================================================================
    // Data Classes
    // =========================================================================

    /**
     * Represents tensor data with shape and type information.
     */
    private data class TensorData(
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
