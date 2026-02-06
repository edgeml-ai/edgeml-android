package ai.edgeml.client

import ai.edgeml.BuildConfig
import ai.edgeml.api.EdgeMLApi
import ai.edgeml.api.EdgeMLApiFactory
import ai.edgeml.api.dto.DeviceRegistrationRequest
import ai.edgeml.api.dto.DeviceGroup
import ai.edgeml.api.dto.GroupMembership
import ai.edgeml.api.dto.HeartbeatRequest
import ai.edgeml.config.EdgeMLConfig
import ai.edgeml.models.CachedModel
import ai.edgeml.models.DownloadState
import ai.edgeml.models.InferenceInput
import ai.edgeml.models.InferenceOutput
import ai.edgeml.models.ModelManager
import ai.edgeml.storage.SecureStorage
import ai.edgeml.sync.EventQueue
import ai.edgeml.sync.EventTypes
import ai.edgeml.sync.WorkManagerSync
import ai.edgeml.training.TFLiteTrainer
import ai.edgeml.utils.BatteryUtils
import ai.edgeml.utils.DeviceUtils
import ai.edgeml.utils.NetworkUtils
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Main entry point for the EdgeML Android SDK.
 *
 * Provides a simple, unified API for federated learning operations:
 * - Device registration
 * - Model download and caching
 * - TensorFlow Lite inference
 * - Background sync via WorkManager
 * - Secure storage for credentials
 *
 * ## Quick Start
 *
 * ```kotlin
 * // Initialize the client
 * val config = EdgeMLConfig.Builder()
 *     .serverUrl("https://api.edgeml.ai")
 *     .apiKey("your-api-key")
 *     .orgId("your-org-id")
 *     .modelId("your-model-id")
 *     .build()
 *
 * val client = EdgeMLClient.Builder(context)
 *     .config(config)
 *     .build()
 *
 * // Initialize (registers device if needed)
 * client.initialize()
 *
 * // Run inference
 * val result = client.runInference(inputData)
 * ```
 */
