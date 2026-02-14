package ai.edgeml.config

import ai.edgeml.runtime.ThermalPolicy
import kotlinx.serialization.Serializable

/**
 * Deployment environment for the EdgeML server.
 *
 * EdgeML supports three deployment modes:
 * - **Cloud (default)**: Managed EdgeML service at `api.edgeml.ai`. No infrastructure to manage.
 * - **VPC**: Self-hosted EdgeML server running inside your Virtual Private Cloud.
 *   Point `serverUrl` to your internal load balancer (e.g., `https://edgeml.internal.mycompany.com`).
 * - **On-premises**: Air-gapped deployment on your own hardware.
 *   Point `serverUrl` to your local server (e.g., `https://edgeml.local:8443`).
 *
 * For VPC and on-premises deployments, you may also need to configure:
 * - [EdgeMLConfig.certificatePins] for certificate pinning against your internal CA
 * - [EdgeMLConfig.pinnedHostname] for the hostname to pin against
 */
enum class ServerEnvironment {
    /** Managed EdgeML cloud service (api.edgeml.ai). */
    CLOUD,

    /** Self-hosted EdgeML server inside a Virtual Private Cloud. */
    VPC,

    /** Air-gapped on-premises EdgeML server deployment. */
    ON_PREMISES,
}

/**
 * Configuration for the EdgeML SDK.
 *
 * Use [EdgeMLConfig.Builder] to create a configuration instance:
 *
 * ## Cloud (simplest)
 * ```kotlin
 * val config = EdgeMLConfig.Builder()
 *     .serverUrl("https://api.edgeml.ai")
 *     .deviceAccessToken("<short-lived-device-token>")
 *     .orgId("your-org-id")
 *     .modelId("model-123")
 *     .build()
 * ```
 *
 * ## VPC / On-premises
 * ```kotlin
 * val config = EdgeMLConfig.Builder()
 *     .serverUrl("https://edgeml.internal.mycompany.com")
 *     .serverEnvironment(ServerEnvironment.VPC)
 *     .deviceAccessToken(token)
 *     .orgId("your-org-id")
 *     .modelId("model-123")
 *     .certificatePins(listOf("AABB..."))        // pin your internal CA
 *     .pinnedHostname("edgeml.internal.mycompany.com")
 *     .build()
 * ```
 */
