package ai.edgeml.client

import ai.edgeml.BuildConfig
import ai.edgeml.api.EdgeMLApi
import ai.edgeml.api.EdgeMLApiFactory
import ai.edgeml.api.dto.DeviceRegistrationRequest
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
import kotlinx.coroutines.CoroutineDispatcher
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
 *     .deviceAccessToken("<short-lived-device-token>")
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    // Internal components
    private val api: EdgeMLApi = EdgeMLApiFactory.create(config)
    private val storage: SecureStorage = SecureStorage.getInstance(context, config.enableEncryptedStorage)
    private val modelManager: ModelManager = ModelManager(context, config, api, storage)
    private val trainer: TFLiteTrainer = TFLiteTrainer(context, config)
    private val syncManager: WorkManagerSync = WorkManagerSync(context, config)
    private val eventQueue: EventQueue = EventQueue.getInstance(context)

    // Coroutine scope for background operations
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

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
    suspend fun initialize(): Result<Unit> = withContext(ioDispatcher) {
        try {
            _state.value = ClientState.INITIALIZING
            Timber.i("Initializing EdgeML SDK v$VERSION")

            val deviceIdentifier = resolveDeviceIdentifier()
            _deviceIdentifier.value = deviceIdentifier

            val serverId = ensureDeviceRegistered(deviceIdentifier)
            _serverDeviceId.value = serverId

            if (serverId != null) {
                fetchGroupMemberships(serverId)
            }

            ensureModelLoaded()
            setupBackgroundServices(serverId)

            _state.value = ClientState.READY
            Timber.i("EdgeML SDK initialized successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "EdgeML initialization failed")
            _state.value = ClientState.ERROR
            Result.failure(e)
        }
    }

    private suspend fun resolveDeviceIdentifier(): String {
        val existing = storage.getClientDeviceIdentifier()
        if (existing != null) return existing
        val identifier = config.deviceId ?: DeviceUtils.generateDeviceIdentifier(context)
        storage.setClientDeviceIdentifier(identifier)
        return identifier
    }

    private suspend fun ensureDeviceRegistered(deviceIdentifier: String): String? {
        var serverId = storage.getServerDeviceId()
        if (serverId == null) {
            val result = registerDevice(deviceIdentifier)
            if (result.isFailure) {
                _state.value = ClientState.ERROR
                throw result.exceptionOrNull() ?: Exception("Device registration failed")
            }
            serverId = storage.getServerDeviceId()
        }
        return serverId
    }

    private suspend fun ensureModelLoaded() {
        val modelResult = modelManager.ensureModelAvailable()
        if (modelResult.isFailure) {
            Timber.w("Model download failed, checking for cached model")
            if (modelManager.getCachedModel() == null) {
                _state.value = ClientState.ERROR
                throw modelResult.exceptionOrNull() ?: Exception("No model available")
            }
        }
        modelManager.getCachedModel()?.let { trainer.loadModel(it) }
    }

    private fun setupBackgroundServices(serverId: String?) {
        if (config.enableBackgroundSync) {
            syncManager.schedulePeriodicSync()
        }
        if (config.enableHeartbeat && serverId != null) {
            startHeartbeat(serverId)
        }
    }

    /**
     * Register the device with the EdgeML server.
     */
    private suspend fun registerDevice(deviceIdentifier: String): Result<Unit> = withContext(ioDispatcher) {
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

            if (!response.isSuccessful) {
                val errorMsg = "Device registration failed: ${response.code()}"
                Timber.e(errorMsg)
                return@withContext Result.failure(Exception(errorMsg))
            }

            val body = response.body()
                ?: return@withContext Result.failure(Exception("Empty registration response"))

            storage.setServerDeviceId(body.id)
            storage.setClientDeviceIdentifier(deviceIdentifier)
            body.apiToken?.let { storage.setApiToken(it) }

            fetchAndCacheDevicePolicy()

            eventQueue.addTrainingEvent(
                type = EventTypes.DEVICE_REGISTERED,
                metadata = mapOf(
                    "device_id" to body.id,
                    "device_identifier" to deviceIdentifier
                )
            )

            Timber.i("Device registered successfully: ${body.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Device registration error")
            Result.failure(e)
        }
    }

    private suspend fun fetchAndCacheDevicePolicy() {
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
    }

    // =========================================================================
    // Heartbeat
    // =========================================================================

    /**
     * Start the heartbeat loop.
     */
    private fun startHeartbeat(deviceId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(ioDispatcher) {
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
    suspend fun triggerHeartbeat(): Result<Unit> = withContext(ioDispatcher) {
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
    suspend fun refreshGroupMemberships(): Result<List<GroupMembership>> = withContext(ioDispatcher) {
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
    // Streaming Inference
    // =========================================================================

    /**
     * Stream generative inference with automatic timing instrumentation.
     *
     * Each emitted [ai.edgeml.inference.InferenceChunk] carries per-chunk
     * latency. On stream completion a [ai.edgeml.inference.StreamingInferenceResult]
     * is automatically reported to the server.
     *
     * @param input Modality-specific input (e.g. prompt string for text).
     * @param modality Output modality.
     * @param engine Optional custom engine; defaults to modality-appropriate engine.
     * @return A [Flow] of [ai.edgeml.inference.InferenceChunk].
     */
    fun generateStream(
        input: Any,
        modality: ai.edgeml.inference.Modality,
        engine: ai.edgeml.inference.StreamingInferenceEngine? = null,
        modelId: String? = null,
        version: String? = null,
    ): Flow<ai.edgeml.inference.InferenceChunk> {
        checkReady()

        val resolvedEngine = engine ?: when (modality) {
            ai.edgeml.inference.Modality.TEXT -> ai.edgeml.inference.LLMEngine(context)
            ai.edgeml.inference.Modality.IMAGE -> ai.edgeml.inference.ImageEngine(context)
            ai.edgeml.inference.Modality.AUDIO -> ai.edgeml.inference.AudioEngine(context)
            ai.edgeml.inference.Modality.VIDEO -> ai.edgeml.inference.VideoEngine(context)
        }

        val sessionId = java.util.UUID.randomUUID().toString()
        val deviceId = _serverDeviceId.value
        val resolvedModelId = modelId ?: config.modelId
        val resolvedVersion = version ?: "latest"

        return kotlinx.coroutines.flow.flow {
            val sessionStart = System.currentTimeMillis()
            var previousTime = sessionStart
            var firstChunkTime: Long? = null
            val latencies = mutableListOf<Double>()
            var chunkCount = 0

            // Report generation_started
            if (deviceId != null) {
                try {
                    api.reportInferenceEvent(
                        ai.edgeml.api.dto.InferenceEventRequest(
                            deviceId = deviceId,
                            modelId = resolvedModelId,
                            version = resolvedVersion,
                            modality = modality.value,
                            sessionId = sessionId,
                            eventType = "generation_started",
                            timestampMs = sessionStart,
                            orgId = config.orgId,
                        )
                    )
                } catch (_: Exception) { }
            }

            eventQueue.addTrainingEvent(
                type = ai.edgeml.sync.EventTypes.GENERATION_STARTED,
                metadata = mapOf("session_id" to sessionId, "modality" to modality.value),
            )

            try {
                resolvedEngine.generate(input, modality).collect { rawChunk ->
                    val now = System.currentTimeMillis()
                    if (firstChunkTime == null) firstChunkTime = now

                    val latencyMs = (now - previousTime).toDouble()
                    previousTime = now
                    latencies.add(latencyMs)
                    chunkCount++

                    emit(
                        ai.edgeml.inference.InferenceChunk(
                            index = chunkCount - 1,
                            data = rawChunk.data,
                            modality = modality,
                            timestamp = now,
                            latencyMs = latencyMs,
                        )
                    )
                }
            } catch (e: Exception) {
                eventQueue.addTrainingEvent(
                    type = ai.edgeml.sync.EventTypes.GENERATION_FAILED,
                    metadata = mapOf("session_id" to sessionId, "error" to (e.message ?: "unknown")),
                )
                throw e
            }

            // Compute and report result
            val sessionEnd = System.currentTimeMillis()
            val totalDurationMs = (sessionEnd - sessionStart).toDouble()
            val ttfcMs = ((firstChunkTime ?: sessionEnd) - sessionStart).toDouble()
            val avgLatency = if (latencies.isEmpty()) 0.0 else latencies.sum() / latencies.size
            val throughput = if (totalDurationMs > 0) chunkCount.toDouble() / (totalDurationMs / 1000) else 0.0

            eventQueue.addTrainingEvent(
                type = ai.edgeml.sync.EventTypes.GENERATION_COMPLETED,
                metrics = mapOf(
                    "ttfc_ms" to ttfcMs,
                    "avg_chunk_latency_ms" to avgLatency,
                    "total_chunks" to chunkCount.toDouble(),
                    "total_duration_ms" to totalDurationMs,
                    "throughput" to throughput,
                ),
                metadata = mapOf("session_id" to sessionId),
            )

            // Report to server
            if (deviceId != null) {
                try {
                    api.reportInferenceEvent(
                        ai.edgeml.api.dto.InferenceEventRequest(
                            deviceId = deviceId,
                            modelId = resolvedModelId,
                            version = resolvedVersion,
                            modality = modality.value,
                            sessionId = sessionId,
                            eventType = "generation_completed",
                            timestampMs = sessionEnd,
                            metrics = ai.edgeml.api.dto.InferenceEventMetrics(
                                ttfcMs = ttfcMs,
                                totalChunks = chunkCount,
                                totalDurationMs = totalDurationMs,
                                throughput = throughput,
                            ),
                            orgId = config.orgId,
                        )
                    )
                } catch (_: Exception) { }
            }
        }
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
    fun getCurrentModel(): CachedModel? = trainer.currentModel

    /**
     * Get information about the loaded model.
     */
    fun getModelInfo(): ModelInfo? {
        val model = trainer.currentModel ?: return null
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
        private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        private var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

        /**
         * Set the configuration.
         */
        fun config(config: EdgeMLConfig) = apply {
            this.config = config
        }

        /**
         * Set the IO dispatcher (default: Dispatchers.IO).
         */
        fun ioDispatcher(dispatcher: CoroutineDispatcher) = apply {
            this.ioDispatcher = dispatcher
        }

        /**
         * Set the Main dispatcher (default: Dispatchers.Main).
         */
        fun mainDispatcher(dispatcher: CoroutineDispatcher) = apply {
            this.mainDispatcher = dispatcher
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

            val client = EdgeMLClient(
                context = context.applicationContext,
                config = cfg,
                ioDispatcher = ioDispatcher,
                mainDispatcher = mainDispatcher,
            )
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