class EdgeMLClient private constructor(
    private val context: Context,
    private val config: EdgeMLConfig,
) {
    // Internal components
    private val api: EdgeMLApi = EdgeMLApiFactory.create(config)
    private val storage: SecureStorage = SecureStorage.getInstance(context, config.enableEncryptedStorage)
    private val modelManager: ModelManager = ModelManager(context, config, api, storage)
    private val trainer: TFLiteTrainer = TFLiteTrainer(context, config)
    private val syncManager: WorkManagerSync = WorkManagerSync(context, config)
    private val eventQueue: EventQueue = EventQueue.getInstance(context)

    // Coroutine scope for background operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Heartbeat management
    private var heartbeatJob: Job? = null
    private val heartbeatIntervalMs: Long = config.heartbeatIntervalSeconds * 1000L

    // State
    private val _state = MutableStateFlow(ClientState.UNINITIALIZED)
    val state: Flow<ClientState> = _state.asStateFlow()

    // Server-assigned device UUID
    private val _serverDeviceId = MutableStateFlow<String?>(null)
    val serverDeviceId: Flow<String?> = _serverDeviceId.asStateFlow()

    // Client-generated device identifier
    private val _deviceIdentifier = MutableStateFlow<String?>(null)
    val deviceIdentifier: Flow<String?> = _deviceIdentifier.asStateFlow()

    // Cached group memberships
    private val _groupMemberships = MutableStateFlow<List<GroupMembership>>(emptyList())
    val groupMemberships: Flow<List<GroupMembership>> = _groupMemberships.asStateFlow()

    companion object {
        /** SDK version */
        val VERSION: String = BuildConfig.EDGEML_VERSION

        @Volatile
        private var instance: EdgeMLClient? = null

        /**
         * Get the singleton instance.
         *
         * @throws IllegalStateException if not initialized
         */
        fun getInstance(): EdgeMLClient {
            return instance
                ?: throw IllegalStateException("EdgeMLClient not initialized. Call Builder.build() first.")
        }

        /**
         * Check if the client is initialized.
         */
        fun isInitialized(): Boolean = instance != null
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    /**
     * Initialize the SDK.
     *
     * This should be called once at app startup. It:
     * 1. Registers the device if not already registered
     * 2. Downloads the latest model if needed
     * 3. Sets up background sync and heartbeat
     *
     * @return Result indicating success or failure
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _state.value = ClientState.INITIALIZING
            Timber.i("Initializing EdgeML SDK v$VERSION")

            // Generate or retrieve device identifier
            var deviceIdentifier = storage.getClientDeviceIdentifier()
            if (deviceIdentifier == null) {
                deviceIdentifier = config.deviceId ?: DeviceUtils.generateDeviceIdentifier(context)
                storage.setClientDeviceIdentifier(deviceIdentifier)
            }
            _deviceIdentifier.value = deviceIdentifier

            // Check if device is already registered (has server ID)
            var serverId = storage.getServerDeviceId()
            val isRegistered = serverId != null

            if (!isRegistered) {
                // Register device
                val registrationResult = registerDevice(deviceIdentifier)
                if (registrationResult.isFailure) {
                    _state.value = ClientState.ERROR
                    return@withContext Result.failure(
                        registrationResult.exceptionOrNull()
                            ?: Exception("Device registration failed")
                    )
                }
                serverId = storage.getServerDeviceId()
            }

            _serverDeviceId.value = serverId

            // Fetch group memberships
            if (serverId != null) {
                fetchGroupMemberships(serverId)
            }

            // Download/verify model
            val modelResult = modelManager.ensureModelAvailable()
            if (modelResult.isFailure) {
                // Model download failed, but we can continue with cached model
                Timber.w("Model download failed, checking for cached model")
                val cached = modelManager.getCachedModel()
                if (cached == null) {
                    _state.value = ClientState.ERROR
                    return@withContext Result.failure(
                        modelResult.exceptionOrNull()
                            ?: Exception("No model available")
                    )
                }
            }

            // Load model into trainer
            val cachedModel = modelManager.getCachedModel()
            if (cachedModel != null) {
                trainer.loadModel(cachedModel)
            }

            // Setup background sync
            if (config.enableBackgroundSync) {
                syncManager.schedulePeriodicSync()
            }

            // Start heartbeat
            if (config.enableHeartbeat && serverId != null) {
                startHeartbeat(serverId)
            }

            _state.value = ClientState.READY
            Timber.i("EdgeML SDK initialized successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "EdgeML initialization failed")
            _state.value = ClientState.ERROR
            Result.failure(e)
        }
    }

    /**
     * Register the device with the EdgeML server.
     */
    private suspend fun registerDevice(deviceIdentifier: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Registering device: $deviceIdentifier")

            val request = DeviceRegistrationRequest(
                deviceIdentifier = deviceIdentifier,
                orgId = config.orgId,
                platform = "android",
                osVersion = DeviceUtils.getOsVersion(),
                sdkVersion = VERSION,
                manufacturer = DeviceUtils.getManufacturer(),
                model = DeviceUtils.getModel(),
                locale = DeviceUtils.getLocale(),
                region = DeviceUtils.getRegion(),
                appVersion = config.appVersion,
                capabilities = DeviceUtils.getDeviceCapabilities(context),
            )

            val response = api.registerDevice(request)

            if (response.isSuccessful) {
                val body = response.body()

                if (body != null) {
                    // Store server-assigned device ID
                    storage.setServerDeviceId(body.id)
                    storage.setClientDeviceIdentifier(deviceIdentifier)
                    body.apiToken?.let { storage.setApiToken(it) }

                    // Fetch and cache device policy from server
                    try {
                        val policyResponse = api.getDevicePolicy(config.orgId)
                        if (policyResponse.isSuccessful) {
                            policyResponse.body()?.let { policy ->
                                storage.setDevicePolicy(policy)
                                Timber.i("Device policy fetched: battery=${policy.batteryThreshold}%, network=${policy.networkPolicy}")
                            }
                        } else {
                            Timber.w("Failed to fetch device policy: ${policyResponse.code()}, using defaults")
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Error fetching device policy, using defaults")
                    }

                    // Queue registration event
                    eventQueue.addTrainingEvent(
                        type = EventTypes.DEVICE_REGISTERED,
                        metadata = mapOf(
                            "device_id" to body.id,
                            "device_identifier" to deviceIdentifier
                        )
                    )

                    Timber.i("Device registered successfully: ${body.id}")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Empty registration response"))
                }
            } else {
                val errorMsg = "Device registration failed: ${response.code()}"
                Timber.e(errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Timber.e(e, "Device registration error")
            Result.failure(e)
        }
    }

    // =========================================================================
    // Heartbeat
    // =========================================================================

    /**
     * Start the heartbeat loop.
     */
    private fun startHeartbeat(deviceId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            Timber.d("Starting heartbeat with interval ${heartbeatIntervalMs}ms")
            while (isActive) {
                sendHeartbeat(deviceId)
                delay(heartbeatIntervalMs)
            }
        }
    }

    /**
     * Stop the heartbeat loop.
     */
    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        Timber.d("Heartbeat stopped")
    }

    /**
     * Send a single heartbeat to the server.
     */
    private suspend fun sendHeartbeat(deviceId: String) {
        try {
            val request = HeartbeatRequest(
                sdkVersion = VERSION,
                osVersion = DeviceUtils.getOsVersion(),
                appVersion = config.appVersion,
                batteryLevel = BatteryUtils.getBatteryLevel(context),
                isCharging = BatteryUtils.isCharging(context),
                availableStorageMb = DeviceUtils.getAvailableStorageMb(),
                availableMemoryMb = DeviceUtils.getAvailableMemoryMb(context),
                networkType = if (NetworkUtils.isWifiConnected(context)) "wifi" else "cellular",
            )

            val response = api.sendHeartbeat(deviceId, request)

            if (response.isSuccessful) {
                Timber.v("Heartbeat sent successfully")
            } else {
                Timber.w("Heartbeat failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Heartbeat error")
        }
    }

    /**
     * Manually trigger a heartbeat.
     */
    suspend fun triggerHeartbeat(): Result<Unit> = withContext(Dispatchers.IO) {
        val deviceId = _serverDeviceId.value
        if (deviceId == null) {
            return@withContext Result.failure(Exception("Device not registered"))
        }
        sendHeartbeat(deviceId)
        Result.success(Unit)
    }

    // =========================================================================
    // Device Groups
    // =========================================================================

    /**
     * Fetch group memberships for the device.
     */
    private suspend fun fetchGroupMemberships(deviceId: String) {
        try {
            val response = api.getDeviceGroups(deviceId)
            if (response.isSuccessful) {
                val body = response.body()
                _groupMemberships.value = body?.memberships ?: emptyList()
                Timber.d("Fetched ${body?.count ?: 0} group memberships")
            } else {
                Timber.w("Failed to fetch group memberships: ${response.code()}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Error fetching group memberships")
        }
    }

    /**
     * Refresh group memberships from the server.
     */
    suspend fun refreshGroupMemberships(): Result<List<GroupMembership>> = withContext(Dispatchers.IO) {
        val deviceId = _serverDeviceId.value
        if (deviceId == null) {
            return@withContext Result.failure(Exception("Device not registered"))
        }

        try {
            val response = api.getDeviceGroups(deviceId)
            if (response.isSuccessful) {
                val memberships = response.body()?.memberships ?: emptyList()
                _groupMemberships.value = memberships
                Result.success(memberships)
            } else {
                Result.failure(Exception("Failed to fetch groups: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if the device belongs to a specific group.
     */
    fun isInGroup(groupId: String): Boolean {
        return _groupMemberships.value.any { it.groupId == groupId }
    }

    /**
     * Check if the device belongs to a group with a specific name.
     */
    fun isInGroupNamed(groupName: String): Boolean {
        return _groupMemberships.value.any { it.groupName == groupName }
    }

    // =========================================================================
    // Inference
    // =========================================================================

    /**
     * Run inference on input data.
     *
     * @param input Input data as float array
     * @return Inference output with results and timing
     */
    suspend fun runInference(input: FloatArray): Result<InferenceOutput> {
        checkReady()

        return trainer.runInference(input).also { result ->
            result.onSuccess { output ->
                eventQueue.addTrainingEvent(
                    type = EventTypes.INFERENCE_COMPLETED,
                    metrics = mapOf(
                        "inference_time_ms" to output.inferenceTimeMs.toDouble()
                    )
                )
            }.onFailure {
                eventQueue.addTrainingEvent(
                    type = EventTypes.INFERENCE_FAILED,
                    metadata = mapOf("error" to (it.message ?: "unknown"))
                )
            }
        }
    }

    /**
     * Run inference with structured input.
     *
     * @param input Inference input with data and shape
     * @return Inference output with results and timing
     */
    suspend fun runInference(input: InferenceInput): Result<InferenceOutput> {
        return runInference(input.data)
    }

    /**
     * Classify input and get top predictions.
     *
     * @param input Input data as float array
     * @param topK Number of top predictions to return
     * @return List of (class index, confidence) pairs
     */
    suspend fun classify(input: FloatArray, topK: Int = 5): Result<List<Pair<Int, Float>>> {
        checkReady()
        return trainer.classify(input, topK)
    }

    // =========================================================================
    // Model Management
    // =========================================================================

    /**
     * Get the model download state flow.
     */
    val modelDownloadState: Flow<DownloadState>
        get() = modelManager.downloadState

    /**
     * Force download the latest model.
     *
     * @return The downloaded model
     */
    suspend fun updateModel(): Result<CachedModel> {
        return modelManager.ensureModelAvailable(forceDownload = true).also { result ->
            result.onSuccess { model ->
                trainer.loadModel(model)
            }
        }
    }

    /**
     * Get the currently loaded model.
     */
    fun getCurrentModel(): CachedModel? = trainer.getCurrentModel()

    /**
     * Get information about the loaded model.
     */
    fun getModelInfo(): ModelInfo? {
        val model = trainer.getCurrentModel() ?: return null
        val tensorInfo = trainer.getTensorInfo()

        return ModelInfo(
            modelId = model.modelId,
            version = model.version,
            format = model.format,
            sizeBytes = model.sizeBytes,
            inputShape = tensorInfo?.inputShape ?: intArrayOf(),
            outputShape = tensorInfo?.outputShape ?: intArrayOf(),
            usingGpu = trainer.isUsingGpu(),
        )
    }

    // =========================================================================
    // Sync Management
    // =========================================================================

    /**
     * Trigger immediate sync.
     */
    fun triggerSync() {
        syncManager.triggerImmediateSync()
    }

    /**
     * Cancel all background sync.
     */
    fun cancelSync() {
        syncManager.cancelAllSync()
    }

    // =========================================================================
    // Event Tracking
    // =========================================================================

    /**
     * Track a custom event.
     *
     * @param eventType Event type identifier
     * @param metrics Numeric metrics
     * @param metadata String metadata
     */
    suspend fun trackEvent(
        eventType: String,
        metrics: Map<String, Double>? = null,
        metadata: Map<String, String>? = null,
    ) {
        eventQueue.addTrainingEvent(
            type = eventType,
            metrics = metrics,
            metadata = metadata,
        )
    }

    // =========================================================================
    // Resource Management
    // =========================================================================

    /**
     * Close the client and release resources.
     */
    suspend fun close() {
        stopHeartbeat()
        trainer.close()
        syncManager.cancelAllSync()
        _state.value = ClientState.CLOSED
        instance = null
        Timber.i("EdgeML client closed")
    }

    /**
     * Check if client is ready for operations.
     */
    private fun checkReady() {
        val currentState = _state.value
        require(currentState == ClientState.READY) {
            "EdgeML client is not ready. Current state: $currentState. " +
                "Call initialize() first and wait for it to complete."
        }
    }

    // =========================================================================
    // Builder
    // =========================================================================

    /**
     * Builder for creating EdgeMLClient instances.
     */
    class Builder(private val context: Context) {
        private var config: EdgeMLConfig? = null

        /**
         * Set the configuration.
         */
        fun config(config: EdgeMLConfig) = apply {
            this.config = config
        }

        /**
         * Build and return the EdgeMLClient instance.
         */
        fun build(): EdgeMLClient {
            val cfg = config
                ?: throw IllegalStateException("Configuration is required. Call config() first.")

            // Setup logging in debug mode
            if (cfg.debugMode && Timber.treeCount == 0) {
                Timber.plant(Timber.DebugTree())
            }

            val client = EdgeMLClient(context.applicationContext, cfg)
            instance = client
            return client
        }
    }
}

/**
 * Client state enum.
 */
enum class ClientState {
    /** Client created but not initialized */
    UNINITIALIZED,

    /** Initialization in progress */
    INITIALIZING,

    /** Ready for operations */
    READY,

    /** Error state */
    ERROR,

    /** Client closed */
    CLOSED,
}

/**
 * Model information.
 */
data class ModelInfo(
    val modelId: String,
    val version: String,
    val format: String,
    val sizeBytes: Long,
    val inputShape: IntArray,
    val outputShape: IntArray,
    val usingGpu: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ModelInfo

        if (modelId != other.modelId) return false
        if (version != other.version) return false
        if (format != other.format) return false
        if (sizeBytes != other.sizeBytes) return false
        if (!inputShape.contentEquals(other.inputShape)) return false
        if (!outputShape.contentEquals(other.outputShape)) return false
        if (usingGpu != other.usingGpu) return false

        return true
    }

    override fun hashCode(): Int {
        var result = modelId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + inputShape.contentHashCode()
        result = 31 * result + outputShape.contentHashCode()
        result = 31 * result + usingGpu.hashCode()
        return result
    }
}
