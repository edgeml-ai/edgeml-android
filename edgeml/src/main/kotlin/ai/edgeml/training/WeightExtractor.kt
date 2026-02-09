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
 * ## Current Limitations
 *
 * As of TensorFlow Lite 2.4+ and LiteRT (late 2025), direct weight extraction
 * from .tflite models on-device is not supported through public APIs. The legacy
 * tensor introspection methods (getTensor, Tensor.read) have been removed.
 *
 * ## Production Alternatives
 *
 * For production federated learning deployments, consider:
 *
 * 1. **Server-Side Weight Extraction**: Have clients upload trained models to server,
 *    where PyTorch/TensorFlow can easily extract weights
 *
 * 2. **Training Signature with Updatable Layers**: Use TFLite models with training
 *    signatures that explicitly expose updatable parameters
 *
 * 3. **Custom Training Loop**: Implement on-device training using frameworks that
 *    provide weight access (e.g., PyTorch Mobile, TensorFlow Mobile)
 *
 * 4. **FlatBuffer Schema Parsing**: Parse .tflite file format directly using
 *    TensorFlow Lite schema definitions (requires schema .fbs files and code generation)
 *
 * ## Current Implementation
 *
 * This implementation returns empty weight maps with appropriate logging.
 * For demo/testing purposes, you can modify this to use server-side extraction
 * or implement one of the alternatives above.
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
    ): ByteArray = withContext(ioDispatcher) {
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
    ): ByteArray = withContext(ioDispatcher) {
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
     * Extracts weights from a TensorFlow Lite model.
     *
     * Note: Direct weight extraction is not supported in current TFLite versions.
     * This method returns an empty map. For production use, implement one of:
     * - Server-side weight extraction (upload model, extract on server)
     * - FlatBuffer schema parsing (requires .fbs schema and code generation)
     * - Training signatures with explicit updatable parameters
     */
    private fun extractWeights(modelPath: String): Map<String, TensorData> {
        val weights = mutableMapOf<String, TensorData>()

        val modelFile = File(modelPath)
        require(modelFile.exists()) { "Model file not found: $modelPath" }

        Timber.w("Direct weight extraction not supported in TFLite 2.4+/LiteRT")
        Timber.i("Returning empty weights - implement server-side extraction for production")

        // For production federated learning:
        // 1. Upload trained model to server
        // 2. Extract weights server-side using PyTorch/TensorFlow
        // 3. Compute deltas and aggregate

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
