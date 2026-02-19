package ai.edgeml.pairing

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import timber.log.Timber

/**
 * Collects device hardware capabilities for pairing requests.
 *
 * Gathers enough information for the server to select the optimal model
 * variant (format, quantization, executor) for this specific device.
 */
object DeviceCapabilities {

    /**
     * Collect device information and build a [DeviceConnectRequest].
     *
     * @param context Application context for system service access.
     * @return Populated request with hardware metadata.
     */
    fun collect(context: Context): DeviceConnectRequest {
        return DeviceConnectRequest(
            deviceId = getDeviceId(context),
            platform = "android",
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            chipFamily = getChipFamily(),
            ramGb = getRAMGb(context),
            osVersion = Build.VERSION.RELEASE,
            npuAvailable = hasNNAPI(),
            gpuAvailable = true, // Most Android devices have GPU compute
        )
    }

    /**
     * Get a stable device identifier.
     *
     * Uses ANDROID_ID which is unique per app signing key and device. Falls
     * back to a generic identifier if the setting is unavailable.
     */
    @Suppress("HardwareIds")
    internal fun getDeviceId(context: Context): String {
        return try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID,
            ) ?: "unknown-android-device"
        } catch (e: Exception) {
            Timber.w(e, "Failed to read ANDROID_ID")
            "unknown-android-device"
        }
    }

    /**
     * Get the chip/SoC family identifier.
     *
     * On API 31+ uses [Build.SOC_MODEL] for a precise SoC name (e.g., "Snapdragon 8 Gen 2").
     * Falls back to [Build.HARDWARE] on older API levels.
     */
    internal fun getChipFamily(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.ifBlank { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }
    }

    /**
     * Get total device RAM in gigabytes.
     */
    internal fun getRAMGb(context: Context): Double {
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        } catch (e: Exception) {
            Timber.w(e, "Failed to read total memory")
            0.0
        }
    }

    /**
     * Check if NNAPI is available (Android 8.1+).
     */
    internal fun hasNNAPI(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
    }
}
