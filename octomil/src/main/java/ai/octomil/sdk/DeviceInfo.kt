package ai.octomil.sdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import androidx.annotation.ChecksSdkIntAtLeast
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Collects and manages device information for Octomil platform.
 *
 * Automatically gathers:
 * - Resettable device identifier (app-scoped UUID, not tied to hardware)
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
     * Resettable, app-scoped device identifier.
     *
     * Uses a UUID stored in SharedPreferences instead of Android ID
     * to avoid non-resettable persistent identifiers that put user
     * privacy at risk. The identifier is stable across app launches
     * but resets on app data clear or reinstall.
     */
    val deviceId: String
        get() {
            val prefs = context.getSharedPreferences("octomil_device", Context.MODE_PRIVATE)
            return prefs.getString("device_id", null) ?: run {
                val id = UUID.randomUUID().toString()
                prefs.edit().putString("device_id", id).apply()
                id
            }
        }

    // MARK: - Device Hardware

    /**
     * Get device manufacturer (e.g., "Samsung", "Google")
     */
    val manufacturer: String
        get() = Build.MANUFACTURER ?: "unknown"

    /**
     * Get device model (e.g., "Pixel 7 Pro", "Galaxy S23")
     */
    val model: String
        get() = Build.MODEL ?: "unknown"

    /**
     * Get CPU architecture (e.g., "arm64-v8a", "armeabi-v7a")
     */
    val cpuArchitecture: String
        get() = Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"

    /**
     * Check if GPU/NPU is available for ML inference
     */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O_MR1)
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

    /**
     * Maps this device to an Octomil server device profile key.
     *
     * The profile is fetched from `GET /api/v1/devices/profiles` and cached
     * both in-memory and on disk. When the server is unreachable, a minimal
     * RAM-based fallback is used (high/mid/low — no vendor-specific profiles).
     */
    val deviceProfile: String
        get() = deviceProfileClient?.getProfile(this) ?: ramOnlyFallback()

    /**
     * Classify device by RAM only. No vendor-specific logic.
     */
    private fun ramOnlyFallback(): String {
        val ramMb = totalMemoryMB ?: 0
        return when {
            ramMb >= 7000 -> "high_end"
            ramMb >= 4000 -> "mid_range"
            else -> "low_end"
        }
    }

    /**
     * Attach a [DeviceProfileClient] so that [deviceProfile] can resolve
     * profiles from the server. When null, only [ramOnlyFallback] is used.
     */
    var deviceProfileClient: DeviceProfileClient? = null

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
        get() = Build.VERSION.RELEASE ?: "unknown"

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
     * Create registration payload for Octomil API
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

// =============================================================================
// Server-fetched device profile models
// =============================================================================

/**
 * A single device profile rule from the server.
 *
 * The server returns a list of these; the client matches the first rule whose
 * constraints are satisfied by the current device.
 */
@Serializable
data class DeviceProfileRule(
    @SerialName("profile") val profile: String,
    @SerialName("min_ram_mb") val minRamMb: Long = 0,
    @SerialName("max_ram_mb") val maxRamMb: Long = Long.MAX_VALUE,
)

/**
 * Server response for `GET /api/v1/devices/profiles`.
 */
@Serializable
data class DeviceProfilesResponse(
    @SerialName("profiles") val profiles: List<DeviceProfileRule>,
    @SerialName("ttl_seconds") val ttlSeconds: Int = 3600,
    @SerialName("fetched_at") var fetchedAt: Double = 0.0,
) {
    val isExpired: Boolean
        get() = fetchedAt == 0.0 ||
            (System.currentTimeMillis() / 1000.0 - fetchedAt) > ttlSeconds
}

// =============================================================================
// DeviceProfileClient
// =============================================================================

/**
 * Fetches and caches device profile configuration from the Octomil server.
 *
 * Priority: in-memory (if not expired) -> server fetch -> disk cache -> RAM-only fallback.
 *
 * Thread-safe via `@Synchronized`.
 */
class DeviceProfileClient(
    private val context: Context,
    private val apiBase: String,
    private val apiKey: String? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serverUrl = apiBase.trimEnd('/')
    private val cacheFile = File(context.cacheDir, "octomil_device_profiles.json")

    @Volatile
    private var cachedResponse: DeviceProfilesResponse? = null

    /**
     * Resolve the device profile for [deviceInfo].
     *
     * Returns null when no server config is available and the caller should
     * fall back to the RAM-only default inside [DeviceInfo].
     */
    @Synchronized
    fun getProfile(deviceInfo: DeviceInfo): String? {
        val response = getProfilesResponse() ?: return null
        return matchProfile(response, deviceInfo)
    }

    // =========================================================================
    // Internal — config resolution
    // =========================================================================

    private fun getProfilesResponse(): DeviceProfilesResponse? {
        val mem = cachedResponse
        if (mem != null && !mem.isExpired) return mem

        val fetched = fetchFromServer()
        if (fetched != null) {
            cachedResponse = fetched
            persistToDisk(fetched)
            return fetched
        }

        // Server unreachable — try expired in-memory cache.
        if (mem != null) {
            Timber.i("Using expired in-memory device profiles (ttl=%d)", mem.ttlSeconds)
            return mem
        }

        // Try disk cache.
        val disk = loadFromDisk()
        if (disk != null) {
            Timber.i("Using disk-cached device profiles")
            cachedResponse = disk
            return disk
        }

        return null
    }

    private fun matchProfile(
        response: DeviceProfilesResponse,
        deviceInfo: DeviceInfo,
    ): String? {
        val ramMb = deviceInfo.totalMemoryMB ?: 0
        for (rule in response.profiles) {
            if (ramMb >= rule.minRamMb && ramMb < rule.maxRamMb) {
                return rule.profile
            }
        }
        return null
    }

    // =========================================================================
    // HTTP
    // =========================================================================

    private fun fetchFromServer(): DeviceProfilesResponse? {
        val requestBuilder = Request.Builder()
            .url("$serverUrl/api/v1/devices/profiles")
            .get()
            .header("User-Agent", "octomil-android/1.0")

        if (apiKey != null) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        return try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    val parsed = json.decodeFromString(
                        DeviceProfilesResponse.serializer(),
                        body,
                    )
                    parsed.fetchedAt = System.currentTimeMillis() / 1000.0
                    parsed
                } else {
                    Timber.w("Device profiles fetch returned HTTP %d", response.code)
                    null
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Device profiles fetch failed")
            null
        }
    }

    // =========================================================================
    // Disk Cache
    // =========================================================================

    private fun persistToDisk(response: DeviceProfilesResponse) {
        try {
            cacheFile.writeText(
                json.encodeToString(DeviceProfilesResponse.serializer(), response),
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to write device profiles cache")
        }
    }

    private fun loadFromDisk(): DeviceProfilesResponse? {
        if (!cacheFile.exists()) return null
        return try {
            json.decodeFromString(DeviceProfilesResponse.serializer(), cacheFile.readText())
        } catch (e: Exception) {
            Timber.w(e, "Failed to read device profiles cache")
            null
        }
    }
}
