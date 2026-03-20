package ai.octomil.config

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import kotlinx.serialization.Serializable

/**
 * Deployment environment for the Octomil server.
 *
 * Octomil supports three deployment modes:
 * - **Cloud (default)**: Managed Octomil service at `api.octomil.com`. No infrastructure to manage.
 * - **VPC**: Self-hosted Octomil server running inside your Virtual Private Cloud.
 *   Point `serverUrl` to your internal load balancer (e.g., `https://octomil.internal.mycompany.com`).
 * - **On-premises**: Air-gapped deployment on your own hardware.
 *   Point `serverUrl` to your local server (e.g., `https://octomil.local:8443`).
 *
 * For VPC and on-premises deployments, you may also need to configure:
 * - [OctomilConfig.certificatePins] for certificate pinning against your internal CA
 * - [OctomilConfig.pinnedHostname] for the hostname to pin against
 */
enum class ServerEnvironment {
    /** Managed Octomil cloud service (api.octomil.com). */
    CLOUD,

    /** Self-hosted Octomil server inside a Virtual Private Cloud. */
    VPC,

    /** Air-gapped on-premises Octomil server deployment. */
    ON_PREMISES,
}

/**
 * Configuration for the Octomil SDK.
 *
 * Use [OctomilConfig.Builder] to create a configuration instance:
 *
 * ## Cloud (simplest)
 * ```kotlin
 * val config = OctomilConfig.Builder()
 *     .auth(AuthConfig.OrgApiKey(
 *         apiKey = "edg_...",
 *         orgId = "your-org-id",
 *     ))
 *     .modelId("model-123")
 *     .build()
 * ```
 *
 * ## Device Token
 * ```kotlin
 * val config = OctomilConfig.Builder()
 *     .auth(AuthConfig.DeviceToken(
 *         deviceId = "dev_abc",
 *         bootstrapToken = token,
 *         serverUrl = "https://api.octomil.com",
 *     ))
 *     .modelId("model-123")
 *     .build()
 * ```
 *
 * ## VPC / On-premises
 * ```kotlin
 * val config = OctomilConfig.Builder()
 *     .auth(AuthConfig.OrgApiKey(
 *         apiKey = "edg_...",
 *         orgId = "your-org-id",
 *         serverUrl = "https://octomil.internal.mycompany.com",
 *     ))
 *     .serverEnvironment(ServerEnvironment.VPC)
 *     .modelId("model-123")
 *     .certificatePins(listOf("AABB..."))
 *     .pinnedHostname("octomil.internal.mycompany.com")
 *     .build()
 * ```
 */
