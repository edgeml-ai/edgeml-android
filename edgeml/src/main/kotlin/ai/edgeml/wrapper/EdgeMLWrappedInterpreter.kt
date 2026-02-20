package ai.edgeml.wrapper

import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import timber.log.Timber
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Drop-in replacement for TFLite [Interpreter] that adds EdgeML telemetry,
 * input validation, and (optional) OTA model updates transparently.
 *
 * **Not a subclass of [Interpreter]** -- it exposes the same method signatures
 * so that call sites remain unchanged.
 *
 * ```kotlin
 * // Before:
 * val interpreter = Interpreter(modelFile)
 * interpreter.run(input, output)
 *
 * // After:
 * val interpreter = EdgeML.wrap(Interpreter(modelFile), modelId = "classifier")
 * interpreter.run(input, output)   // identical call site
 * ```
 *
 * Each `run`/`runForMultipleInputsOutputs` call:
 * 1. Validates input against the server model contract (if available & input is FloatArray).
 * 2. Records start time via [System.nanoTime].
 * 3. Delegates to the underlying [Interpreter].
 * 4. Records latency and enqueues a telemetry event.
 */
class EdgeMLWrappedInterpreter internal constructor(
    private val interpreter: Interpreter,
    private val modelId: String,
    private val config: EdgeMLWrapperConfig,
    private val telemetryQueue: TelemetryQueue,
) : Closeable {

    /**
     * Server model contract for input validation. Set asynchronously after
     * the server responds; null means validation is skipped.
     */
    @Volatile
    internal var serverContract: ServerModelContract? = null

    companion object {
        private const val TAG = "EdgeMLWrappedInterpreter"
    }

    // =========================================================================
    // run() — single input/output
    // =========================================================================

    /**
     * Run model inference with a single input and output.
     *
     * Mirrors [Interpreter.run]. The input/output types follow the same rules
     * as the underlying TFLite Interpreter (ByteBuffer, float arrays, etc.).
     */
    fun run(input: Any, output: Any) {
        validateIfFloatArray(input)
        val startNs = System.nanoTime()
        var success = true
        var errorMsg: String? = null
        try {
            interpreter.run(input, output)
        } catch (e: Exception) {
            success = false
            errorMsg = e.message
            throw e
        } finally {
            recordTelemetry(startNs, success, errorMsg)
        }
    }

    // =========================================================================
    // runForMultipleInputsOutputs()
    // =========================================================================

    /**
     * Run model inference with multiple inputs and outputs.
     *
     * Mirrors [Interpreter.runForMultipleInputsOutputs].
     */
    fun runForMultipleInputsOutputs(inputs: Array<Any>, outputs: Map<Int, Any>) {
        // Validate first input if it's a FloatArray
        if (inputs.isNotEmpty()) {
            validateIfFloatArray(inputs[0])
        }
        val startNs = System.nanoTime()
        var success = true
        var errorMsg: String? = null
        try {
            interpreter.runForMultipleInputsOutputs(inputs, outputs)
        } catch (e: Exception) {
            success = false
            errorMsg = e.message
            throw e
        } finally {
            recordTelemetry(startNs, success, errorMsg)
        }
    }

    // =========================================================================
    // Tensor introspection — delegated directly
    // =========================================================================

    /** Get the input tensor at the given index. */
    fun getInputTensor(index: Int): Tensor = interpreter.getInputTensor(index)

    /** Get the output tensor at the given index. */
    fun getOutputTensor(index: Int): Tensor = interpreter.getOutputTensor(index)

    /** Get the number of input tensors. */
    fun getInputTensorCount(): Int = interpreter.inputTensorCount

    /** Get the number of output tensors. */
    fun getOutputTensorCount(): Int = interpreter.outputTensorCount

    /** Resize the input tensor at the given index. */
    fun resizeInput(index: Int, dims: IntArray) {
        interpreter.resizeInput(index, dims)
    }

    /** Allocate tensors after resizing. */
    fun allocateTensors() {
        interpreter.allocateTensors()
    }

    // =========================================================================
    // Closeable
    // =========================================================================

    /**
     * Close the underlying interpreter and flush any remaining telemetry.
     */
    override fun close() {
        try {
            telemetryQueue.close()
        } catch (e: Exception) {
            Timber.w(e, "Error flushing telemetry on close")
        }
        interpreter.close()
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Validate input against the server contract if:
     * - validation is enabled in config
     * - a contract is available
     * - the input is a FloatArray
     *
     * Validation failures are logged as warnings but do NOT throw -- the
     * inference still proceeds. This is intentional: contract validation is
     * best-effort and should never break the caller's code path.
     */
    private fun validateIfFloatArray(input: Any) {
        if (!config.validateInputs) return
        val contract = serverContract ?: return

        val floatInput = when (input) {
            is FloatArray -> input
            is ByteBuffer -> null // Can't cheaply validate ByteBuffer shape
            is Array<*> -> {
                // Try to extract the first element if it's a nested float array
                val first = input.firstOrNull()
                if (first is FloatArray) first else null
            }
            else -> null
        }

        if (floatInput != null) {
            val result = contract.validate(floatInput)
            if (!result.isValid) {
                Timber.w(
                    "%s: Input validation failed for model '%s': %s",
                    TAG,
                    modelId,
                    result.message,
                )
            }
        }
    }

    /**
     * Record a telemetry event for a completed inference.
     */
    private fun recordTelemetry(startNs: Long, success: Boolean, errorMessage: String?) {
        if (!config.telemetryEnabled) return

        val latencyMs = (System.nanoTime() - startNs) / 1_000_000.0
        val event = InferenceTelemetryEvent(
            modelId = modelId,
            latencyMs = latencyMs,
            timestampMs = System.currentTimeMillis(),
            success = success,
            errorMessage = errorMessage,
        )
        telemetryQueue.enqueue(event)
    }
}

/**
 * Lightweight model contract for input validation.
 *
 * This is intentionally simpler than a full server schema -- it captures just
 * enough to catch obvious mistakes (wrong input size) at the edge.
 */
data class ServerModelContract(
    /** Expected number of elements in the input FloatArray. */
    val expectedInputSize: Int,
    /** Human-readable description of the expected input shape. */
    val inputDescription: String = "",
) {
    /**
     * Validate a FloatArray input against this contract.
     */
    fun validate(input: FloatArray): ValidationResult {
        if (expectedInputSize > 0 && input.size != expectedInputSize) {
            return ValidationResult(
                isValid = false,
                message = "Expected $expectedInputSize input elements ($inputDescription), " +
                    "got ${input.size}",
            )
        }
        return ValidationResult(isValid = true, message = "OK")
    }
}

/**
 * Result of input validation against a [ServerModelContract].
 */
data class ValidationResult(
    val isValid: Boolean,
    val message: String,
)
