package ai.edgeml.utils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import ai.edgeml.api.dto.DeviceCapabilities
import timber.log.Timber
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Utility functions for device information and identification.
 */
object DeviceUtils {

    /**
     * Generate a unique device identifier.
     *
     * Uses Android ID + app-specific salt to create a stable, unique identifier
     * that persists across app reinstalls but is unique per device/app combination.
     */
    @SuppressLint("HardwareIds")
    fun generateDeviceIdentifier(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()

        // Add package name as salt for app-specific ID
        val salt = context.packageName
        val combined = "$androidId:$salt"

        // Hash to create consistent device ID
        return hashString(combined).take(32)
    }

    /**
     * Get device capabilities for registration.
     */
    fun getDeviceCapabilities(context: Context): DeviceCapabilities {
        return DeviceCapabilities(
            cpuArchitecture = getCpuArchitecture(),
            gpuAvailable = isGpuAvailable(),
            nnapiAvailable = isNnapiAvailable(),
            totalMemoryMb = getTotalMemoryMb(context),
            availableStorageMb = getAvailableStorageMb(),
        )
    }

    /**
     * Get the device manufacturer.
     */
    fun getManufacturer(): String = Build.MANUFACTURER

    /**
     * Get the device model.
     */
    fun getModel(): String = Build.MODEL

    /**
     * Get the device locale (e.g., "en_US").
     */
    fun getLocale(): String {
        val locale = Locale.getDefault()
        return "${locale.language}_${locale.country}"
    }

    /**
     * Get the device region based on timezone.
     */
    fun getRegion(): String {
        val timeZone = TimeZone.getDefault().id
        return when {
            timeZone.startsWith("America/") -> "us"
            timeZone.startsWith("Europe/") -> "eu"
            timeZone.startsWith("Asia/") -> "apac"
            else -> "other"
        }
    }

    /**
     * Get the Android OS version string.
     */
    fun getOsVersion(): String {
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    /**
     * Get the CPU architecture.
     */
    fun getCpuArchitecture(): String {
        return Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    }

    /**
     * Check if GPU is likely available for ML operations.
     */
    fun isGpuAvailable(): Boolean {
        return try {
            val compatListClass = Class.forName("org.tensorflow.lite.gpu.CompatibilityList")
            val instance = compatListClass.getDeclaredConstructor().newInstance()
            val method = compatListClass.getMethod("isDelegateSupportedOnThisDevice")
            method.invoke(instance) as? Boolean ?: false
        } catch (e: Exception) {
            // TFLite GPU not available
            false
        }
    }

    /**
     * Check if NNAPI is available.
     */
    fun isNnapiAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    }

    /**
     * Get total device RAM in MB.
     */
    fun getTotalMemoryMb(context: Context): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            memInfo.totalMem / (1024 * 1024)
        } catch (e: Exception) {
            Timber.w(e, "Failed to get total memory")
            0
        }
    }

    /**
     * Get available internal storage in MB.
     */
    fun getAvailableStorageMb(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
        } catch (e: Exception) {
            Timber.w(e, "Failed to get available storage")
            0
        }
    }

    /**
     * Get available RAM in MB.
     */
    fun getAvailableMemoryMb(context: Context): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            memInfo.availMem / (1024 * 1024)
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Check if device is in low memory state.
     */
    fun isLowMemory(context: Context): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            memInfo.lowMemory
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Hash a string using SHA-256.
     */
    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Network connectivity utilities.
 */
object NetworkUtils {
    /**
     * Check if network is available.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected == true
        }
    }

    /**
     * Check if connected to WiFi.
     */
    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.type == android.net.ConnectivityManager.TYPE_WIFI
        }
    }

    /**
     * Check if connected to unmetered network.
     */
    fun isUnmeteredConnection(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        } else {
            isWifiConnected(context)
        }
    }
}

/**
 * Battery utilities.
 */
object BatteryUtils {
    /**
     * Get current battery level (0-100).
     */
    fun getBatteryLevel(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE)
            as android.os.BatteryManager

        return batteryManager.getIntProperty(
            android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
        )
    }

    /**
     * Check if device is charging.
     */
    fun isCharging(context: Context): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE)
            as android.os.BatteryManager

        return batteryManager.isCharging
    }
}