@Serializable
data class EdgeMLConfig(
    /**
     * Base URL of the EdgeML server.
     *
     * This is the HTTP(S) endpoint where the EdgeML coordination server is running.
     * - **Cloud**: `https://api.edgeml.ai` (managed service)
     * - **VPC**: Your internal load balancer URL (e.g., `https://edgeml.internal.company.com`)
     * - **On-premises**: Your local server URL (e.g., `https://edgeml.local:8443`)
     *
     * The server handles device registration, model distribution, round coordination,
     * gradient aggregation, and secure aggregation key exchange.
     */
    val serverUrl: String,
    /** Short-lived device access token for authentication */
    val deviceAccessToken: String,
    /** Organization ID */
    val orgId: String,
    /** Model ID to use for training/inference */
    val modelId: String,
    /**
     * Deployment environment. Defaults to [ServerEnvironment.CLOUD].
     *
     * Set to [ServerEnvironment.VPC] or [ServerEnvironment.ON_PREMISES] when running
     * against a self-hosted EdgeML server. This affects default behaviors like
     * certificate validation and telemetry.
     */
    val serverEnvironment: ServerEnvironment = ServerEnvironment.CLOUD,
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
    /** Enable TensorFlow Lite GPU delegate. */
    val enableGpuAcceleration: Boolean = true,
    /**
     * Cache compiled GPU shaders to disk so subsequent model loads skip shader
     * compilation (saves 100-500ms cold-start). No downside — cache is
     * automatically invalidated when the model changes.
     */
    val enableGpuSerialization: Boolean = true,
    /**
     * Allow float16 precision on the GPU delegate (~2x throughput).
     * Safe for most vision/classification models. May reduce accuracy for
     * models with large dynamic ranges. Only takes effect when GPU is active.
     */
    val enableFloat16Inference: Boolean = false,
    /**
     * Enable NNAPI delegate for hardware acceleration on Android 8.1–14.
     *
     * NNAPI is **deprecated in Android 15** (API 35). On Android 15+ devices
     * this flag is ignored and vendor NPU delegates are used instead.
     * Default off because NNAPI can be unpredictable across vendors.
     */
    val enableNnapi: Boolean = false,
    /**
     * Use vendor NPU delegates (Qualcomm QNN, Samsung Eden, MediaTek
     * NeuroPilot) when the vendor AAR is on the classpath. Default off —
     * enable this when you add a vendor delegate AAR to your app:
     *
     * ```kotlin
     * // build.gradle.kts
     * implementation("com.qualcomm.qti:qnn-tflite-delegate:2.+")
     *
     * // EdgeMLConfig
     * .enableVendorNpu(true)
     * ```
     *
     * Vendor delegate AARs:
     * - Qualcomm: `com.qualcomm.qti:qnn-tflite-delegate`
     * - Samsung: `com.samsung.android:eden-tflite-delegate`
     * - MediaTek: `com.mediatek.neuropilot:tflite-neuron-delegate`
     */
    val enableVendorNpu: Boolean = false,
    /**
     * Detect ARM big.LITTLE core topology and pin TFLite threads to
     * performance (big) cores. Inference on little cores can be 3-5x slower.
     * When true, [numThreads] is overridden with the detected big core count.
     */
    val preferBigCores: Boolean = true,
    /**
     * Number of TFLite interpreter threads. When [preferBigCores] is true,
     * this is overridden with the detected performance core count.
     */
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
    /**
     * Allow training to proceed in degraded mode when the model lacks training signatures.
     *
     * When **false** (default), calling [ai.edgeml.client.EdgeMLClient.train] on a model
     * without a TFLite "train" signature will throw
     * [ai.edgeml.training.MissingTrainingSignatureException]. This is the safe default
     * because forward-pass-only training cannot update weights on-device.
     *
     * When **true**, forward-pass training is permitted. Loss and accuracy metrics will
     * be computed but weights will NOT be updated. The returned
     * [ai.edgeml.training.TrainingOutcome.degraded] flag will be `true`.
     */
    val allowDegradedTraining: Boolean = false,
    /**
     * Thermal management policy for on-device training.
     *
     * Controls how the SDK responds to device overheating during training:
     * - [ThermalPolicy.ADAPTIVE] (default): Dynamically reduce batch size and insert
     *   cooldown delays based on thermal state. Aborts only at CRITICAL.
     * - [ThermalPolicy.AGGRESSIVE]: Abort training early at SERIOUS or higher.
     * - [ThermalPolicy.IGNORE]: No thermal management (not recommended for production).
     */
    val thermalPolicy: ThermalPolicy = ThermalPolicy.ADAPTIVE,
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
        private var serverEnvironment: ServerEnvironment = ServerEnvironment.CLOUD
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
        private var enableGpuSerialization: Boolean = true
        private var enableFloat16Inference: Boolean = false
        private var enableNnapi: Boolean = false
        private var enableVendorNpu: Boolean = false
        private var preferBigCores: Boolean = true
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
        private var allowDegradedTraining: Boolean = false
        private var thermalPolicy: ThermalPolicy = ThermalPolicy.ADAPTIVE

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

        fun enableGpuSerialization(enabled: Boolean) = apply { this.enableGpuSerialization = enabled }

        fun enableFloat16Inference(enabled: Boolean) = apply { this.enableFloat16Inference = enabled }

        fun enableNnapi(enabled: Boolean) = apply { this.enableNnapi = enabled }

        fun enableVendorNpu(enabled: Boolean) = apply { this.enableVendorNpu = enabled }

        fun preferBigCores(enabled: Boolean) = apply { this.preferBigCores = enabled }

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

        /**
         * Set the deployment environment.
         *
         * @see ServerEnvironment
         */
        fun serverEnvironment(env: ServerEnvironment) = apply { this.serverEnvironment = env }

        /**
         * Allow training to proceed without model training signatures.
         *
         * When false (default), training on a model without a "train" signature
         * throws [ai.edgeml.training.MissingTrainingSignatureException].
         * Set to true only if you understand that forward-pass training
         * cannot update weights on-device.
         */
        fun allowDegradedTraining(allowed: Boolean) = apply { this.allowDegradedTraining = allowed }

        /**
         * Set the thermal management policy for training.
         *
         * @see ThermalPolicy
         */
        fun thermalPolicy(policy: ThermalPolicy) = apply { this.thermalPolicy = policy }

        fun build(): EdgeMLConfig =
            EdgeMLConfig(
                serverUrl = serverUrl,
                deviceAccessToken = deviceAccessToken,
                orgId = orgId,
                modelId = modelId,
                serverEnvironment = serverEnvironment,
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
                enableGpuSerialization = enableGpuSerialization,
                enableFloat16Inference = enableFloat16Inference,
                enableNnapi = enableNnapi,
                enableVendorNpu = enableVendorNpu,
                preferBigCores = preferBigCores,
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
                allowDegradedTraining = allowDegradedTraining,
                thermalPolicy = thermalPolicy,
            )
    }

    companion object {
        /**
         * Default server URL for the managed EdgeML cloud service.
         *
         * For VPC or on-premises deployments, replace this with your own server URL:
         * - VPC example: `https://edgeml.internal.mycompany.com`
         * - On-prem example: `https://edgeml.local:8443`
         */
        const val DEFAULT_SERVER_URL = "https://api.edgeml.ai"
    }
}