@Serializable
data class OctomilConfig(
    /**
     * Authentication configuration.
     *
     * Use [AuthConfig.OrgApiKey] for API key authentication or
     * [AuthConfig.DeviceToken] for device token authentication.
     *
     * Server URL, org ID, and bearer token are derived from this config.
     */
    @kotlinx.serialization.Transient
    val auth: AuthConfig = AuthConfig.OrgApiKey(apiKey = "", orgId = "", serverUrl = DEFAULT_SERVER_URL),
    /** Model ID to use for server-connected training/inference. Null for manifest-only apps. */
    val modelId: String? = null,
    /**
     * Deployment environment. Defaults to [ServerEnvironment.CLOUD].
     *
     * Set to [ServerEnvironment.VPC] or [ServerEnvironment.ON_PREMISES] when running
     * against a self-hosted Octomil server. This affects default behaviors like
     * certificate validation and telemetry.
     */
    val serverEnvironment: ServerEnvironment = ServerEnvironment.CLOUD,
    /**
     * Client-side device identifier.
     *
     * When set, this value is:
     * - Used as the device identifier during registration (instead of auto-generating one).
     * - Included in OTLP telemetry resource attributes as `octomil.device_id`.
     * - Passed to the control plane for sync requests.
     *
     * When null, a device identifier is auto-generated on first initialization
     * and persisted to secure storage.
     */
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
     * compilation. No downside — cache is automatically invalidated when the
     * model changes.
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
     * // OctomilConfig
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
     * performance (big) cores.
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
     * When **false** (default), calling [ai.octomil.client.OctomilClient.train] on a model
     * without a TFLite "train" signature will throw
     * [ai.octomil.training.MissingTrainingSignatureException]. This is the safe default
     * because forward-pass-only training cannot update weights on-device.
     *
     * When **true**, forward-pass training is permitted. Loss and accuracy metrics will
     * be computed but weights will NOT be updated. The returned
     * [ai.octomil.training.TrainingOutcome.degraded] flag will be `true`.
     */
    val allowDegradedTraining: Boolean = false,
    /**
     * Pin specific engines per model ID. Overrides benchmark results.
     *
     * Key: model ID (e.g. `"whisper-tiny"`) or `"*"` for all models.
     * Value: engine wire value (e.g. `"llama_cpp"`, `"tflite"`).
     *
     * These overrides take precedence over persisted benchmark winners
     * but are overridden by server-side overrides from ControlSync.
     */
    val engineOverrides: Map<String, String> = emptyMap(),
) {
    /** Base URL of the Octomil server, derived from [auth]. */
    val serverUrl: String get() = auth.serverUrl

    /** Organization ID, derived from [auth]. */
    val orgId: String get() = auth.orgId

    /** Authentication token (API key or bootstrap token), derived from [auth]. */
    val deviceAccessToken: String get() = auth.token

    /** API key — alias for [deviceAccessToken]. */
    val apiKey: String get() = auth.token

    /** Device ID from auth config, if using device token auth. */
    val authDeviceId: String? get() = (auth as? AuthConfig.DeviceToken)?.deviceId

    init {
        if (auth.serverUrl.isBlank()) throw OctomilException(OctomilErrorCode.INVALID_INPUT, "serverUrl must not be blank")
        if (auth.token.isBlank()) throw OctomilException(OctomilErrorCode.INVALID_API_KEY, "apiKey / bootstrapToken must not be blank")
        if (auth is AuthConfig.OrgApiKey && auth.orgId.isBlank()) throw OctomilException(OctomilErrorCode.INVALID_INPUT, "orgId must not be blank")
        if (modelId != null && modelId.isBlank()) throw OctomilException(OctomilErrorCode.INVALID_INPUT, "modelId must not be blank when set")
        if (connectionTimeoutMs <= 0) throw OctomilException(OctomilErrorCode.INVALID_INPUT, "connectionTimeoutMs must be positive")
        if (readTimeoutMs <= 0) throw OctomilException(OctomilErrorCode.INVALID_INPUT, "readTimeoutMs must be positive")
        if (writeTimeoutMs <= 0) throw OctomilException(OctomilErrorCode.INVALID_INPUT, "writeTimeoutMs must be positive")
        if (maxRetries < 0) throw OctomilException(OctomilErrorCode.INVALID_INPUT, "maxRetries must be non-negative")
        if (retryDelayMs <= 0) throw OctomilException(OctomilErrorCode.INVALID_INPUT, "retryDelayMs must be positive")
        if (modelCacheSizeBytes <= 0) throw OctomilException(OctomilErrorCode.INVALID_INPUT, "modelCacheSizeBytes must be positive")
        if (numThreads <= 0) throw OctomilException(OctomilErrorCode.INVALID_INPUT, "numThreads must be positive")
        if (syncIntervalMinutes < 15) throw OctomilException(OctomilErrorCode.INVALID_INPUT, "syncIntervalMinutes must be at least 15")
        if (heartbeatIntervalSeconds < 60) throw OctomilException(OctomilErrorCode.INVALID_INPUT, "heartbeatIntervalSeconds must be at least 60")
        if (minBatteryLevel !in 0..100) throw OctomilException(OctomilErrorCode.INVALID_INPUT, "minBatteryLevel must be 0-100")
    }

    /**
     * Builder for [OctomilConfig].
     */
    class Builder {
        private var auth: AuthConfig? = null
        // Legacy flat-field accumulator — used by serverUrl/apiKey/deviceAccessToken/orgId convenience methods.
        private var _serverUrl: String? = null
        private var _token: String? = null
        private var _orgId: String? = null
        private var modelId: String? = null
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
        private var engineOverrides: Map<String, String> = emptyMap()

        /**
         * Set the authentication configuration.
         *
         * Use [AuthConfig.OrgApiKey] for API key authentication or
         * [AuthConfig.DeviceToken] for device token authentication.
         */
        fun auth(auth: AuthConfig) = apply { this.auth = auth }

        /** Convenience: set the server URL (builds an [AuthConfig.OrgApiKey] on [build]). */
        fun serverUrl(url: String) = apply { this._serverUrl = url }

        /** Convenience: set the API key (builds an [AuthConfig.OrgApiKey] on [build]). */
        fun apiKey(key: String) = apply { this._token = key }

        /** Convenience: alias for [apiKey]. */
        fun deviceAccessToken(token: String) = apply { this._token = token }

        /** Convenience: set the org ID (builds an [AuthConfig.OrgApiKey] on [build]). */
        fun orgId(id: String) = apply { this._orgId = id }

        fun modelId(id: String?) = apply { this.modelId = id }

        /**
         * Set the device identifier.
         *
         * When provided, this ID is used for device registration, OTLP telemetry
         * resource attributes (`octomil.device_id`), and control-plane sync.
         * Pass null (or omit) to let the SDK auto-generate one on first initialization.
         */
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
         * throws [ai.octomil.training.MissingTrainingSignatureException].
         * Set to true only if you understand that forward-pass training
         * cannot update weights on-device.
         */
        fun allowDegradedTraining(allowed: Boolean) = apply { this.allowDegradedTraining = allowed }

        /**
         * Pin specific engines per model ID. Overrides benchmark results.
         *
         * Key: model ID (e.g. `"whisper-tiny"`) or `"*"` for all models.
         * Value: engine wire value (e.g. `"llama_cpp"`, `"tflite"`).
         */
        fun engineOverrides(overrides: Map<String, String>) = apply { this.engineOverrides = overrides }

        fun build(): OctomilConfig {
            val resolvedAuth = auth
                ?: if (_token != null || _serverUrl != null || _orgId != null) {
                    AuthConfig.OrgApiKey(
                        apiKey = _token ?: "",
                        orgId = _orgId ?: "",
                        serverUrl = _serverUrl ?: DEFAULT_SERVER_URL,
                    )
                } else {
                    throw OctomilException(
                        OctomilErrorCode.INVALID_INPUT,
                        "auth must be set. Use auth(AuthConfig.OrgApiKey(...)) or auth(AuthConfig.DeviceToken(...))",
                    )
                }
            return OctomilConfig(
                auth = resolvedAuth,
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
                engineOverrides = engineOverrides,
            )
        }
    }

    companion object {
        /**
         * Default server URL for the managed Octomil cloud service.
         *
         * For VPC or on-premises deployments, replace this with your own server URL:
         * - VPC example: `https://octomil.internal.mycompany.com`
         * - On-prem example: `https://octomil.local:8443`
         */
        const val DEFAULT_SERVER_URL = "https://api.octomil.com"
    }
}
