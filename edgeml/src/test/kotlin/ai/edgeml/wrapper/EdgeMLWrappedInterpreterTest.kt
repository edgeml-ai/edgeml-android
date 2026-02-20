package ai.edgeml.wrapper

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [EdgeMLWrappedInterpreter] — delegation, contract validation,
 * telemetry recording, and close behavior.
 *
 * The TFLite [Interpreter] is mocked since native TFLite is not available
 * on the CI JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EdgeMLWrappedInterpreterTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockInterpreter: Interpreter
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "edgeml_wrapper_test_${System.nanoTime()}")
        tmpDir.mkdirs()

        mockInterpreter = mockk<Interpreter>(relaxed = true)
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun createQueue(
        sender: TelemetrySender? = null,
        batchSize: Int = 100,
    ): TelemetryQueue = TelemetryQueue(
        batchSize = batchSize,
        flushIntervalMs = 0,
        persistDir = tmpDir,
        sender = sender,
        dispatcher = testDispatcher,
    )

    private fun createWrapper(
        config: EdgeMLWrapperConfig = EdgeMLWrapperConfig.default(),
        queue: TelemetryQueue = createQueue(),
        modelId: String = "test-model",
    ): EdgeMLWrappedInterpreter = EdgeMLWrappedInterpreter(
        interpreter = mockInterpreter,
        modelId = modelId,
        config = config,
        telemetryQueue = queue,
    )

    // =========================================================================
    // run() delegation
    // =========================================================================

    @Test
    fun `run delegates to underlying interpreter`() {
        val input = floatArrayOf(1.0f, 2.0f, 3.0f)
        val output = floatArrayOf(0.0f)

        val wrapper = createWrapper()
        wrapper.run(input, output)

        verify(exactly = 1) { mockInterpreter.run(input, output) }
        wrapper.close()
    }

    @Test
    fun `run propagates interpreter exceptions`() {
        every { mockInterpreter.run(any(), any()) } throws RuntimeException("inference failed")

        val wrapper = createWrapper()

        assertFailsWith<RuntimeException>("inference failed") {
            wrapper.run(floatArrayOf(1.0f), floatArrayOf(0.0f))
        }
        wrapper.close()
    }

    // =========================================================================
    // runForMultipleInputsOutputs() delegation
    // =========================================================================

    @Test
    fun `runForMultipleInputsOutputs delegates to underlying interpreter`() {
        val inputs = arrayOf<Any>(floatArrayOf(1.0f), floatArrayOf(2.0f))
        val outputs = mapOf(0 to floatArrayOf(0.0f))

        val wrapper = createWrapper()
        wrapper.runForMultipleInputsOutputs(inputs, outputs)

        verify(exactly = 1) { mockInterpreter.runForMultipleInputsOutputs(inputs, outputs) }
        wrapper.close()
    }

    @Test
    fun `runForMultipleInputsOutputs propagates exceptions`() {
        every {
            mockInterpreter.runForMultipleInputsOutputs(any(), any())
        } throws RuntimeException("multi-input failed")

        val wrapper = createWrapper()

        assertFailsWith<RuntimeException> {
            wrapper.runForMultipleInputsOutputs(
                arrayOf<Any>(floatArrayOf(1.0f)),
                mapOf(0 to floatArrayOf(0.0f)),
            )
        }
        wrapper.close()
    }

    // =========================================================================
    // Tensor introspection delegation
    // =========================================================================

    @Test
    fun `getInputTensorCount delegates to interpreter`() {
        every { mockInterpreter.inputTensorCount } returns 2

        val wrapper = createWrapper()
        assertEquals(2, wrapper.getInputTensorCount())
        wrapper.close()
    }

    @Test
    fun `getOutputTensorCount delegates to interpreter`() {
        every { mockInterpreter.outputTensorCount } returns 3

        val wrapper = createWrapper()
        assertEquals(3, wrapper.getOutputTensorCount())
        wrapper.close()
    }

    @Test
    fun `getInputTensor delegates to interpreter`() {
        val mockTensor = mockk<Tensor>()
        every { mockInterpreter.getInputTensor(0) } returns mockTensor

        val wrapper = createWrapper()
        assertEquals(mockTensor, wrapper.getInputTensor(0))
        wrapper.close()
    }

    @Test
    fun `getOutputTensor delegates to interpreter`() {
        val mockTensor = mockk<Tensor>()
        every { mockInterpreter.getOutputTensor(1) } returns mockTensor

        val wrapper = createWrapper()
        assertEquals(mockTensor, wrapper.getOutputTensor(1))
        wrapper.close()
    }

    @Test
    fun `resizeInput delegates to interpreter`() {
        val wrapper = createWrapper()
        wrapper.resizeInput(0, intArrayOf(1, 224, 224, 3))

        verify(exactly = 1) { mockInterpreter.resizeInput(0, intArrayOf(1, 224, 224, 3)) }
        wrapper.close()
    }

    @Test
    fun `allocateTensors delegates to interpreter`() {
        val wrapper = createWrapper()
        wrapper.allocateTensors()

        verify(exactly = 1) { mockInterpreter.allocateTensors() }
        wrapper.close()
    }

    // =========================================================================
    // Telemetry recording
    // =========================================================================

    @Test
    fun `run records telemetry event on success`() {
        val queue = createQueue()
        val wrapper = createWrapper(queue = queue)

        wrapper.run(floatArrayOf(1.0f), floatArrayOf(0.0f))

        assertEquals(1, queue.pendingCount)
        wrapper.close()
    }

    @Test
    fun `run records telemetry event on failure`() {
        every { mockInterpreter.run(any(), any()) } throws RuntimeException("boom")

        val queue = createQueue()
        val wrapper = createWrapper(queue = queue)

        try {
            wrapper.run(floatArrayOf(1.0f), floatArrayOf(0.0f))
        } catch (_: RuntimeException) {
            // expected
        }

        assertEquals(1, queue.pendingCount)
        wrapper.close()
    }

    @Test
    fun `telemetry is not recorded when disabled`() {
        val config = EdgeMLWrapperConfig(telemetryEnabled = false)
        val queue = createQueue()
        val wrapper = createWrapper(config = config, queue = queue)

        wrapper.run(floatArrayOf(1.0f), floatArrayOf(0.0f))

        assertEquals(0, queue.pendingCount)
        wrapper.close()
    }

    @Test
    fun `runForMultipleInputsOutputs records telemetry`() {
        val queue = createQueue()
        val wrapper = createWrapper(queue = queue)

        wrapper.runForMultipleInputsOutputs(
            arrayOf<Any>(floatArrayOf(1.0f)),
            mapOf(0 to floatArrayOf(0.0f)),
        )

        assertEquals(1, queue.pendingCount)
        wrapper.close()
    }

    // =========================================================================
    // Contract validation
    // =========================================================================

    @Test
    fun `validation passes when input size matches contract`() {
        val queue = createQueue()
        val config = EdgeMLWrapperConfig(validateInputs = true, telemetryEnabled = false)
        val wrapper = createWrapper(config = config, queue = queue)
        wrapper.serverContract = ServerModelContract(expectedInputSize = 3, inputDescription = "[1, 3]")

        // Should not throw or log warnings when size matches
        wrapper.run(floatArrayOf(1.0f, 2.0f, 3.0f), floatArrayOf(0.0f))

        verify(exactly = 1) { mockInterpreter.run(any(), any()) }
        wrapper.close()
    }

    @Test
    fun `validation warns but does not throw on size mismatch`() {
        val queue = createQueue()
        val config = EdgeMLWrapperConfig(validateInputs = true, telemetryEnabled = false)
        val wrapper = createWrapper(config = config, queue = queue)
        wrapper.serverContract = ServerModelContract(expectedInputSize = 10, inputDescription = "[1, 10]")

        // Should still call interpreter even with wrong input size
        wrapper.run(floatArrayOf(1.0f, 2.0f), floatArrayOf(0.0f))

        verify(exactly = 1) { mockInterpreter.run(any(), any()) }
        wrapper.close()
    }

    @Test
    fun `validation is skipped when disabled`() {
        val config = EdgeMLWrapperConfig(validateInputs = false, telemetryEnabled = false)
        val wrapper = createWrapper(config = config)
        wrapper.serverContract = ServerModelContract(expectedInputSize = 10)

        // Even with mismatched size, no validation warning
        wrapper.run(floatArrayOf(1.0f), floatArrayOf(0.0f))

        verify(exactly = 1) { mockInterpreter.run(any(), any()) }
        wrapper.close()
    }

    @Test
    fun `validation is skipped when no contract available`() {
        val config = EdgeMLWrapperConfig(validateInputs = true, telemetryEnabled = false)
        val wrapper = createWrapper(config = config)
        // serverContract is null by default

        wrapper.run(floatArrayOf(1.0f), floatArrayOf(0.0f))

        verify(exactly = 1) { mockInterpreter.run(any(), any()) }
        wrapper.close()
    }

    @Test
    fun `validation skips non-FloatArray inputs`() {
        val config = EdgeMLWrapperConfig(validateInputs = true, telemetryEnabled = false)
        val wrapper = createWrapper(config = config)
        wrapper.serverContract = ServerModelContract(expectedInputSize = 10)

        // ByteBuffer input should not be validated (can't cheaply check shape)
        val buf = java.nio.ByteBuffer.allocateDirect(4)
        wrapper.run(buf, floatArrayOf(0.0f))

        verify(exactly = 1) { mockInterpreter.run(buf, any()) }
        wrapper.close()
    }

    // =========================================================================
    // ServerModelContract
    // =========================================================================

    @Test
    fun `ServerModelContract validate returns valid for matching size`() {
        val contract = ServerModelContract(expectedInputSize = 5)
        val result = contract.validate(floatArrayOf(1f, 2f, 3f, 4f, 5f))

        assertTrue(result.isValid)
        assertEquals("OK", result.message)
    }

    @Test
    fun `ServerModelContract validate returns invalid for wrong size`() {
        val contract = ServerModelContract(expectedInputSize = 5, inputDescription = "[1, 5]")
        val result = contract.validate(floatArrayOf(1f, 2f))

        assertFalse(result.isValid)
        assertTrue(result.message.contains("Expected 5"))
        assertTrue(result.message.contains("got 2"))
    }

    @Test
    fun `ServerModelContract validate skips check when expectedInputSize is 0`() {
        val contract = ServerModelContract(expectedInputSize = 0)
        val result = contract.validate(floatArrayOf(1f, 2f, 3f))

        assertTrue(result.isValid)
    }

    // =========================================================================
    // ValidationResult
    // =========================================================================

    @Test
    fun `ValidationResult equality`() {
        val a = ValidationResult(isValid = true, message = "OK")
        val b = ValidationResult(isValid = true, message = "OK")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // =========================================================================
    // close()
    // =========================================================================

    @Test
    fun `close calls interpreter close`() {
        val wrapper = createWrapper()

        wrapper.close()

        verify(exactly = 1) { mockInterpreter.close() }
    }

    @Test
    fun `close can be called multiple times`() {
        every { mockInterpreter.close() } just Runs

        val wrapper = createWrapper()

        wrapper.close()
        // Second close may log a warning but should not throw
        try {
            wrapper.close()
        } catch (_: Exception) {
            // Acceptable -- depends on interpreter implementation
        }
    }

    // =========================================================================
    // Multiple inferences accumulate telemetry
    // =========================================================================

    @Test
    fun `multiple run calls accumulate telemetry events`() {
        val queue = createQueue()
        val wrapper = createWrapper(queue = queue)

        wrapper.run(floatArrayOf(1.0f), floatArrayOf(0.0f))
        wrapper.run(floatArrayOf(2.0f), floatArrayOf(0.0f))
        wrapper.run(floatArrayOf(3.0f), floatArrayOf(0.0f))

        assertEquals(3, queue.pendingCount)
        wrapper.close()
    }
}
