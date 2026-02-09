package ai.edgeml.sdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Collects and manages device information for EdgeML platform.
 *
 * Automatically gathers:
 * - Stable device identifier (Android ID)
 * - Hardware specs (CPU, memory, storage, GPU)
 * - System info (Android version, manufacturer, model)
 * - Runtime constraints (battery, network)
 * - Locale and timezone
 *
 * Example:
 * ```kotlin
 * val deviceInfo = DeviceInfo(context)
 * val registrationData = deviceInfo.toRegistrationMap()
 * ```
 */
class DeviceInfo(
    private val context: Context,
) {
    // MARK: - Properties

    /**
     * Stable device identifier (Android ID)
     */
    val deviceId: String
        get() =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID,
            ) ?: UUID.randomUUID().toString()

    // MARK: - Device Hardware

    /**
     * Get device manufacturer (e.g., "Samsung", "Google")
     */
    val manufacturer: String
        get() = Build.MANUFACTURER

    /**
     * Get device model (e.g., "Pixel 7 Pro", "Galaxy S23")
     */
    val model: String
        get() = Build.MODEL

    /**
     * Get CPU architecture (e.g., "arm64-v8a", "armeabi-v7a")
     */
    val cpuArchitecture: String
        get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    /**
     * Check if GPU/NPU is available for ML inference
     */
    val gpuAvailable: Boolean
        get() {
            // Check for NNAPI support (Android 8.1+)
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
        }

    /**
     * Get total physical memory in MB
     */
    val totalMemoryMB: Long?
        get() {
            return try {
                val activityManager =
                    context.getSystemService(Context.ACTIVITY_SERVICE)
                        as android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)
                memInfo.totalMem / (1024 * 1024)
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Get available storage space in MB
     */
    val availableStorageMB: Long?
        get() {
            return try {
                val stat = StatFs(context.filesDir.absolutePath)
                val bytesAvailable = stat.availableBytes
                bytesAvailable / (1024 * 1024)
            } catch (e: Exception) {
                null
            }
        }

    // MARK: - Runtime Constraints

    /**
     * Get current battery level (0-100)
     */
    val batteryLevel: Int?
        get() {
            return try {
                val batteryManager =
                    context.getSystemService(Context.BATTERY_SERVICE)
                        as BatteryManager
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Get current network type (wifi, cellular, offline, unknown)
     */
    val networkType: String
        get() {
            return try {
                val connectivityManager =
                    context.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as ConnectivityManager

                val network = connectivityManager.activeNetwork ?: return "offline"
                val capabilities =
                    connectivityManager.getNetworkCapabilities(network)
                        ?: return "unknown"

                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "unknown"
                }
            } catch (e: Exception) {
                "unknown"
            }
        }

    // MARK: - System Info

    /**
     * Get Android platform string
     */
    val platform: String = "android"

    /**
     * Get Android version (e.g., "13", "14")
     */
    val osVersion: String
        get() = Build.VERSION.RELEASE

    /**
     * Get user's locale
     */
    val locale: String
        get() = Locale.getDefault().toString()

    /**
     * Get user's region
     */
    val region: String
        get() = Locale.getDefault().country ?: "US"

    /**
     * Get user's timezone
     */
    val timezone: String
        get() = TimeZone.getDefault().id

    // MARK: - Collection Methods

    /**
     * Collect complete device hardware information
     */
    fun collectDeviceInfo(): Map<String, Any?> {
        val info =
            mutableMapOf<String, Any?>(
                "manufacturer" to manufacturer,
                "model" to model,
                "cpu_architecture" to cpuArchitecture,
                "gpu_available" to gpuAvailable,
            )

        totalMemoryMB?.let { info["total_memory_mb"] = it }
        availableStorageMB?.let { info["available_storage_mb"] = it }

        return info
    }

    /**
     * Collect runtime metadata (battery, network)
     */
    fun collectMetadata(): Map<String, Any?> {
        val metadata =
            mutableMapOf<String, Any?>(
                "network_type" to networkType,
            )

        batteryLevel?.let { metadata["battery_level"] = it }

        return metadata
    }

    /**
     * Collect ML capabilities
     */
    fun collectCapabilities(): Map<String, Any> =
        mapOf(
            "cpu_architecture" to cpuArchitecture,
            "gpu_available" to gpuAvailable,
            "nnapi" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1),
            "tflite" to true,
        )

    /**
     * Create registration payload for EdgeML API
     */
    fun toRegistrationMap(): Map<String, Any?> =
        mapOf(
            "device_identifier" to deviceId,
            "platform" to platform,
            "os_version" to osVersion,
            "device_info" to collectDeviceInfo(),
            "locale" to locale,
            "region" to region,
            "timezone" to timezone,
            "metadata" to collectMetadata(),
            "capabilities" to collectCapabilities(),
        )

    /**
     * Get updated metadata for heartbeat updates.
     *
     * Call this periodically to send updated battery/network status.
     */
    fun updateMetadata(): Map<String, Any?> = collectMetadata()
}
