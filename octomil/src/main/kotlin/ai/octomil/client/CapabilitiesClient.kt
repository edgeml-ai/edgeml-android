package ai.octomil.client

import ai.octomil.generated.DeviceClass
import ai.octomil.utils.DeviceUtils
import android.content.Context

/**
 * Provides a snapshot of device hardware and runtime capabilities.
 *
 * Accessed via `client.capabilities`.
 *
 * ```kotlin
 * val profile = client.capabilities.current()
 * when (profile.deviceClass) {
 *     DeviceClass.FLAGSHIP -> loadLargeModel()
 *     DeviceClass.LOW      -> loadQuantizedModel()
 *     else                 -> loadDefaultModel()
 * }
 * ```
 */
class CapabilitiesClient internal constructor(
    private val context: Context,
) {
    /**
     * Collect a [CapabilityProfile] reflecting the current device state.
     *
     * This inspects available RAM, storage, and probes for hardware
     * accelerator support (NNAPI, GPU delegate, XNNPACK, vendor NPU).
     * The call is synchronous and cheap -- no I/O or network.
     */
    fun current(): CapabilityProfile {
        val memoryMb = DeviceUtils.getTotalMemoryMb(context)
        val storageMb = DeviceUtils.getAvailableStorageMb()
        val deviceClass = classifyDevice(memoryMb)
        val runtimes = detectRuntimes()
        val accelerators = detectAccelerators()

        return CapabilityProfile(
            deviceClass = deviceClass,
            availableRuntimes = runtimes,
            memoryMb = memoryMb,
            storageMb = storageMb,
            platform = "android",
            accelerators = accelerators,
        )
    }

    // =========================================================================
    // Detection helpers
    // =========================================================================

    /**
     * Classify device tier based on total RAM.
     *
     * Thresholds follow the contract DeviceClass enum:
     * - FLAGSHIP: >= 8 GB
     * - HIGH:     >= 6 GB
     * - MID:      >= 4 GB
     * - LOW:      < 4 GB
     */
    internal fun classifyDevice(memoryMb: Long): DeviceClass = when {
        memoryMb >= 8 * 1024 -> DeviceClass.FLAGSHIP
        memoryMb >= 6 * 1024 -> DeviceClass.HIGH
        memoryMb >= 4 * 1024 -> DeviceClass.MID
        else -> DeviceClass.LOW
    }

    /**
     * Detect available inference runtimes.
     *
     * TFLite (CPU) is always present. NNAPI and GPU are probed via [DeviceUtils].
     */
    internal fun detectRuntimes(): List<String> = buildList {
        add("tflite")
        if (DeviceUtils.isNnapiAvailable()) add("nnapi")
        if (DeviceUtils.isGpuAvailable()) add("gpu")
    }

    /**
     * Detect available hardware accelerators.
     *
     * - XNNPACK: always available (bundled with TFLite).
     * - NNAPI: Android 9+.
     * - GPU: probed via TFLite CompatibilityList.
     * - Vendor NPU: detected by classpath probing for known delegate classes.
     */
    internal fun detectAccelerators(): List<String> = buildList {
        add("xnnpack")
        if (DeviceUtils.isNnapiAvailable()) add("nnapi")
        if (DeviceUtils.isGpuAvailable()) add("gpu")
        if (isVendorNpuAvailable()) add("vendor_npu")
    }

    /**
     * Probe for known vendor NPU delegate classes on the classpath.
     */
    private fun isVendorNpuAvailable(): Boolean {
        val delegateClasses = listOf(
            "com.qualcomm.qti.QnnDelegate",
            "com.samsung.android.sdk.eden.EdenDelegate",
            "com.mediatek.neuropilot.NeuronDelegate",
        )
        return delegateClasses.any { className ->
            try {
                Class.forName(className)
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }
    }
}
