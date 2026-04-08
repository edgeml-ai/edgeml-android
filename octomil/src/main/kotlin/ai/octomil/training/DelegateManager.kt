package ai.octomil.training

import ai.octomil.config.OctomilConfig
import ai.octomil.models.CachedModel
import android.content.Context
import android.os.Build
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import timber.log.Timber
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Manages TFLite delegate lifecycle: GPU, vendor NPU, and NNAPI.
 *
 * Delegate priority: vendor NPU > GPU > NNAPI (legacy) > XNNPack/CPU.
 * Warmup benchmarks the selected delegate vs CPU after loading.
 *
 * @see TFLiteTrainer
 */
internal class DelegateManager(
    private val context: Context,
    private val config: OctomilConfig,
) {
    var gpuDelegate: GpuDelegate? = null
        private set
    var vendorDelegate: Delegate? = null
        private set

    /**
     * Vendor NPU delegate class names fetched from the server.
     *
     * Populated by [fetchVendorDelegateConfig] during model loading.
     * Empty list means no vendor delegates are available or the server
     * was unreachable -- only the standard NNAPI delegate will be tried.
     */
    private var vendorDelegateClassNames: List<VendorDelegateInfo> = emptyList()

    /**
     * Vendor delegate class name and display name, fetched from the server.
     */
    data class VendorDelegateInfo(
        val className: String,
        val displayName: String,
        /** SoC patterns this delegate applies to (lowercase). Empty = always try. */
        val socPatterns: List<String> = emptyList(),
        /** Manufacturer patterns this delegate applies to (lowercase). Empty = always try. */
        val manufacturerPatterns: List<String> = emptyList(),
    )

    // =========================================================================
    // Delegate Attachment
    // =========================================================================

    /**
     * Configure delegates on interpreter options according to priority chain.
     *
     * @return true if a vendor NPU delegate was attached
     */
    fun configureDelegates(
        options: Interpreter.Options,
        model: CachedModel,
    ): Boolean {
        // Fetch vendor delegate class names from server (if vendor NPU enabled)
        if (config.enableVendorNpu && vendorDelegateClassNames.isEmpty()) {
            vendorDelegateClassNames = fetchVendorDelegateConfig()
        }

        val vendorAttached = if (config.enableVendorNpu) {
            tryAttachVendorNpu(options)
        } else false

        if (!vendorAttached && config.enableGpuAcceleration && isGpuSupported()) {
            tryAttachGpu(options, model)
        }

        if (!vendorAttached && gpuDelegate == null && config.enableNnapi) {
            tryAttachNnapi(options)
        }

        return vendorAttached
    }

    /**
     * Reload the interpreter with GPU delegate (cascading from vendor NPU).
     */
    fun reloadWithGpu(
        model: CachedModel,
        effectiveThreads: Int,
    ): Interpreter {
        gpuDelegate?.close()
        gpuDelegate = null

        val options = Interpreter.Options().apply { setNumThreads(effectiveThreads) }
        tryAttachGpu(options, model)
        return Interpreter(loadModelFile(File(model.filePath)), options)
    }

    /**
     * Reload the interpreter CPU-only (all delegates failed).
     */
    fun reloadCpuOnly(
        model: CachedModel,
        effectiveThreads: Int,
    ): Interpreter {
        gpuDelegate?.close()
        gpuDelegate = null
        closeVendorDelegate()
        vendorDelegate = null

        val options = Interpreter.Options().apply { setNumThreads(effectiveThreads) }
        return Interpreter(loadModelFile(File(model.filePath)), options)
    }

    /**
     * Close and release vendor delegate via reflection.
     */
    fun closeVendorDelegate() {
        val delegate = vendorDelegate ?: return
        try {
            delegate.javaClass.getMethod("close").invoke(delegate)
        } catch (_: Exception) {
            // Vendor delegate may not have a close method
        }
    }

    /**
     * Release all delegate resources.
     */
    fun closeAll() {
        gpuDelegate?.close()
        closeVendorDelegate()
        gpuDelegate = null
        vendorDelegate = null
    }

    // =========================================================================
    // GPU Support
    // =========================================================================

    /**
     * Check if GPU acceleration is supported on this device.
     */
    fun isGpuSupported(): Boolean =
        try {
            val compatList = CompatibilityList()
            compatList.isDelegateSupportedOnThisDevice
        } catch (e: Exception) {
            Timber.w(e, "Failed to check GPU support")
            false
        }

    /**
     * Check if GPU delegate is currently active.
     */
    fun isUsingGpu(): Boolean = gpuDelegate != null

    // =========================================================================
    // GPU Delegate
    // =========================================================================

    /**
     * Attach GPU delegate with serialization and float16 options.
     */
    private fun tryAttachGpu(options: Interpreter.Options, model: CachedModel) {
        try {
            @Suppress("DEPRECATION")
            val delegateOptions = GpuDelegate.Options()

            // GPU shader serialization -- cache compiled programs to skip recompilation
            if (config.enableGpuSerialization) {
                try {
                    val cacheDir = context.cacheDir.resolve("gpu_cache").apply { mkdirs() }
                    val setSerMethod = delegateOptions.javaClass.getMethod(
                        "setSerializationParams",
                        String::class.java,
                        String::class.java,
                    )
                    setSerMethod.invoke(
                        delegateOptions,
                        cacheDir.absolutePath,
                        "${model.modelId}_${model.version}",
                    )
                    Timber.d("GPU shader serialization enabled")
                } catch (_: NoSuchMethodException) {
                    Timber.d("GPU serialization not available in this TFLite version")
                } catch (e: Exception) {
                    Timber.w(e, "Failed to enable GPU serialization")
                }
            }

            if (config.enableFloat16Inference) {
                delegateOptions.setPrecisionLossAllowed(true)
                delegateOptions.setInferencePreference(
                    GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED,
                )
                Timber.d("GPU float16 inference enabled")
            }

            gpuDelegate = GpuDelegate(delegateOptions)
            options.addDelegate(gpuDelegate)
            Timber.i("GPU delegate attached")
        } catch (e: Exception) {
            Timber.w(e, "Failed to create GPU delegate, falling back to CPU")
            gpuDelegate?.close()
            gpuDelegate = null
        }
    }

    // =========================================================================
    // NNAPI Delegate
    // =========================================================================

    /**
     * Attach NNAPI delegate for Android 8.1-14 devices.
     * NNAPI is deprecated in Android 15+; use vendor NPU delegates instead.
     */
    @Suppress("DEPRECATION")
    private fun tryAttachNnapi(options: Interpreter.Options) {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.O_MR1..34) {
            Timber.d("NNAPI skipped: API ${Build.VERSION.SDK_INT} outside supported range 27-34")
            return
        }
        try {
            options.setUseNNAPI(true)
            Timber.i("NNAPI delegate enabled (API ${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            Timber.w(e, "Failed to enable NNAPI delegate")
        }
    }

    // =========================================================================
    // Vendor NPU Delegate
    // =========================================================================

    /**
     * Try to attach vendor NPU delegates via reflection.
     * Returns true if a vendor delegate was successfully attached.
     */
    private fun tryAttachVendorNpu(options: Interpreter.Options): Boolean {
        if (vendorDelegateClassNames.isEmpty()) {
            Timber.d("No vendor delegate config from server -- skipping vendor NPU")
            return false
        }

        val soc = getSocIdentifier().lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()

        for (delegate in vendorDelegateClassNames) {
            // Check SoC pattern match (empty patterns = always try)
            val socMatch = delegate.socPatterns.isEmpty() ||
                delegate.socPatterns.any { pattern -> soc.contains(pattern) }
            // Check manufacturer pattern match (empty patterns = always try)
            val mfrMatch = delegate.manufacturerPatterns.isEmpty() ||
                delegate.manufacturerPatterns.any { pattern -> manufacturer.contains(pattern) }

            if (socMatch && mfrMatch) {
                if (tryLoadReflectionDelegate(options, delegate.className, delegate.displayName)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Fetch vendor delegate configuration from the server.
     */
    private fun fetchVendorDelegateConfig(): List<VendorDelegateInfo> {
        return try {
            val soc = getSocIdentifier()
            val modelId = config.modelId ?: return emptyList()
            val url = "${config.serverUrl}/api/v1/models/$modelId/optimized-config/$soc"

            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer ${config.deviceAccessToken}")
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000

            if (connection.responseCode == 200) {
                val body = connection.inputStream.bufferedReader().readText()
                parseVendorDelegateResponse(body)
            } else {
                Timber.d("Vendor delegate config fetch returned HTTP ${connection.responseCode}")
                emptyList()
            }
        } catch (e: Exception) {
            Timber.d(e, "Failed to fetch vendor delegate config from server")
            emptyList()
        }
    }

    /**
     * Parse the vendor delegate configuration from the server JSON response.
     */
    private fun parseVendorDelegateResponse(json: String): List<VendorDelegateInfo> {
        return try {
            val root = org.json.JSONObject(json)
            val delegates = root.optJSONArray("vendor_delegates") ?: return emptyList()
            (0 until delegates.length()).mapNotNull { i ->
                val obj = delegates.getJSONObject(i)
                val className = obj.optString("class_name", "")
                if (className.isBlank()) return@mapNotNull null

                val socPatterns = obj.optJSONArray("soc_patterns")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()

                val mfrPatterns = obj.optJSONArray("manufacturer_patterns")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()

                VendorDelegateInfo(
                    className = className,
                    displayName = obj.optString("display_name", className.substringAfterLast('.')),
                    socPatterns = socPatterns,
                    manufacturerPatterns = mfrPatterns,
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse vendor delegate config")
            emptyList()
        }
    }

    /**
     * Load a TFLite delegate by class name via reflection.
     */
    private fun tryLoadReflectionDelegate(
        options: Interpreter.Options,
        className: String,
        displayName: String,
    ): Boolean {
        return try {
            val clazz = Class.forName(className)
            val instance = clazz.getDeclaredConstructor().newInstance()
            if (instance is Delegate) {
                options.addDelegate(instance)
                vendorDelegate = instance
                Timber.i("$displayName delegate attached via reflection")
                true
            } else {
                Timber.w("$displayName class does not implement Delegate interface")
                false
            }
        } catch (_: ClassNotFoundException) {
            Timber.d("$displayName delegate not on classpath (add vendor AAR to enable)")
            false
        } catch (e: Exception) {
            Timber.w(e, "Failed to initialize $displayName delegate")
            false
        }
    }

    // =========================================================================
    // SoC Detection
    // =========================================================================

    /**
     * Get the SoC identifier for vendor delegate gating.
     * Uses Build.SOC_MODEL on API 31+ (e.g., "SM8550"), falls back to Build.HARDWARE.
     */
    fun getSocIdentifier(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }
    }

    // =========================================================================
    // Thread Detection
    // =========================================================================

    /**
     * Resolve the effective thread count. When [OctomilConfig.preferBigCores] is true,
     * detects ARM big.LITTLE topology and returns the performance core count.
     */
    fun resolveThreadCount(): Int {
        if (!config.preferBigCores) return config.numThreads
        val bigCores = detectBigCoreCount()
        if (bigCores != config.numThreads) {
            Timber.d("Thread count: %d big cores detected (config=%d)", bigCores, config.numThreads)
        }
        return bigCores
    }

    /**
     * Detect the number of "big" (performance) cores on ARM big.LITTLE SoCs by
     * reading max CPU frequencies from sysfs. Falls back to [config.numThreads].
     */
    private fun detectBigCoreCount(): Int {
        return try {
            val cpuDir = File("/sys/devices/system/cpu/")
            val cores = cpuDir.listFiles { file -> file.name.matches(Regex("cpu\\d+")) }
                ?: return config.numThreads

            val maxFreqs = cores.mapNotNull { core ->
                try {
                    File(core, "cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
                } catch (_: Exception) {
                    null
                }
            }

            if (maxFreqs.isEmpty()) return config.numThreads

            val topFreq = maxFreqs.max()
            val threshold = (topFreq * 0.8).toLong()
            maxFreqs.count { it >= threshold }.coerceAtLeast(1)
        } catch (_: Exception) {
            config.numThreads
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun loadModelFile(file: File): MappedByteBuffer =
        file.inputStream().channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
}
