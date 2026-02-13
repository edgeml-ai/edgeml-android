package ai.edgeml.config

import kotlinx.serialization.Serializable

/**
 * Configuration for the EdgeML SDK.
 *
 * Use [EdgeMLConfig.Builder] to create a configuration instance:
 * ```kotlin
 * val config = EdgeMLConfig.Builder()
 *     .serverUrl("https://api.edgeml.ai")
 *     .deviceAccessToken("<short-lived-device-token>")
 *     .modelId("model-123")
 *     .build()
 * ```
 */
@Serializable
data class EdgeMLConfig(
    /** Base URL of the EdgeML server */
    val serverUrl: String,
    /** Short-lived device access token for authentication */
    val deviceAccessToken: String,
    /** Organization ID */
    val orgId: String,
    /** Model ID to use for training/inference */
    val modelId: String,
    /** Device ID (auto-generated if not provided) */
    val deviceId: String? = null,
    /** App version for device metadata */
    val appVersion: String? = null,
    /** Enable debug logging */
    val debugMode: Boolean = false,
    /** Connection timeout in milliseconds */
    val connectionTimeoutMs: Long = 30_000L,
    /** Read timeout in milliseconds */
    val readTimeoutMs: Long = 60_000L,
    /** Write timeout in milliseconds */
    val writeTimeoutMs: Long = 60_000L,
    /** Maximum retries for network operations */
    val maxRetries: Int = 3,
    /** Retry delay in milliseconds (with exponential backoff) */
    val retryDelayMs: Long = 1_000L,
    /** Model cache size in bytes (default: 100MB) */
    val modelCacheSizeBytes: Long = 100 * 1024 * 1024L,
    /** Enable TensorFlow Lite GPU delegate */
    val enableGpuAcceleration: Boolean = true,
    /** Number of TFLite interpreter threads */
    val numThreads: Int = 4,
    /** Enable background sync via WorkManager */
    val enableBackgroundSync: Boolean = true,
    /** Background sync interval in minutes */
    val syncIntervalMinutes: Long = 60L,
    /** Enable heartbeat for device health tracking */
    val enableHeartbeat: Boolean = true,
    /** Heartbeat interval in seconds */
    val heartbeatIntervalSeconds: Long = 300L,
    /** Minimum battery level for background sync (0-100) */
    val minBatteryLevel: Int = 20,
    /** Require charging for background sync */
    val requireCharging: Boolean = false,
    /** Require unmetered network (WiFi) for background sync */
    val requireUnmeteredNetwork: Boolean = true,
    /** Enable encrypted storage for sensitive data */
    val enableEncryptedStorage: Boolean = true,
    /** Privacy configuration for upload behavior and differential privacy */
    val privacyConfiguration: PrivacyConfiguration = PrivacyConfiguration.DEFAULT,
    /** Enable secure aggregation (SecAgg+) for weight uploads */
    val enableSecureAggregation: Boolean = false,
    /** SHA-256 certificate pin hashes for certificate pinning (base64-encoded) */
    val certificatePins: List<String> = emptyList(),
    /** Hostname to pin certificates against (required when certificatePins is non-empty) */
    val pinnedHostname: String = "",
) {
    init {
        require(serverUrl.isNotBlank()) { "serverUrl must not be blank" }
        require(deviceAccessToken.isNotBlank()) { "deviceAccessToken must not be blank" }
        require(orgId.isNotBlank()) { "orgId must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(connectionTimeoutMs > 0) { "connectionTimeoutMs must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
        require(writeTimeoutMs > 0) { "writeTimeoutMs must be positive" }
        require(maxRetries >= 0) { "maxRetries must be non-negative" }
        require(retryDelayMs > 0) { "retryDelayMs must be positive" }
        require(modelCacheSizeBytes > 0) { "modelCacheSizeBytes must be positive" }
        require(numThreads > 0) { "numThreads must be positive" }
        require(syncIntervalMinutes >= 15) { "syncIntervalMinutes must be at least 15" }
        require(heartbeatIntervalSeconds >= 60) { "heartbeatIntervalSeconds must be at least 60" }
        require(minBatteryLevel in 0..100) { "minBatteryLevel must be 0-100" }
    }

    /**
     * Builder for [EdgeMLConfig].
     */
    class Builder {
        private var serverUrl: String = ""
        private var deviceAccessToken: String = ""
        private var orgId: String = ""
        private var modelId: String = ""
        private var deviceId: String? = null
        private var appVersion: String? = null
        private var debugMode: Boolean = false
        private var connectionTimeoutMs: Long = 30_000L
        private var readTimeoutMs: Long = 60_000L
        private var writeTimeoutMs: Long = 60_000L
        private var maxRetries: Int = 3
        private var retryDelayMs: Long = 1_000L
        private var modelCacheSizeBytes: Long = 100 * 1024 * 1024L
        private var enableGpuAcceleration: Boolean = true
        private var numThreads: Int = 4
        private var enableBackgroundSync: Boolean = true
        private var syncIntervalMinutes: Long = 60L
        private var enableHeartbeat: Boolean = true
        private var heartbeatIntervalSeconds: Long = 300L
        private var minBatteryLevel: Int = 20
        private var requireCharging: Boolean = false
        private var requireUnmeteredNetwork: Boolean = true
        private var enableEncryptedStorage: Boolean = true
        private var privacyConfiguration: PrivacyConfiguration = PrivacyConfiguration.DEFAULT
        private var enableSecureAggregation: Boolean = false
        private var certificatePins: List<String> = emptyList()
        private var pinnedHostname: String = ""

        fun serverUrl(url: String) = apply { this.serverUrl = url.trimEnd('/') }

        fun deviceAccessToken(token: String) = apply { this.deviceAccessToken = token }

        fun orgId(id: String) = apply { this.orgId = id }

        fun modelId(id: String) = apply { this.modelId = id }

        fun deviceId(id: String?) = apply { this.deviceId = id }

        fun appVersion(version: String?) = apply { this.appVersion = version }

        fun debugMode(enabled: Boolean) = apply { this.debugMode = enabled }

        fun connectionTimeoutMs(timeout: Long) = apply { this.connectionTimeoutMs = timeout }

        fun readTimeoutMs(timeout: Long) = apply { this.readTimeoutMs = timeout }

        fun writeTimeoutMs(timeout: Long) = apply { this.writeTimeoutMs = timeout }

        fun maxRetries(retries: Int) = apply { this.maxRetries = retries }

        fun retryDelayMs(delay: Long) = apply { this.retryDelayMs = delay }

        fun modelCacheSizeBytes(size: Long) = apply { this.modelCacheSizeBytes = size }

        fun enableGpuAcceleration(enabled: Boolean) = apply { this.enableGpuAcceleration = enabled }

        fun numThreads(threads: Int) = apply { this.numThreads = threads }

        fun enableBackgroundSync(enabled: Boolean) = apply { this.enableBackgroundSync = enabled }

        fun syncIntervalMinutes(minutes: Long) = apply { this.syncIntervalMinutes = minutes }

        fun enableHeartbeat(enabled: Boolean) = apply { this.enableHeartbeat = enabled }

        fun heartbeatIntervalSeconds(seconds: Long) = apply { this.heartbeatIntervalSeconds = seconds }

        fun minBatteryLevel(level: Int) = apply { this.minBatteryLevel = level }

        fun requireCharging(required: Boolean) = apply { this.requireCharging = required }

        fun requireUnmeteredNetwork(required: Boolean) = apply { this.requireUnmeteredNetwork = required }

        fun enableEncryptedStorage(enabled: Boolean) = apply { this.enableEncryptedStorage = enabled }

        fun privacyConfiguration(config: PrivacyConfiguration) = apply { this.privacyConfiguration = config }

        fun enableSecureAggregation(enabled: Boolean) = apply { this.enableSecureAggregation = enabled }

        fun certificatePins(pins: List<String>) = apply { this.certificatePins = pins }
        fun pinnedHostname(hostname: String) = apply { this.pinnedHostname = hostname }

        fun build(): EdgeMLConfig =
            EdgeMLConfig(
                serverUrl = serverUrl,
                deviceAccessToken = deviceAccessToken,
                orgId = orgId,
                modelId = modelId,
                deviceId = deviceId,
                appVersion = appVersion,
                debugMode = debugMode,
                connectionTimeoutMs = connectionTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                writeTimeoutMs = writeTimeoutMs,
                maxRetries = maxRetries,
                retryDelayMs = retryDelayMs,
                modelCacheSizeBytes = modelCacheSizeBytes,
                enableGpuAcceleration = enableGpuAcceleration,
                numThreads = numThreads,
                enableBackgroundSync = enableBackgroundSync,
                syncIntervalMinutes = syncIntervalMinutes,
                enableHeartbeat = enableHeartbeat,
                heartbeatIntervalSeconds = heartbeatIntervalSeconds,
                minBatteryLevel = minBatteryLevel,
                requireCharging = requireCharging,
                requireUnmeteredNetwork = requireUnmeteredNetwork,
                enableEncryptedStorage = enableEncryptedStorage,
                privacyConfiguration = privacyConfiguration,
                enableSecureAggregation = enableSecureAggregation,
                certificatePins = certificatePins,
                pinnedHostname = pinnedHostname,
            )
    }

    companion object {
        /** Default server URL for production */
        const val DEFAULT_SERVER_URL = "https://api.edgeml.ai"
    }
}
