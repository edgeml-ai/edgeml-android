package ai.edgeml.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Describes a single tensor in a model's input/output contract.
 *
 * Received from the server when model metadata is fetched. The [shape]
 * list uses `null` to represent dynamic dimensions (typically the batch
 * dimension).
 *
 * Example server JSON:
 * ```json
 * {"name": "input_0", "dtype": "float32", "shape": [null, 224, 224, 3]}
 * ```
 */
@Serializable
data class TensorSpec(
    /** Tensor name (e.g., "input_0", "output_0"). */
    @SerialName("name")
    val name: String,
    /** Data type (e.g., "float32", "int8"). */
    @SerialName("dtype")
    val dtype: String = "float32",
    /** Tensor shape. Null entries represent dynamic dimensions (e.g., batch size). */
    @SerialName("shape")
    val shape: List<Int?>,
    /** Optional human-readable description. */
    @SerialName("description")
    val description: String? = null,
) {
    /**
     * The number of fixed (non-dynamic) elements in this tensor.
     *
     * Dynamic dimensions (null) are excluded from the product. For a shape
     * like `[null, 224, 224, 3]`, this returns `224 * 224 * 3 = 150528`.
     *
     * Returns 0 if all dimensions are dynamic or the shape is empty.
     */
    val fixedElementCount: Int
        get() {
            val fixed = shape.filterNotNull()
            if (fixed.isEmpty()) return 0
            return fixed.fold(1) { acc, dim -> acc * dim }
        }

    /** True if any dimension is null (dynamic). */
    val hasDynamicDimensions: Boolean
        get() = shape.any { it == null }
}

/**
 * Server-provided contract describing a model's expected inputs and outputs.
 *
 * This is auto-extracted by the server when a model is uploaded and returned
 * alongside model version metadata. The SDK uses it to validate inference
 * inputs before passing them to the TFLite interpreter, providing clearer
 * error messages than the raw interpreter errors.
 *
 * When no contract is available (older servers, or models without extracted
 * metadata), validation is skipped entirely for backwards compatibility.
 */
@Serializable
data class ServerModelContract(
    /** Input tensor specifications. */
    @SerialName("inputs")
    val inputs: List<TensorSpec> = emptyList(),
    /** Output tensor specifications. */
    @SerialName("outputs")
    val outputs: List<TensorSpec> = emptyList(),
) {
    /**
     * Validate that [input] has the correct number of elements for this
     * contract's first input tensor.
     *
     * @param input The float array to validate.
     * @return null on success, or a descriptive error message on failure.
     */
    fun validateInput(input: FloatArray): String? {
        if (inputs.isEmpty()) return null

        val spec = inputs.first()
        val expectedSize = spec.fixedElementCount
        if (expectedSize <= 0) return null // all-dynamic or empty shape, can't validate

        if (input.size != expectedSize) {
            return "Input size mismatch: expected $expectedSize elements " +
                "(shape ${spec.shape}), got ${input.size}"
        }
        return null
    }

    /**
     * Validate that [input] has the correct number of elements, throwing
     * an [IllegalArgumentException] with a descriptive message on failure.
     *
     * @param input The float array to validate.
     * @throws IllegalArgumentException if the input size doesn't match.
     */
    fun requireValidInput(input: FloatArray) {
        val error = validateInput(input)
        if (error != null) {
            throw IllegalArgumentException(error)
        }
    }
}
