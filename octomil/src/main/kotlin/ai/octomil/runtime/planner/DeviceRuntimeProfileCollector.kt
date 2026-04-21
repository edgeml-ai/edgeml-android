package ai.octomil.runtime.planner

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import timber.log.Timber

/**
 * Collects a [DeviceRuntimeProfile] from the current Android device.
 *
 * All data is privacy-safe hardware metadata. No user data, prompts,
 * responses, file paths, or PII is included.
 *
 * Collected fields:
 * - SDK version (BuildConfig)
 * - Android API level
 * - ABI (arm64-v8a, armeabi-v7a, x86_64, etc.)
 * - SoC / device model (Build.HARDWARE, Build.SOC_MODEL, Build.MODEL)
 * - RAM band (ActivityManager.getMemoryInfo)
 * - Available runtimes: detected via reflection on known engine classes
 */
object DeviceRuntimeProfileCollector {

    /**
     * Model-capable evidence entries registered by engine integrations
     * when they load a concrete local artifact.
     *
     * These are merged with the classpath-detected runtimes to give the
     * planner explicit proof that a model can run locally, enabling no-plan
     * local selection without requiring a server plan or benchmark cache hit.
     *
     * Thread-safe: guarded by [evidenceLock].
     */
    private val modelCapableEvidence = mutableListOf<InstalledRuntime>()
    private val evidenceLock = Any()

    /**
     * Register model-capable evidence for a concrete loaded artifact.
     *
     * Engine integrations should call this after successfully loading a model,
     * providing the model ID, capability, and engine. The evidence is included
     * in subsequent [collect] calls, allowing the planner to select this engine
     * for no-plan local resolution.
     *
     * Duplicate registrations (same engine + model) are deduplicated.
     *
     * @param evidence An [InstalledRuntime] with model-capable metadata
     *                 (created via [InstalledRuntime.modelCapable]).
     */
    fun registerEvidence(evidence: InstalledRuntime) {
        synchronized(evidenceLock) {
            val canonicalized = evidence.canonicalized()
            val existingIndex = modelCapableEvidence.indexOfFirst { existing ->
                existing.engine == canonicalized.engine &&
                    existing.metadata[RuntimeEvidenceMetadataKeys.MODELS] ==
                    canonicalized.metadata[RuntimeEvidenceMetadataKeys.MODELS]
            }
            if (existingIndex >= 0) {
                modelCapableEvidence[existingIndex] = canonicalized
            } else {
                modelCapableEvidence.add(canonicalized)
            }
        }
    }

    /**
     * Remove all model-capable evidence entries.
     *
     * Primarily for testing. Production code should not need to call this.
     */
    fun clearEvidence() {
        synchronized(evidenceLock) {
            modelCapableEvidence.clear()
        }
    }

    /**
     * Return a snapshot of registered model-capable evidence.
     */
    internal fun getRegisteredEvidence(): List<InstalledRuntime> {
        synchronized(evidenceLock) {
            return modelCapableEvidence.toList()
        }
    }

    /**
     * Collect the device runtime profile.
     *
     * Merges classpath-detected engine availability with model-capable
     * evidence registered by engine integrations. Generic classpath entries
     * are useful for the server profile but NOT sufficient for no-plan
     * local selection -- only entries with model/capability metadata pass
     * the planner's `supportsLocalDefault` gate.
     *
     * @param context Application context for system service access.
     * @return Populated [DeviceRuntimeProfile].
     */
    fun collect(context: Context): DeviceRuntimeProfile {
        val classpathRuntimes = detectInstalledRuntimes().map { it.canonicalized() }
        val evidence = getRegisteredEvidence()

        // Merge: classpath entries first, then model-capable evidence.
        // Evidence entries supplement (don't replace) classpath entries because
        // they carry different metadata -- classpath entries report availability,
        // evidence entries declare model + capability support.
        val merged = (classpathRuntimes + evidence).distinctBy { rt ->
            // Deduplicate by engine + models metadata.
            // Two entries for the same engine are kept if they declare different models.
            rt.engine to (rt.metadata[RuntimeEvidenceMetadataKeys.MODELS] ?: "")
        }

        return DeviceRuntimeProfile(
            sdk = "android",
            sdkVersion = getSdkVersion(),
            platform = "Android",
            arch = getPrimaryAbi(),
            osVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            chip = getChipName(),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            ramTotalBytes = getRamTotalBytes(context),
            gpuCoreCount = null, // Not reliably detectable on Android
            accelerators = detectAccelerators(),
            installedRuntimes = merged,
        )
    }

    // =========================================================================
    // SDK Version
    // =========================================================================

    internal fun getSdkVersion(): String {
        return try {
            ai.octomil.BuildConfig.OCTOMIL_VERSION
        } catch (_: Exception) {
            "unknown"
        }
    }

    // =========================================================================
    // ABI
    // =========================================================================

