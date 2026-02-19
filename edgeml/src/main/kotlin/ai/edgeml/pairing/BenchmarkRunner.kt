package ai.edgeml.pairing

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs inference benchmarks on a downloaded TFLite model.
 *
 * Benchmark sequence:
 * 1. Load model (measure load time)
 * 2. Run 1 cold inference (measure latency)
 * 3. Run N warm inferences (collect latencies, compute percentiles)
 * 4. Capture memory, battery, and thermal state
 * 5. Build [BenchmarkReport]
 *
 * Uses TFLite Interpreter directly via reflection to avoid a hard compile-time
 * dependency on TFLite from the pairing module. The interpreter classes are
 * already on the classpath via the main SDK dependency.
 */
class BenchmarkRunner(
    private val context: Context,
    private val warmInferenceCount: Int = 10,
) {
    /**
     * Run benchmarks on a model file and return a [BenchmarkReport].
     *
     * @param modelFile Path to the .tflite model file on disk.
     * @param modelName Name of the model for the report.
     * @param deviceInfo Device hardware info for the report.
     * @return Populated benchmark report.
     * @throws BenchmarkException if model loading or inference fails.
     */
    fun run(
        modelFile: File,
        modelName: String,
        deviceInfo: DeviceConnectRequest,
    ): BenchmarkReport {
        Timber.d("Starting benchmark for model: %s (%d bytes)", modelName, modelFile.length())

        // Step 1: Load model and measure load time
        val loadStart = System.nanoTime()
        val interpreter = loadInterpreter(modelFile)
        val modelLoadTimeMs = (System.nanoTime() - loadStart) / 1_000_000.0

        try {
            // Determine input/output shapes
            val inputShape = getInputShape(interpreter)
            val outputShape = getOutputShape(interpreter)
            val inputSize = inputShape.fold(1) { acc, dim -> acc * dim }
            val outputSize = outputShape.fold(1) { acc, dim -> acc * dim }

            // Allocate input/output buffers
            val inputBuffer = ByteBuffer.allocateDirect(inputSize * 4).order(ByteOrder.nativeOrder())
            val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4).order(ByteOrder.nativeOrder())

            // Fill input with random data for benchmarking
            for (i in 0 until inputSize) {
                inputBuffer.putFloat(Math.random().toFloat())
            }

            // Step 2: Cold inference
            inputBuffer.rewind()
            outputBuffer.rewind()
            val coldStart = System.nanoTime()
            runInterpreter(interpreter, inputBuffer, outputBuffer)
            val coldInferenceMs = (System.nanoTime() - coldStart) / 1_000_000.0

            // Step 3: Warm inferences
            val warmLatencies = mutableListOf<Double>()
            val memoryBeforeInference = getUsedMemoryBytes()

            for (i in 0 until warmInferenceCount) {
                inputBuffer.rewind()
                outputBuffer.rewind()
                val warmStart = System.nanoTime()
                runInterpreter(interpreter, inputBuffer, outputBuffer)
                val warmLatencyMs = (System.nanoTime() - warmStart) / 1_000_000.0
                warmLatencies.add(warmLatencyMs)
            }

            val memoryAfterInference = getUsedMemoryBytes()
            val memoryPeakBytes = maxOf(memoryBeforeInference, memoryAfterInference)

            // Compute percentiles
            warmLatencies.sort()
            val p50 = percentile(warmLatencies, 50)
            val p95 = percentile(warmLatencies, 95)
            val p99 = percentile(warmLatencies, 99)
            val avgWarmMs = if (warmLatencies.isNotEmpty()) warmLatencies.sum() / warmLatencies.size else 0.0

            // Tokens per second: treat each inference as one "token" for now
            val tokensPerSecond = if (avgWarmMs > 0) 1000.0 / avgWarmMs else 0.0

            // TTFT = time to first token = cold inference time
            // TPOT = time per output token = average warm inference time
            val ttftMs = coldInferenceMs
            val tpotMs = avgWarmMs

            // Battery and thermal state
            val batteryLevel = getBatteryLevel()
            val thermalState = getThermalState()

            val totalInferenceCount = 1 + warmInferenceCount

            Timber.i(
                "Benchmark complete: model_load=%.1fms cold=%.1fms warm_avg=%.1fms p50=%.1fms p95=%.1fms",
                modelLoadTimeMs, coldInferenceMs, avgWarmMs, p50, p95,
            )

            return BenchmarkReport(
                modelName = modelName,
                deviceName = deviceInfo.deviceName,
                chipFamily = deviceInfo.chipFamily ?: Build.HARDWARE,
                ramGb = deviceInfo.ramGb ?: 0.0,
                osVersion = deviceInfo.osVersion ?: Build.VERSION.RELEASE,
                ttftMs = ttftMs,
                tpotMs = tpotMs,
                tokensPerSecond = tokensPerSecond,
                p50LatencyMs = p50,
                p95LatencyMs = p95,
                p99LatencyMs = p99,
                memoryPeakBytes = memoryPeakBytes,
                inferenceCount = totalInferenceCount,
                modelLoadTimeMs = modelLoadTimeMs,
                coldInferenceMs = coldInferenceMs,
                warmInferenceMs = avgWarmMs,
                batteryLevel = batteryLevel,
                thermalState = thermalState,
            )
        } finally {
            closeInterpreter(interpreter)
        }
    }

    // =========================================================================
    // TFLite Interpreter (reflection-based to avoid hard dependency)
    // =========================================================================

    private fun loadInterpreter(modelFile: File): Any {
        try {
            val interpreterClass = Class.forName("org.tensorflow.lite.Interpreter")
            val constructor = interpreterClass.getConstructor(File::class.java)
            return constructor.newInstance(modelFile)
        } catch (e: Exception) {
            throw BenchmarkException("Failed to load TFLite interpreter: ${e.message}", e)
        }
    }

    private fun getInputShape(interpreter: Any): IntArray {
        return try {
            val method = interpreter.javaClass.getMethod("getInputTensor", Int::class.javaPrimitiveType)
            val tensor = method.invoke(interpreter, 0)
            val shapeMethod = tensor.javaClass.getMethod("shape")
            shapeMethod.invoke(tensor) as IntArray
        } catch (e: Exception) {
            Timber.w(e, "Failed to get input shape, using default [1, 224, 224, 3]")
            intArrayOf(1, 224, 224, 3)
        }
    }

    private fun getOutputShape(interpreter: Any): IntArray {
        return try {
            val method = interpreter.javaClass.getMethod("getOutputTensor", Int::class.javaPrimitiveType)
            val tensor = method.invoke(interpreter, 0)
            val shapeMethod = tensor.javaClass.getMethod("shape")
            shapeMethod.invoke(tensor) as IntArray
        } catch (e: Exception) {
            Timber.w(e, "Failed to get output shape, using default [1, 1000]")
            intArrayOf(1, 1000)
        }
    }

    private fun runInterpreter(interpreter: Any, input: ByteBuffer, output: ByteBuffer) {
        try {
            val method = interpreter.javaClass.getMethod(
                "run",
                Any::class.java,
                Any::class.java,
            )
            method.invoke(interpreter, input, output)
        } catch (e: Exception) {
            throw BenchmarkException("Inference failed: ${e.message}", e)
        }
    }

    private fun closeInterpreter(interpreter: Any) {
        try {
            val method = interpreter.javaClass.getMethod("close")
            method.invoke(interpreter)
        } catch (e: Exception) {
            Timber.w(e, "Failed to close interpreter")
        }
    }

    // =========================================================================
    // System metrics
    // =========================================================================

    private fun getUsedMemoryBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun getBatteryLevel(): Float? {
        return try {
            val batteryManager =
                context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level >= 0) level.toFloat() else null
        } catch (e: Exception) {
            Timber.w(e, "Failed to read battery level")
            null
        }
    }

    private fun getThermalState(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "unknown"
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read thermal state")
            null
        }
    }

    companion object {
        /**
         * Compute the k-th percentile from a sorted list of values.
         */
        internal fun percentile(sortedValues: List<Double>, k: Int): Double {
            if (sortedValues.isEmpty()) return 0.0
            if (sortedValues.size == 1) return sortedValues[0]
            val index = (k / 100.0) * (sortedValues.size - 1)
            val lower = index.toInt()
            val upper = (lower + 1).coerceAtMost(sortedValues.size - 1)
            val fraction = index - lower
            return sortedValues[lower] + fraction * (sortedValues[upper] - sortedValues[lower])
        }
    }
}

/**
 * Exception thrown when benchmarking fails.
 */
class BenchmarkException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
