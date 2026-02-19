package ai.edgeml.runtime

import android.content.Context
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer

/**
 * TFLite interpreter wrapper with automatic delegate fallback chain.
 *
 * Loads a TFLite model with the preferred delegate (NNAPI, GPU, or XNNPACK)
 * and automatically falls through the chain if a delegate fails to load.
 * Uses reflection so there is no hard compile-time dependency on TFLite
 * delegate libraries (same pattern as [ai.edgeml.pairing.BenchmarkRunner]).
 *
 * ## Fallback chain
 * 1. Try preferred delegate (e.g. NNAPI)
 * 2. If that fails → try GPU
 * 3. If that fails → XNNPACK (plain CPU interpreter, always works)
 *
 * ## Usage
 * ```kotlin
 * val interpreter = AdaptiveInterpreter(modelFile, context)
 * val result = interpreter.load("nnapi")
 * println("Loaded with ${result.delegate}, failed: ${result.failedDelegates}")
 *
 * interpreter.run(inputBuffer, outputBuffer)
 *
 * // Later, adapt to new conditions:
 * interpreter.reload("xnnpack")
 *
 * interpreter.close()
 * ```
 */
class AdaptiveInterpreter(
    private val modelFile: File,
    @Suppress("unused") private val context: Context,
) {
    /** The underlying TFLite Interpreter instance (accessed via reflection). */
    private var interpreter: Any? = null

    /** Delegate currently in use. */
    var activeDelegate: String = "unknown"
        private set

    /**
     * Result of loading a model with delegate fallback.
     */
    data class LoadResult(
        /** The delegate that was successfully used. */
        val delegate: String,
        /** Delegates that were attempted but failed. */
        val failedDelegates: List<String>,
        /** Time taken to load the model in milliseconds. */
        val loadTimeMs: Double,
    )

    /**
     * Load the model with delegate fallback.
     *
     * Tries the preferred delegate first, then falls through NNAPI → GPU → XNNPACK.
     * The fallback chain skips delegates that come before the preferred one in the
     * chain (e.g. if preferred is "gpu", NNAPI is not attempted).
     *
     * @param preferredDelegate Starting delegate: "nnapi", "gpu", or "xnnpack".
     * @return [LoadResult] describing which delegate succeeded and which failed.
     * @throws RuntimeException if even XNNPACK (plain CPU) fails to load.
     */
    suspend fun load(preferredDelegate: String = "nnapi"): LoadResult {
        close() // release any previous interpreter

        val chain = buildFallbackChain(preferredDelegate)
        val failedDelegates = mutableListOf<String>()
        val loadStart = System.nanoTime()

        for (delegate in chain) {
            val loaded = tryLoadDelegate(delegate)
            if (loaded != null) {
                interpreter = loaded
                activeDelegate = delegate
                val loadTimeMs = (System.nanoTime() - loadStart) / 1_000_000.0
                Timber.i(
                    "AdaptiveInterpreter loaded with delegate=%s failed=%s loadTime=%.1fms",
                    delegate, failedDelegates, loadTimeMs,
                )
                return LoadResult(
                    delegate = delegate,
                    failedDelegates = failedDelegates,
                    loadTimeMs = loadTimeMs,
                )
            } else {
                failedDelegates.add(delegate)
                Timber.d("Delegate %s failed, trying next in chain", delegate)
            }
        }

        // Should never reach here since xnnpack (plain interpreter) is always last
        val loadTimeMs = (System.nanoTime() - loadStart) / 1_000_000.0
        throw RuntimeException(
            "All delegates failed to load model: ${modelFile.name}. " +
                "Tried: ${chain.joinToString()}, all failed.",
        )
    }

    /**
     * Run inference on the loaded interpreter.
     *
     * @param input Input [ByteBuffer] matching the model's input tensor shape.
     * @param output Output [ByteBuffer] matching the model's output tensor shape.
     * @throws IllegalStateException if the interpreter has not been loaded.
     * @throws RuntimeException if inference fails.
     */
    fun run(input: ByteBuffer, output: ByteBuffer) {
        val interp = interpreter
            ?: throw IllegalStateException("Interpreter not loaded. Call load() first.")

        try {
            val method = interp.javaClass.getMethod(
                "run",
                Any::class.java,
                Any::class.java,
            )
            method.invoke(interp, input, output)
        } catch (e: Exception) {
            throw RuntimeException("Inference failed on delegate $activeDelegate: ${e.message}", e)
        }
    }

    /**
     * Reload the interpreter with a different delegate.
     *
     * Closes the current interpreter and loads with the new delegate
     * (with full fallback chain from that delegate).
     *
     * @param delegate New preferred delegate.
     * @return [LoadResult] from the reload.
     */
    suspend fun reload(delegate: String): LoadResult {
        Timber.d("Reloading interpreter: %s -> %s", activeDelegate, delegate)
        return load(delegate)
    }

    /**
     * Release interpreter resources.
     */
    fun close() {
        val interp = interpreter ?: return
        try {
            val method = interp.javaClass.getMethod("close")
            method.invoke(interp)
        } catch (e: Exception) {
            Timber.w(e, "Failed to close interpreter")
        }
        interpreter = null
        activeDelegate = "unknown"
    }

    // =========================================================================
    // Internal: fallback chain and delegate loading
    // =========================================================================

    /**
     * Build the delegate fallback chain starting from the preferred delegate.
     *
     * Full chain order: nnapi → gpu → xnnpack.
     * If preferred is "gpu", chain is: gpu → xnnpack.
     * If preferred is "xnnpack", chain is: xnnpack.
     */
    internal fun buildFallbackChain(preferredDelegate: String): List<String> {
        val fullChain = listOf("nnapi", "gpu", "xnnpack")
        val startIndex = fullChain.indexOf(preferredDelegate).coerceAtLeast(0)
        return fullChain.subList(startIndex, fullChain.size)
    }

    /**
     * Try to load the interpreter with a specific delegate via reflection.
     *
     * @return The interpreter instance, or null if the delegate is unavailable.
     */
    internal fun tryLoadDelegate(delegateName: String): Any? {
        return try {
            when (delegateName) {
                "xnnpack" -> {
                    // XNNPACK is the default CPU delegate — plain interpreter
                    loadPlainInterpreter()
                }
                "nnapi" -> {
                    loadInterpreterWithDelegate(
                        "org.tensorflow.lite.nnapi.NnApiDelegate",
                    )
                }
                "gpu" -> {
                    loadInterpreterWithDelegate(
                        "org.tensorflow.lite.gpu.GpuDelegate",
                    )
                }
                else -> {
                    Timber.d("Unknown delegate: %s", delegateName)
                    null
                }
            }
        } catch (e: Exception) {
            Timber.d("Failed to load delegate %s: %s", delegateName, e.message)
            null
        }
    }

    private fun loadPlainInterpreter(): Any {
        val interpreterClass = Class.forName("org.tensorflow.lite.Interpreter")
        val constructor = interpreterClass.getConstructor(File::class.java)
        return constructor.newInstance(modelFile)
    }

    private fun loadInterpreterWithDelegate(delegateClassName: String): Any? {
        return try {
            val optionsClass = Class.forName("org.tensorflow.lite.Interpreter\$Options")
            val options = optionsClass.getDeclaredConstructor().newInstance()

            val delegateClass = Class.forName(delegateClassName)
            val delegate = delegateClass.getDeclaredConstructor().newInstance()

            val delegateBaseClass = Class.forName("org.tensorflow.lite.Delegate")
            val addDelegateMethod = optionsClass.getMethod("addDelegate", delegateBaseClass)
            addDelegateMethod.invoke(options, delegate)

            val interpreterClass = Class.forName("org.tensorflow.lite.Interpreter")
            val constructor = interpreterClass.getConstructor(File::class.java, optionsClass)
            constructor.newInstance(modelFile, options)
        } catch (e: ClassNotFoundException) {
            Timber.d("Delegate class not found: %s", delegateClassName)
            null
        } catch (e: Exception) {
            Timber.d("Failed to create interpreter with %s: %s", delegateClassName, e.message)
            null
        }
    }
}