    /**
     * Return the primary supported ABI (e.g. "arm64-v8a", "x86_64").
     */
    internal fun getPrimaryAbi(): String {
        return Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"
    }

    /**
     * Return all supported ABIs.
     */
    internal fun getAllAbis(): List<String> {
        return Build.SUPPORTED_ABIS?.toList() ?: emptyList()
    }

    // =========================================================================
    // SoC / Chip
    // =========================================================================

    /**
     * Get the chip/SoC identifier.
     *
     * On API 31+ uses [Build.SOC_MODEL] for a precise SoC name
     * (e.g. "Snapdragon 8 Gen 2"). Falls back to [Build.HARDWARE] on older levels.
     */
    internal fun getChipName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val socModel = Build.SOC_MODEL
            if (!socModel.isNullOrBlank() && socModel != "unknown") return socModel
        }
        val hw = Build.HARDWARE
        return if (!hw.isNullOrBlank()) hw else null
    }

    // =========================================================================
    // RAM
    // =========================================================================

    /**
     * Get total device RAM in bytes via ActivityManager.
     */
    internal fun getRamTotalBytes(context: Context): Long? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                as? ActivityManager ?: return null
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            memInfo.totalMem
        } catch (e: Exception) {
            Timber.d(e, "Failed to read RAM")
            null
        }
    }

    /**
     * Classify RAM into a band string for human-readable profiles.
     *
     * Bands:
     * - "high" >= 8 GB
     * - "mid"  >= 4 GB
     * - "low"  < 4 GB
     */
    internal fun ramBand(totalBytes: Long?): String {
        if (totalBytes == null) return "unknown"
        val gb = totalBytes / (1024.0 * 1024.0 * 1024.0)
        return when {
            gb >= 8.0 -> "high"
            gb >= 4.0 -> "mid"
            else -> "low"
        }
    }

    // =========================================================================
    // Accelerators
    // =========================================================================

    /**
     * Detect available hardware accelerators.
     */
    internal fun detectAccelerators(): List<String> {
        val accel = mutableListOf<String>()

        // NNAPI (Android 8.1+, deprecated in Android 15)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            accel.add("nnapi")
        }

        // GPU compute (virtually all modern Android devices)
        accel.add("gpu")

        return accel
    }

    // =========================================================================
    // Installed Runtimes
    // =========================================================================

    /**
     * Detect locally-installed inference engines via reflection.
     *
     * Checks for the presence of known engine classes on the classpath.
     * Does not instantiate engines -- just checks availability.
     */
    internal fun detectInstalledRuntimes(): List<InstalledRuntime> {
        val runtimes = mutableListOf<InstalledRuntime>()

        // TFLite is available when either the Octomil engine wrapper or the
        // upstream interpreter is present. We report engine availability here,
        // not model-specific support; the planner requires a server plan,
        // benchmark cache, or explicit metadata before choosing it locally.
        if (
            isClassAvailable("ai.octomil.runtime.engines.tflite.LLMEngine") ||
            isClassAvailable("org.tensorflow.lite.Interpreter")
        ) {
            runtimes.add(
                InstalledRuntime(
                    engine = "tflite",
                    available = true,
                    accelerator = "cpu",
                )
            )
        }

        // ONNX Runtime
        if (isClassAvailable("ai.onnxruntime.OrtSession")) {
            runtimes.add(
                InstalledRuntime(
                    engine = "onnxruntime",
                    available = true,
                    accelerator = "cpu",
                )
            )
        }

        // llama.cpp (via octomil-runtime-llama-android)
        if (isClassAvailable("ai.octomil.runtime.llama.LlamaCppEngine")) {
            runtimes.add(
                InstalledRuntime(
                    engine = "llama.cpp",
                    available = true,
                    accelerator = "cpu",
                )
            )
        }

        // Samsung One (Eden) delegate
        if (isClassAvailable("com.samsung.android.sdk.eden.EdenModel")) {
            runtimes.add(
                InstalledRuntime(
                    engine = "samsung_one",
                    available = true,
                    accelerator = "npu",
                )
            )
        }

        // ExecuTorch
        if (isClassAvailable("org.pytorch.executorch.Module")) {
            runtimes.add(
                InstalledRuntime(
                    engine = "executorch",
                    available = true,
                    accelerator = "cpu",
                )
            )
        }

        // Whisper (via octomil-runtime-sherpa-android)
        if (isClassAvailable("ai.octomil.runtime.sherpa.SherpaOnnxEngine")) {
            runtimes.add(
                InstalledRuntime(
                    engine = "whisper.cpp",
                    available = true,
                    accelerator = "cpu",
                )
            )
        }

        return runtimes
    }

    /**
     * Check if a class is available on the classpath without loading it.
     */
    internal fun isClassAvailable(className: String): Boolean {
        return try {
            Class.forName(className, false, DeviceRuntimeProfileCollector::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
