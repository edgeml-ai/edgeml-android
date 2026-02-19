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
    private val warmInferenceCount: Int = 50,
    private val delegateTrialCount: Int = 3,
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

            // Step 2: Cold inference (on default CPU interpreter)
            inputBuffer.rewind()
            outputBuffer.rewind()
            val coldStart = System.nanoTime()
            runInterpreter(interpreter, inputBuffer, outputBuffer)
            val coldInferenceMs = (System.nanoTime() - coldStart) / 1_000_000.0

            // Step 3: Delegate auto-selection — test each delegate and pick the fastest
            val delegateResult = selectBestDelegate(modelFile, inputBuffer, outputBuffer, inputSize)
            val activeDelegate = delegateResult.bestDelegate
            val disabledDelegates = delegateResult.disabledDelegates

            Timber.i(
                "Delegate selection: active=%s disabled=%s",
                activeDelegate, disabledDelegates,
            )

            // Step 4: Rebuild interpreter with the best delegate (if not xnnpack/default)
            val benchInterpreter = createInterpreterWithDelegate(modelFile, activeDelegate)
                ?: interpreter

            try {
                // Step 5: Warm inferences with selected delegate
                val warmLatencies = mutableListOf<Double>()
                val memoryBeforeInference = getUsedMemoryBytes()

                for (i in 0 until warmInferenceCount) {
                    inputBuffer.rewind()
                    outputBuffer.rewind()
                    val warmStart = System.nanoTime()
                    runInterpreter(benchInterpreter, inputBuffer, outputBuffer)
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
                val avgWarmMs =
                    if (warmLatencies.isNotEmpty()) warmLatencies.sum() / warmLatencies.size else 0.0

                // Tokens per second: treat each inference as one "token" for now
                val tokensPerSecond = if (avgWarmMs > 0) 1000.0 / avgWarmMs else 0.0

                // TTFT = time to first token = cold inference time
                // TPOT = time per output token = average warm inference time
                val ttftMs = coldInferenceMs
                val tpotMs = avgWarmMs

                // Battery and thermal state
                val batteryLevel = getBatteryLevel()
                val thermalState = getThermalState()

                // Total = cold (1) + delegate trials + warm inferences
                val totalInferenceCount = 1 + delegateResult.totalTrialInferences + warmInferenceCount

                Timber.i(
                    "Benchmark complete: model_load=%.1fms cold=%.1fms warm_avg=%.1fms " +
                        "p50=%.1fms p95=%.1fms delegate=%s",
                    modelLoadTimeMs, coldInferenceMs, avgWarmMs, p50, p95, activeDelegate,
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
                    activeDelegate = activeDelegate,
                    disabledDelegates = disabledDelegates.ifEmpty { null },
                )
            } finally {
                if (benchInterpreter !== interpreter) {
                    closeInterpreter(benchInterpreter)
                }
            }
        } finally {
            closeInterpreter(interpreter)
        }
    }

    // =========================================================================
    // Delegate auto-selection
    // =========================================================================

    /**
     * Result of delegate auto-selection.
     */
    internal data class DelegateResult(
        val bestDelegate: String,
        val disabledDelegates: List<String>,
        val totalTrialInferences: Int,
    )

    /**
     * Test each available delegate by running [delegateTrialCount] inferences and picking
     * whichever yields the lowest median latency.
     *
     * Delegates tested (in order): NNAPI (API 27+), GPU, XNNPACK (CPU fallback).
     * Any delegate that fails to load or run is recorded in [DelegateResult.disabledDelegates].
     */
    internal fun selectBestDelegate(
        modelFile: File,
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        inputSize: Int,
    ): DelegateResult {
        val candidates = mutableListOf<Pair<String, Double>>() // name -> median latency
        val disabled = mutableListOf<String>()
        var totalTrials = 0

        // --- NNAPI delegate (API 27+) ---
        if (Build.VERSION.SDK_INT >= 27) {
            val nnapiMedian = trialDelegate(modelFile, "nnapi", inputBuffer, outputBuffer)
            if (nnapiMedian != null) {
                candidates.add("nnapi" to nnapiMedian)
                totalTrials += delegateTrialCount
            } else {
                disabled.add("nnapi")
            }
        } else {
            disabled.add("nnapi")
        }

        // --- GPU delegate ---
        val gpuMedian = trialDelegate(modelFile, "gpu", inputBuffer, outputBuffer)
        if (gpuMedian != null) {
            candidates.add("gpu" to gpuMedian)
            totalTrials += delegateTrialCount
        } else {
            disabled.add("gpu")
        }

        // --- XNNPACK (CPU) — always available via default interpreter ---
        val xnnpackMedian = trialDelegate(modelFile, "xnnpack", inputBuffer, outputBuffer)
        if (xnnpackMedian != null) {
            candidates.add("xnnpack" to xnnpackMedian)
            totalTrials += delegateTrialCount
        } else {
            // XNNPACK/CPU should always work; if it fails, still record it
            disabled.add("xnnpack")
        }

        // Pick the fastest (lowest median latency)
        val best = candidates.minByOrNull { it.second }?.first ?: "xnnpack"

        return DelegateResult(
            bestDelegate = best,
            disabledDelegates = disabled,
            totalTrialInferences = totalTrials,
        )
    }

    /**
     * Run [delegateTrialCount] inferences with the given delegate and return the median latency,
     * or null if the delegate fails to load.
     */
    private fun trialDelegate(
        modelFile: File,
        delegateName: String,
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
    ): Double? {
        val interpreter = try {
            if (delegateName == "xnnpack") {
                // XNNPACK is the default CPU delegate — use plain interpreter
                loadInterpreter(modelFile)
            } else {
                createInterpreterWithDelegate(modelFile, delegateName) ?: return null
            }
        } catch (e: Exception) {
            Timber.d("Delegate %s unavailable: %s", delegateName, e.message)
            return null
        }

        try {
            val latencies = mutableListOf<Double>()
            for (i in 0 until delegateTrialCount) {
                inputBuffer.rewind()
                outputBuffer.rewind()
                val start = System.nanoTime()
                runInterpreter(interpreter, inputBuffer, outputBuffer)
                latencies.add((System.nanoTime() - start) / 1_000_000.0)
            }
            latencies.sort()
            return percentile(latencies, 50)
        } catch (e: Exception) {
            Timber.d("Delegate %s trial inference failed: %s", delegateName, e.message)
            return null
        } finally {
            closeInterpreter(interpreter)
        }
    }

    /**
     * Create a TFLite interpreter configured with the specified delegate.
     * Uses reflection so there is no hard compile-time dependency on TFLite delegate libraries.
     *
     * @return Configured interpreter, or null if the delegate class is not on the classpath.
     */
    internal fun createInterpreterWithDelegate(modelFile: File, delegateName: String): Any? {
        return try {
            val optionsClass = Class.forName("org.tensorflow.lite.Interpreter\$Options")
            val options = optionsClass.getDeclaredConstructor().newInstance()

            val delegate = when (delegateName) {
                "nnapi" -> {
                    val nnapiClass = Class.forName(
                        "org.tensorflow.lite.nnapi.NnApiDelegate",
                    )
                    nnapiClass.getDeclaredConstructor().newInstance()
                }
                "gpu" -> {
                    val gpuClass = Class.forName(
                        "org.tensorflow.lite.gpu.GpuDelegate",
                    )
                    gpuClass.getDeclaredConstructor().newInstance()
                }
                "xnnpack" -> {
                    // XNNPACK is the default; no delegate to add, use plain interpreter
                    return loadInterpreter(modelFile)
                }
                else -> return null
            }

            // options.addDelegate(delegate)
            val delegateBaseClass = Class.forName("org.tensorflow.lite.Delegate")
            val addDelegateMethod = optionsClass.getMethod("addDelegate", delegateBaseClass)
            addDelegateMethod.invoke(options, delegate)

            // new Interpreter(modelFile, options)
            val interpreterClass = Class.forName("org.tensorflow.lite.Interpreter")
            val constructor = interpreterClass.getConstructor(File::class.java, optionsClass)
            constructor.newInstance(modelFile, options)
        } catch (e: ClassNotFoundException) {
            Timber.d("Delegate %s class not found on classpath", delegateName)
            null
        } catch (e: Exception) {
            Timber.d("Failed to create interpreter with delegate %s: %s", delegateName, e.message)
            null
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
