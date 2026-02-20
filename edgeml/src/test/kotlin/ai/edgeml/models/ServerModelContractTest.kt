package ai.edgeml.models

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ServerModelContract] and [TensorSpec].
 *
 * Covers:
 * - JSON deserialization from server payloads
 * - Input validation: correct size passes, wrong size fails
 * - Dynamic dimensions handled correctly
 * - No contract = no validation (backwards compatible)
 * - Error messages are descriptive
 */
class ServerModelContractTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // =========================================================================
    // TensorSpec
    // =========================================================================

    @Test
    fun `TensorSpec deserializes from server JSON`() {
        val jsonStr = """
            {"name": "input_0", "dtype": "float32", "shape": [null, 224, 224, 3], "description": "Image input"}
        """.trimIndent()

        val spec = json.decodeFromString<TensorSpec>(jsonStr)

        assertEquals("input_0", spec.name)
        assertEquals("float32", spec.dtype)
        assertEquals(listOf(null, 224, 224, 3), spec.shape)
        assertEquals("Image input", spec.description)
    }

    @Test
    fun `TensorSpec deserializes with defaults`() {
        val jsonStr = """{"name": "output_0", "shape": [null, 10]}"""

        val spec = json.decodeFromString<TensorSpec>(jsonStr)

        assertEquals("output_0", spec.name)
        assertEquals("float32", spec.dtype) // default
        assertNull(spec.description)
    }

    @Test
    fun `TensorSpec fixedElementCount excludes dynamic dimensions`() {
        val spec = TensorSpec(name = "input_0", shape = listOf(null, 224, 224, 3))

        // null (batch) is excluded: 224 * 224 * 3 = 150528
        assertEquals(150528, spec.fixedElementCount)
    }

    @Test
    fun `TensorSpec fixedElementCount with all fixed dimensions`() {
        val spec = TensorSpec(name = "input_0", shape = listOf(1, 28, 28, 1))

        // 1 * 28 * 28 * 1 = 784
        assertEquals(784, spec.fixedElementCount)
    }

    @Test
    fun `TensorSpec fixedElementCount returns 0 for all dynamic dimensions`() {
        val spec = TensorSpec(name = "input_0", shape = listOf(null, null))

        assertEquals(0, spec.fixedElementCount)
    }

    @Test
    fun `TensorSpec fixedElementCount returns 0 for empty shape`() {
        val spec = TensorSpec(name = "input_0", shape = emptyList())

        assertEquals(0, spec.fixedElementCount)
    }

    @Test
    fun `TensorSpec hasDynamicDimensions returns true when null present`() {
        val spec = TensorSpec(name = "input_0", shape = listOf(null, 224, 224, 3))

        assertTrue(spec.hasDynamicDimensions)
    }

    @Test
    fun `TensorSpec hasDynamicDimensions returns false when all fixed`() {
        val spec = TensorSpec(name = "input_0", shape = listOf(1, 28, 28, 1))

        assertFalse(spec.hasDynamicDimensions)
    }

    @Test
    fun `TensorSpec roundtrips through serialization`() {
        val original = TensorSpec(
            name = "input_0",
            dtype = "float32",
            shape = listOf(null, 224, 224, 3),
            description = "Image input",
        )

        val serialized = json.encodeToString(TensorSpec.serializer(), original)
        val deserialized = json.decodeFromString<TensorSpec>(serialized)

        assertEquals(original, deserialized)
    }

    // =========================================================================
    // ServerModelContract - JSON deserialization
    // =========================================================================

    @Test
    fun `ServerModelContract deserializes full server payload`() {
        val jsonStr = """
            {
                "inputs": [
                    {"name": "input_0", "dtype": "float32", "shape": [null, 224, 224, 3], "description": null}
                ],
                "outputs": [
                    {"name": "output_0", "dtype": "float32", "shape": [null, 1000], "description": null}
                ]
            }
        """.trimIndent()

        val contract = json.decodeFromString<ServerModelContract>(jsonStr)

        assertEquals(1, contract.inputs.size)
        assertEquals(1, contract.outputs.size)
        assertEquals("input_0", contract.inputs[0].name)
        assertEquals(listOf(null, 224, 224, 3), contract.inputs[0].shape)
        assertEquals("output_0", contract.outputs[0].name)
        assertEquals(listOf(null, 1000), contract.outputs[0].shape)
    }

    @Test
    fun `ServerModelContract deserializes empty contract`() {
        val jsonStr = """{"inputs": [], "outputs": []}"""

        val contract = json.decodeFromString<ServerModelContract>(jsonStr)

        assertTrue(contract.inputs.isEmpty())
        assertTrue(contract.outputs.isEmpty())
    }

    @Test
    fun `ServerModelContract defaults to empty lists`() {
        val jsonStr = """{}"""

        val contract = json.decodeFromString<ServerModelContract>(jsonStr)

        assertTrue(contract.inputs.isEmpty())
        assertTrue(contract.outputs.isEmpty())
    }

    @Test
    fun `ServerModelContract deserializes multi-input model`() {
        val jsonStr = """
            {
                "inputs": [
                    {"name": "image", "dtype": "float32", "shape": [null, 224, 224, 3]},
                    {"name": "metadata", "dtype": "float32", "shape": [null, 16]}
                ],
                "outputs": [
                    {"name": "classes", "dtype": "float32", "shape": [null, 10]}
                ]
            }
        """.trimIndent()

        val contract = json.decodeFromString<ServerModelContract>(jsonStr)

        assertEquals(2, contract.inputs.size)
        assertEquals("image", contract.inputs[0].name)
        assertEquals("metadata", contract.inputs[1].name)
    }

    @Test
    fun `ServerModelContract roundtrips through serialization`() {
        val original = ServerModelContract(
            inputs = listOf(
                TensorSpec("input_0", "float32", listOf(null, 28, 28, 1)),
            ),
            outputs = listOf(
                TensorSpec("output_0", "float32", listOf(null, 10)),
            ),
        )

        val serialized = json.encodeToString(ServerModelContract.serializer(), original)
        val deserialized = json.decodeFromString<ServerModelContract>(serialized)

        assertEquals(original, deserialized)
    }

    // =========================================================================
    // ServerModelContract - Input validation
    // =========================================================================

    @Test
    fun `validateInput passes when input size matches fixed dimensions`() {
        val contract = ServerModelContract(
            inputs = listOf(
                TensorSpec("input_0", "float32", listOf(null, 224, 224, 3)),
            ),
        )

        // 224 * 224 * 3 = 150528
        val input = FloatArray(150528)
        val error = contract.validateInput(input)

        assertNull(error)
    }

    @Test
    fun `validateInput fails when input size is wrong`() {
        val contract = ServerModelContract(
            inputs = listOf(
                TensorSpec("input_0", "float32", listOf(null, 224, 224, 3)),
            ),
        )

        val input = FloatArray(100) // wrong size
        val error = contract.validateInput(input)

        assertNotNull(error)
        assertTrue(error.contains("expected 150528"))
        assertTrue(error.contains("got 100"))
    }

    @Test
    fun `validateInput passes with all-fixed dimensions (MNIST)`() {
        val contract = ServerModelContract(
            inputs = listOf(
                TensorSpec("input_0", "float32", listOf(1, 28, 28, 1)),
            ),
        )

        // 1 * 28 * 28 * 1 = 784
        val input = FloatArray(784)
        val error = contract.validateInput(input)

        assertNull(error)
    }

    @Test
    fun `validateInput fails with all-fixed dimensions wrong size`() {
        val contract = ServerModelContract(
            inputs = listOf(
                TensorSpec("input_0", "float32", listOf(1, 28, 28, 1)),
            ),
        )

        val input = FloatArray(100)
        val error = contract.validateInput(input)

        assertNotNull(error)
        assertTrue(error.contains("expected 784"))
    }

    @Test
    fun `validateInput skips validation when inputs list is empty`() {
        val contract = ServerModelContract(inputs = emptyList())

        val input = FloatArray(999)
        val error = contract.validateInput(input)

        assertNull(error) // no validation = passes
    }

    @Test
    fun `validateInput skips validation when all dimensions are dynamic`() {
        val contract = ServerModelContract(
            inputs = listOf(
                TensorSpec("input_0", "float32", listOf(null, null, null)),
            ),
        )

        val input = FloatArray(42)
        val error = contract.validateInput(input)

        assertNull(error) // can't validate dynamic-only shapes
    }

    @Test
    fun `validateInput skips validation when shape is empty`() {
        val contract = ServerModelContract(
            inputs = listOf(
                TensorSpec("input_0", "float32", emptyList()),
            ),
        )

        val input = FloatArray(42)
        val error = contract.validateInput(input)

        assertNull(error) // empty shape = can't validate
    }

    @Test
    fun `validateInput error message includes shape info`() {
        val contract = ServerModelContract(
            inputs = listOf(
                TensorSpec("input_0", "float32", listOf(null, 10, 10)),
            ),
        )

        val input = FloatArray(50) // expected 100
        val error = contract.validateInput(input)

        assertNotNull(error)
        assertTrue(error.contains("shape"))
        assertTrue(error.contains("100"))
        assertTrue(error.contains("50"))
    }

    // =========================================================================
    // requireValidInput
    // =========================================================================

    @Test
    fun `requireValidInput does not throw when input is valid`() {
        val contract = ServerModelContract(
            inputs = listOf(
                TensorSpec("input_0", "float32", listOf(1, 10)),
            ),
        )

        // Should not throw
        contract.requireValidInput(FloatArray(10))
    }

    @Test
    fun `requireValidInput throws IllegalArgumentException when input is invalid`() {
        val contract = ServerModelContract(
            inputs = listOf(
                TensorSpec("input_0", "float32", listOf(1, 10)),
            ),
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            contract.requireValidInput(FloatArray(5))
        }
        assertTrue(exception.message!!.contains("expected 10"))
        assertTrue(exception.message!!.contains("got 5"))
    }

    @Test
    fun `requireValidInput does not throw when no contract inputs`() {
        val contract = ServerModelContract(inputs = emptyList())

        // Should not throw regardless of input size
        contract.requireValidInput(FloatArray(999))
    }

    // =========================================================================
    // Backwards compatibility: no contract = no validation
    // =========================================================================

    @Test
    fun `null contract means no validation happens`() {
        // This tests the pattern used in TFLiteTrainer
        val contract: ServerModelContract? = null
        val input = FloatArray(42)

        // When contract is null, validateInput is never called
        val error = contract?.validateInput(input)

        assertNull(error)
    }

    // =========================================================================
    // Integration with CachedModel serialization
    // =========================================================================

    @Test
    fun `CachedModel serializes with null modelContract`() {
        val model = CachedModel(
            modelId = "m1",
            version = "1.0",
            filePath = "/path/model.tflite",
            checksum = "abc",
            sizeBytes = 100,
            format = "tensorflow_lite",
            downloadedAt = 1000L,
            verified = true,
            modelContract = null,
        )

        val serialized = json.encodeToString(CachedModel.serializer(), model)
        val deserialized = json.decodeFromString<CachedModel>(serialized)

        assertNull(deserialized.modelContract)
        assertEquals("m1", deserialized.modelId)
    }

    @Test
    fun `CachedModel serializes with non-null modelContract`() {
        val contract = ServerModelContract(
            inputs = listOf(TensorSpec("input_0", "float32", listOf(null, 224, 224, 3))),
            outputs = listOf(TensorSpec("output_0", "float32", listOf(null, 1000))),
        )

        val model = CachedModel(
            modelId = "m1",
            version = "1.0",
            filePath = "/path/model.tflite",
            checksum = "abc",
            sizeBytes = 100,
            format = "tensorflow_lite",
            downloadedAt = 1000L,
            verified = true,
            modelContract = contract,
        )

        val serialized = json.encodeToString(CachedModel.serializer(), model)
        val deserialized = json.decodeFromString<CachedModel>(serialized)

        assertNotNull(deserialized.modelContract)
        assertEquals(1, deserialized.modelContract!!.inputs.size)
        assertEquals(listOf(null, 224, 224, 3), deserialized.modelContract!!.inputs[0].shape)
    }

    @Test
    fun `CachedModel deserializes without modelContract field (backwards compat)`() {
        // Simulate cache JSON from before the modelContract field was added
        val jsonStr = """
            {
                "model_id": "m1",
                "version": "1.0",
                "file_path": "/path/model.tflite",
                "checksum": "abc",
                "size_bytes": 100,
                "format": "tensorflow_lite",
                "downloaded_at": 1000,
                "verified": true
            }
        """.trimIndent()

        val model = json.decodeFromString<CachedModel>(jsonStr)

        assertEquals("m1", model.modelId)
        assertNull(model.modelContract) // defaults to null
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun `TensorSpec with single dimension`() {
        val spec = TensorSpec("flat_input", "float32", listOf(784))

        assertEquals(784, spec.fixedElementCount)
        assertFalse(spec.hasDynamicDimensions)
    }

    @Test
    fun `validateInput with classification model (batch + classes)`() {
        val contract = ServerModelContract(
            inputs = listOf(
                TensorSpec("input_0", "float32", listOf(null, 1000)),
            ),
        )

        assertNull(contract.validateInput(FloatArray(1000))) // correct
        assertNotNull(contract.validateInput(FloatArray(999))) // wrong
    }

    @Test
    fun `validateInput with int8 dtype still validates size`() {
        // dtype doesn't affect element count validation
        val contract = ServerModelContract(
            inputs = listOf(
                TensorSpec("input_0", "int8", listOf(1, 100)),
            ),
        )

        assertNull(contract.validateInput(FloatArray(100)))
        assertNotNull(contract.validateInput(FloatArray(50)))
    }
}
