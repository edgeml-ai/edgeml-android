package ai.octomil.wrapper

import ai.octomil.api.dto.AnyValue
import ai.octomil.api.dto.ExportLogsServiceRequest
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
 * Tests for [OctomilWrappedInterpreter] — delegation, contract validation,
 * telemetry recording, and close behavior.
 *
 * The TFLite [Interpreter] is mocked since native TFLite is not available
 * on the CI JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OctomilWrappedInterpreterTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockInterpreter: Interpreter
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "octomil_wrapper_test_${System.nanoTime()}")
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
        config: OctomilWrapperConfig = OctomilWrapperConfig.default(),
        queue: TelemetryQueue = createQueue(),
        modelId: String = "test-model",
    ): OctomilWrappedInterpreter = OctomilWrappedInterpreter(
        interpreter = mockInterpreter,
        modelId = modelId,
        config = config,
        telemetryQueue = queue,
    )

    // =========================================================================
    // predict() delegation
    // =========================================================================

    @Test
    fun `predict delegates to underlying interpreter`() {
        val input = floatArrayOf(1.0f, 2.0f, 3.0f)
        val output = floatArrayOf(0.0f)

        val wrapper = createWrapper()
        wrapper.predict(input, output)

        verify(exactly = 1) { mockInterpreter.run(input, output) }
        wrapper.close()
    }

    @Test
    fun `predict propagates interpreter exceptions`() {
        every { mockInterpreter.run(any(), any()) } throws RuntimeException("inference failed")

        val wrapper = createWrapper()

        assertFailsWith<RuntimeException>("inference failed") {
            wrapper.predict(floatArrayOf(1.0f), floatArrayOf(0.0f))
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
    fun `predict records telemetry event on success`() {
        val queue = createQueue()
        val wrapper = createWrapper(queue = queue)

        wrapper.predict(floatArrayOf(1.0f), floatArrayOf(0.0f))

        // 1 inference event in the inference queue
        assertEquals(1, queue.pendingCount)
        // 1 inference.started in the v2 queue
        assertEquals(1, queue.pendingV2Count)
        wrapper.close()
    }

    @Test
    fun `predict records telemetry event on failure`() {
        every { mockInterpreter.run(any(), any()) } throws RuntimeException("boom")

        val queue = createQueue()
        val wrapper = createWrapper(queue = queue)

        try {
            wrapper.predict(floatArrayOf(1.0f), floatArrayOf(0.0f))
        } catch (_: RuntimeException) {
            // expected
        }

        assertEquals(1, queue.pendingCount)
        assertEquals(1, queue.pendingV2Count) // inference.started was emitted before failure
        wrapper.close()
    }

    @Test
    fun `telemetry is not recorded when disabled`() {
        val config = OctomilWrapperConfig(telemetryEnabled = false)
        val queue = createQueue()
        val wrapper = createWrapper(config = config, queue = queue)

        wrapper.predict(floatArrayOf(1.0f), floatArrayOf(0.0f))

        assertEquals(0, queue.pendingCount)
        assertEquals(0, queue.pendingV2Count)
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
        assertEquals(1, queue.pendingV2Count) // inference.started
        wrapper.close()
    }

    // =========================================================================
    // inference.started emission
    // =========================================================================

    @Test
    fun `inference_started emitted before inference`() = runTest(testDispatcher) {
        val batches = mutableListOf<ExportLogsServiceRequest>()
        val sender = TelemetrySender { batch -> batches.add(batch) }
        val queue = createQueue(sender = sender)
        val wrapper = createWrapper(queue = queue, modelId = "my-model")

        wrapper.predict(floatArrayOf(1.0f), floatArrayOf(0.0f))

        queue.flush()

        assertEquals(1, batches.size)
        val logRecords = batches[0].resourceLogs.flatMap { rl ->
            rl.scopeLogs.flatMap { sl -> sl.logRecords }
        }
        // Should contain both the inference.completed and inference.started events
        val names = logRecords.mapNotNull { (it.body as? AnyValue.StringValue)?.stringValue }
        assertTrue("inference.started" in names, "Expected inference.started, got: $names")
        assertTrue("inference.completed" in names, "Expected inference.completed, got: $names")

        // inference.started should appear in the batch (order may vary since
        // they're in separate queues, but both must be present)
        val startedRecord = logRecords.first {
            (it.body as? AnyValue.StringValue)?.stringValue == "inference.started"
        }
        val modelIdAttr = startedRecord.attributes?.firstOrNull { it.key == "model.id" }
        assertNotNull(modelIdAttr, "Expected model.id attribute on inference.started")
        assertEquals(
            "my-model",
            (modelIdAttr.value as AnyValue.StringValue).stringValue,
        )

        wrapper.close()
    }

    // =========================================================================
    // Contract validation
    // =========================================================================

    @Test
    fun `validation passes when input size matches contract`() {
        val queue = createQueue()
        val config = OctomilWrapperConfig(validateInputs = true, telemetryEnabled = false)
        val wrapper = createWrapper(config = config, queue = queue)
        wrapper.serverContract = ServerModelContract(expectedInputSize = 3, inputDescription = "[1, 3]")

        // Should not throw or log warnings when size matches
        wrapper.predict(floatArrayOf(1.0f, 2.0f, 3.0f), floatArrayOf(0.0f))

        verify(exactly = 1) { mockInterpreter.run(any(), any()) }
        wrapper.close()
    }

    @Test
    fun `validation warns but does not throw on size mismatch`() {
        val queue = createQueue()
        val config = OctomilWrapperConfig(validateInputs = true, telemetryEnabled = false)
        val wrapper = createWrapper(config = config, queue = queue)
        wrapper.serverContract = ServerModelContract(expectedInputSize = 10, inputDescription = "[1, 10]")

        // Should still call interpreter even with wrong input size
        wrapper.predict(floatArrayOf(1.0f, 2.0f), floatArrayOf(0.0f))

        verify(exactly = 1) { mockInterpreter.run(any(), any()) }
        wrapper.close()
    }

    @Test
    fun `validation is skipped when disabled`() {
        val config = OctomilWrapperConfig(validateInputs = false, telemetryEnabled = false)
        val wrapper = createWrapper(config = config)
        wrapper.serverContract = ServerModelContract(expectedInputSize = 10)

        // Even with mismatched size, no validation warning
        wrapper.predict(floatArrayOf(1.0f), floatArrayOf(0.0f))

        verify(exactly = 1) { mockInterpreter.run(any(), any()) }
        wrapper.close()
    }

    @Test
    fun `validation is skipped when no contract available`() {
        val config = OctomilWrapperConfig(validateInputs = true, telemetryEnabled = false)
        val wrapper = createWrapper(config = config)
        // serverContract is null by default

        wrapper.predict(floatArrayOf(1.0f), floatArrayOf(0.0f))

        verify(exactly = 1) { mockInterpreter.run(any(), any()) }
        wrapper.close()
    }

    @Test
    fun `validation skips non-FloatArray inputs`() {
        val config = OctomilWrapperConfig(validateInputs = true, telemetryEnabled = false)
        val wrapper = createWrapper(config = config)
        wrapper.serverContract = ServerModelContract(expectedInputSize = 10)

        // ByteBuffer input should not be validated (can't cheaply check shape)
        val buf = java.nio.ByteBuffer.allocateDirect(4)
        wrapper.predict(buf, floatArrayOf(0.0f))

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
    fun `multiple predict calls accumulate telemetry events`() {
        val queue = createQueue()
        val wrapper = createWrapper(queue = queue)

        wrapper.predict(floatArrayOf(1.0f), floatArrayOf(0.0f))
        wrapper.predict(floatArrayOf(2.0f), floatArrayOf(0.0f))
        wrapper.predict(floatArrayOf(3.0f), floatArrayOf(0.0f))

        assertEquals(3, queue.pendingCount)
        assertEquals(3, queue.pendingV2Count) // 3 inference.started events
        wrapper.close()
    }
}
