package ai.edgeml.client

import ai.edgeml.BuildConfig
import ai.edgeml.api.EdgeMLApi
import ai.edgeml.api.EdgeMLApiFactory
import ai.edgeml.api.dto.DeviceRegistrationRequest
import ai.edgeml.api.dto.GroupMembership
import ai.edgeml.api.dto.HeartbeatRequest
import ai.edgeml.api.dto.RoundAssignment
import ai.edgeml.config.EdgeMLConfig
import ai.edgeml.models.CachedModel
import ai.edgeml.models.DownloadState
import ai.edgeml.models.InferenceInput
import ai.edgeml.models.InferenceOutput
import ai.edgeml.models.ModelManager
import ai.edgeml.secagg.SecAggManager
import ai.edgeml.storage.SecureStorage
import ai.edgeml.sync.EventQueue
import ai.edgeml.sync.EventTypes
import ai.edgeml.sync.WorkManagerSync
import ai.edgeml.training.TFLiteTrainer
import ai.edgeml.training.MissingTrainingSignatureException
import ai.edgeml.training.TrainingConfig
import ai.edgeml.training.TrainingDataProvider
import ai.edgeml.training.TrainingOutcome
import ai.edgeml.training.UploadPolicy
import ai.edgeml.training.WarmupResult
import ai.edgeml.training.WeightUpdate
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
    context: Context,
    private val config: EdgeMLConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val api: EdgeMLApi = EdgeMLApiFactory.create(config),
    private val storage: SecureStorage = SecureStorage.getInstance(context, config.enableEncryptedStorage),
    private val modelManager: ModelManager = ModelManager(context, config, api, storage),
    private val trainer: TFLiteTrainer = TFLiteTrainer(context, config),
    private val syncManager: WorkManagerSync = WorkManagerSync(context, config),
    private val eventQueue: EventQueue = EventQueue.getInstance(context),
    private val secAggManager: SecAggManager? = if (config.enableSecureAggregation) SecAggManager(api) else null,
) {
    // Store application context to avoid leaking Activity/Service references
    private val context: Context = context.applicationContext

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
        fun getInstance(): EdgeMLClient =
            instance
                ?: throw IllegalStateException("EdgeMLClient not initialized. Call Builder.build() first.")

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
    suspend fun initialize(): Result<Unit> =
        withContext(ioDispatcher) {
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
                warmupModel()
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

    /**
     * Run warmup inference to absorb cold-start costs (JIT, GPU shader
     * compilation, delegate init) before [ClientState.READY] is set.
     * Also benchmarks GPU vs CPU — disables GPU if partition stalls detected.
     */
    private suspend fun warmupModel() {
        val result = trainer.warmup()
        if (result != null) {
            eventQueue.addTrainingEvent(
                type = EventTypes.MODEL_WARMUP_COMPLETED,
                metrics = buildMap {
                    put("cold_inference_ms", result.coldInferenceMs)
                    put("warm_inference_ms", result.warmInferenceMs)
                    result.cpuInferenceMs?.let { put("cpu_inference_ms", it) }
                },
                metadata = mapOf(
                    "using_gpu" to result.usingGpu.toString(),
                    "active_delegate" to result.activeDelegate,
                    "delegate_disabled" to result.delegateDisabled.toString(),
                    "disabled_delegates" to result.disabledDelegates.joinToString(","),
                ),
            )
        }
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
    private suspend fun registerDevice(deviceIdentifier: String): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                Timber.d("Registering device: $deviceIdentifier")

                val request =
                    DeviceRegistrationRequest(
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

                val body =
                    response.body()
                        ?: return@withContext Result.failure(Exception("Empty registration response"))

                storage.setServerDeviceId(body.id)
                storage.setClientDeviceIdentifier(deviceIdentifier)
                body.apiToken?.let { storage.setApiToken(it) }

                fetchAndCacheDevicePolicy()

                eventQueue.addTrainingEvent(
                    type = EventTypes.DEVICE_REGISTERED,
                    metadata =
                        mapOf(
                            "device_id" to body.id,
                            "device_identifier" to deviceIdentifier,
                        ),
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
        heartbeatJob =
            scope.launch(ioDispatcher) {
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
            val request =
                HeartbeatRequest(
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
    suspend fun triggerHeartbeat(): Result<Unit> =
        withContext(ioDispatcher) {
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
    suspend fun refreshGroupMemberships(): Result<List<GroupMembership>> =
        withContext(ioDispatcher) {
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
    fun isInGroup(groupId: String): Boolean = _groupMemberships.value.any { it.groupId == groupId }

    /**
     * Check if the device belongs to a group with a specific name.
     */
    fun isInGroupNamed(groupName: String): Boolean = _groupMemberships.value.any { it.groupName == groupName }

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
            result
                .onSuccess { output ->
                    eventQueue.addTrainingEvent(
                        type = EventTypes.INFERENCE_COMPLETED,
                        metrics =
                            mapOf(
                                "inference_time_ms" to output.inferenceTimeMs.toDouble(),
                            ),
                    )
                }.onFailure {
                    eventQueue.addTrainingEvent(
                        type = EventTypes.INFERENCE_FAILED,
                        metadata = mapOf("error" to (it.message ?: "unknown")),
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
    suspend fun runInference(input: InferenceInput): Result<InferenceOutput> = runInference(input.data)

    /**
     * Classify input and get top predictions.
     *
     * @param input Input data as float array
     * @param topK Number of top predictions to return
     * @return List of (class index, confidence) pairs
     */
    suspend fun classify(
        input: FloatArray,
        topK: Int = 5,
    ): Result<List<Pair<Int, Float>>> {
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

        val resolvedEngine = resolveStreamingEngine(engine, modality)

        val sessionId =
            java.util.UUID
                .randomUUID()
                .toString()
        val deviceId = _serverDeviceId.value
        val resolvedModelId = modelId ?: config.modelId
        val resolvedVersion = version ?: "latest"

        return kotlinx.coroutines.flow.flow {
            val sessionStart = System.currentTimeMillis()
            var previousTime = sessionStart
            var firstChunkTime: Long? = null
            val latencies = mutableListOf<Double>()
            var chunkCount = 0

            deviceId?.let { id ->
                reportStreamingEventSafely(
                    ai.edgeml.api.dto.InferenceEventRequest(
                        deviceId = id,
                        modelId = resolvedModelId,
                        version = resolvedVersion,
                        modality = modality.value,
                        sessionId = sessionId,
                        eventType = "generation_started",
                        timestampMs = sessionStart,
                        orgId = config.orgId,
                    ),
                )
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
                        ),
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
                metrics =
                    mapOf(
                        "ttfc_ms" to ttfcMs,
                        "avg_chunk_latency_ms" to avgLatency,
                        "total_chunks" to chunkCount.toDouble(),
                        "total_duration_ms" to totalDurationMs,
                        "throughput" to throughput,
                    ),
                metadata = mapOf("session_id" to sessionId),
            )

            deviceId?.let { id ->
                reportStreamingEventSafely(
                    ai.edgeml.api.dto.InferenceEventRequest(
                        deviceId = id,
                        modelId = resolvedModelId,
                        version = resolvedVersion,
                        modality = modality.value,
                        sessionId = sessionId,
                        eventType = "generation_completed",
                        timestampMs = sessionEnd,
                        metrics =
                            ai.edgeml.api.dto.InferenceEventMetrics(
                                ttfcMs = ttfcMs,
                                totalChunks = chunkCount,
                                totalDurationMs = totalDurationMs,
                                throughput = throughput,
                            ),
                        orgId = config.orgId,
                    ),
                )
            }
        }
    }

    private fun resolveStreamingEngine(
        engine: ai.edgeml.inference.StreamingInferenceEngine?,
        modality: ai.edgeml.inference.Modality,
    ): ai.edgeml.inference.StreamingInferenceEngine =
        engine ?: when (modality) {
            ai.edgeml.inference.Modality.TEXT -> ai.edgeml.inference.LLMEngine(context)
            ai.edgeml.inference.Modality.IMAGE -> ai.edgeml.inference.ImageEngine(context)
            ai.edgeml.inference.Modality.AUDIO -> ai.edgeml.inference.AudioEngine(context)
            ai.edgeml.inference.Modality.VIDEO -> ai.edgeml.inference.VideoEngine(context)
        }

    private suspend fun reportStreamingEventSafely(request: ai.edgeml.api.dto.InferenceEventRequest) {
        try {
            api.reportInferenceEvent(request)
        } catch (e: Exception) {
            Timber.w(e, "Failed to report ${request.eventType} event")
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
    suspend fun updateModel(): Result<CachedModel> =
        modelManager.ensureModelAvailable(forceDownload = true).also { result ->
            result.onSuccess { model ->
                trainer.loadModel(model)
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

    /**
     * Get the model's input/output contract.
     *
     * Use this at setup time to validate that your data pipeline produces
     * the correct shapes and types before calling [runInference]. This
     * enables "compile-time" validation of the data contract rather than
     * discovering shape mismatches at inference time.
     *
     * ```kotlin
     * val contract = client.getModelContract()
     *     ?: error("No model loaded")
     *
     * // Validate your pipeline output matches the model's expectations
     * require(contract.validateInput(myData)) {
     *     "Input shape mismatch: expected ${contract.inputDescription}"
     * }
     * ```
     *
     * @return The model contract, or null if no model is loaded.
     */
    fun getModelContract(): ModelContract? {
        val model = trainer.currentModel ?: return null
        val tensorInfo = trainer.getTensorInfo() ?: return null

        return ModelContract(
            modelId = model.modelId,
            version = model.version,
            inputShape = tensorInfo.inputShape.copyOf(),
            outputShape = tensorInfo.outputShape.copyOf(),
            inputType = tensorInfo.inputType,
            outputType = tensorInfo.outputType,
            hasTrainingSignature = trainer.hasTrainingSignature(),
            signatureKeys = trainer.getSignatureKeys(),
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
    // Training (Unified API)
    // =========================================================================

    /**
     * Train the model on local data with a single, unified API.
     *
     * This is the **recommended** way to do all training. It replaces the
     * previous split between `participateInRound()` and `trainAndUpload()`.
     *
     * ## Upload behavior
     *
     * The [uploadPolicy] parameter controls what happens after training:
     * - [UploadPolicy.AUTO] (default): Extracts weights and uploads automatically.
     *   Uses SecAgg if enabled and a `roundId` is provided.
     * - [UploadPolicy.MANUAL]: Extracts weights but does NOT upload.
     *   Returns them in [TrainingOutcome.weightUpdate] for you to handle.
     * - [UploadPolicy.DISABLED]: No weight extraction or upload. Pure local training.
     *
     * ## Degraded mode
     *
     * If the model lacks TFLite training signatures, behavior depends on
     * [EdgeMLConfig.allowDegradedTraining]:
     * - **false (default)**: Throws [MissingTrainingSignatureException].
     * - **true**: Runs forward-pass training (no weight updates) and sets
     *   [TrainingOutcome.degraded] to `true`.
     *
     * ## Examples
     *
     * ```kotlin
     * // Simple local-only training
     * val outcome = client.train(myDataProvider).getOrThrow()
     *
     * // Federated training with auto-upload
     * val outcome = client.train(
     *     dataProvider = myDataProvider,
     *     uploadPolicy = UploadPolicy.AUTO,
     *     roundId = assignment.id,
     * ).getOrThrow()
     *
     * // Train and inspect weights before uploading
     * val outcome = client.train(
     *     dataProvider = myDataProvider,
     *     uploadPolicy = UploadPolicy.MANUAL,
     * ).getOrThrow()
     * val weights = outcome.weightUpdate!! // non-null for MANUAL
     * ```
     *
     * @param dataProvider Provider for local training data.
     * @param trainingConfig Training hyperparameters (optional, sensible defaults apply).
     * @param uploadPolicy Controls weight extraction and upload. Default: [UploadPolicy.AUTO].
     * @param roundId Optional federated learning round ID. When provided with
     *   [UploadPolicy.AUTO], the server coordinates this as part of a round.
     * @return [TrainingOutcome] with training metrics, optional weights, and upload status.
     */
    suspend fun train(
        dataProvider: TrainingDataProvider,
        trainingConfig: TrainingConfig = TrainingConfig(),
        uploadPolicy: UploadPolicy = UploadPolicy.AUTO,
        roundId: String? = null,
    ): Result<TrainingOutcome> =
        withContext(ioDispatcher) {
            checkReady()

            try {
                val deviceId = _serverDeviceId.value
                    ?: return@withContext Result.failure(Exception("Device not registered"))

                // Check for training signature support
                val isDegraded = !trainer.hasTrainingSignature()
                if (isDegraded && !config.allowDegradedTraining) {
                    return@withContext Result.failure(
                        MissingTrainingSignatureException(trainer.getSignatureKeys())
                    )
                }

                if (isDegraded) {
                    Timber.e(
                        "MODEL TRAINING DEGRADED: Model lacks 'train' signature. " +
                            "Weights will NOT be updated on-device. Loss/accuracy reflect " +
                            "inference only. Export model with training signatures for real learning.",
                    )
                }

                Timber.i("Starting training: policy=$uploadPolicy, round=$roundId, degraded=$isDegraded")

                // If this is a round, fetch config and ensure correct model
                if (roundId != null) {
                    val roundResponse = api.getRound(roundId)
                    if (!roundResponse.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("Failed to fetch round config: ${roundResponse.code()}")
                        )
                    }
                    val modelResult = modelManager.ensureModelAvailable(forceDownload = false)
                    modelResult.onSuccess { model -> trainer.loadModel(model) }
                }

                eventQueue.addTrainingEvent(
                    type = EventTypes.ROUND_PARTICIPATION_STARTED,
                    metadata = buildMap {
                        roundId?.let { put("round_id", it) }
                        put("upload_policy", uploadPolicy.name)
                        put("degraded", isDegraded.toString())
                    },
                )

                // Train locally
                val trainingResult = trainer.train(dataProvider, trainingConfig).getOrThrow()

                eventQueue.addTrainingEvent(
                    type = EventTypes.TRAINING_COMPLETED,
                    metrics = mapOf(
                        "loss" to (trainingResult.loss ?: 0.0),
                        "accuracy" to (trainingResult.accuracy ?: 0.0),
                        "training_time" to trainingResult.trainingTime,
                        "sample_count" to trainingResult.sampleCount.toDouble(),
                    ),
                    metadata = buildMap {
                        roundId?.let { put("round_id", it) }
                        put("degraded", isDegraded.toString())
                    },
                )

                // Handle weight extraction and upload based on policy
                var weightUpdate: WeightUpdate? = null
                var uploaded = false
                var usedSecAgg = false

                when (uploadPolicy) {
                    UploadPolicy.AUTO -> {
                        weightUpdate = trainer.extractWeightUpdate(trainingResult).getOrThrow()
                        val useSecAgg = (config.enableSecureAggregation || roundId != null) &&
                            secAggManager != null && roundId != null
                        usedSecAgg = useSecAgg

                        val uploadResult = if (useSecAgg) {
                            uploadWithSecAgg(weightUpdate, roundId!!, deviceId, trainingResult, isDegraded)
                        } else {
                            uploadPlaintext(weightUpdate, deviceId, roundId, trainingResult, isDegraded)
                        }
                        uploaded = uploadResult.isSuccess
                    }
                    UploadPolicy.MANUAL -> {
                        weightUpdate = trainer.extractWeightUpdate(trainingResult).getOrThrow()
                    }
                    UploadPolicy.DISABLED -> {
                        // No extraction, no upload
                    }
                }

                val outcome = TrainingOutcome(
                    trainingResult = trainingResult,
                    weightUpdate = weightUpdate,
                    uploaded = uploaded,
                    secureAggregation = usedSecAgg,
                    uploadPolicy = uploadPolicy,
                    degraded = isDegraded,
                )

                eventQueue.addTrainingEvent(
                    type = EventTypes.ROUND_PARTICIPATION_COMPLETED,
                    metrics = mapOf(
                        "loss" to (trainingResult.loss ?: 0.0),
                        "accuracy" to (trainingResult.accuracy ?: 0.0),
                        "sample_count" to trainingResult.sampleCount.toDouble(),
                    ),
                    metadata = buildMap {
                        roundId?.let { put("round_id", it) }
                        put("uploaded", uploaded.toString())
                        put("upload_policy", uploadPolicy.name)
                        put("degraded", isDegraded.toString())
                    },
                )

                Timber.i(
                    "Training complete: ${trainingResult.sampleCount} samples, " +
                        "policy=$uploadPolicy, uploaded=$uploaded, degraded=$isDegraded",
                )
                Result.success(outcome)
            } catch (e: MissingTrainingSignatureException) {
                Timber.e(e, "Training blocked: model lacks training signatures")
                Result.failure(e)
            } catch (e: Exception) {
                Timber.e(e, "Training failed")
                eventQueue.addTrainingEvent(
                    type = EventTypes.TRAINING_FAILED,
                    metadata = mapOf("error" to (e.message ?: "unknown")),
                )
                Result.failure(e)
            }
        }

    // =========================================================================
    // Round Management
    // =========================================================================

    /**
     * Check if this device has been selected for an active training round.
     *
     * Polls the server for rounds in the "waiting_for_updates" state for the
     * configured model. Returns the first matching round assignment, or null
     * if no round is currently active for this device.
     *
     * @return The round assignment, or null if none available.
     */
    suspend fun checkForRoundAssignment(): Result<RoundAssignment?> =
        withContext(ioDispatcher) {
            checkReady()

            val deviceId = _serverDeviceId.value
                ?: return@withContext Result.failure(Exception("Device not registered"))

            try {
                val response = api.listRounds(
                    modelId = config.modelId,
                    state = "waiting_for_updates",
                    deviceId = deviceId,
                )

                if (!response.isSuccessful) {
                    Timber.w("Round assignment check failed: ${response.code()}")
                    return@withContext Result.success(null)
                }

                val rounds = response.body() ?: emptyList()
                val assignment = rounds.firstOrNull()

                if (assignment != null) {
                    Timber.i("Round assignment received: ${assignment.id}")
                    eventQueue.addTrainingEvent(
                        type = EventTypes.ROUND_ASSIGNMENT_RECEIVED,
                        metadata = mapOf(
                            "round_id" to assignment.id,
                            "model_id" to assignment.modelId,
                            "aggregation_type" to assignment.aggregationType,
                        ),
                    )
                }

                Result.success(assignment)
            } catch (e: Exception) {
                Timber.w(e, "Error checking for round assignment")
                Result.success(null)
            }
        }

    /**
     * Get the current status of a training round.
     *
     * @param roundId The round to query.
     * @return The round details.
     */
    suspend fun getRoundStatus(roundId: String): Result<RoundAssignment> =
        withContext(ioDispatcher) {
            checkReady()
            try {
                val response = api.getRound(roundId)
                if (response.isSuccessful) {
                    val body = response.body()
                        ?: return@withContext Result.failure(Exception("Empty round response"))
                    Result.success(body)
                } else {
                    Result.failure(Exception("Failed to get round status: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // =========================================================================
    // Weight Upload (internal)
    // =========================================================================

    /**
     * Build the [GradientUpdateRequest] from training context.
     *
     * Centralizes the server payload construction so that all upload
     * paths (SecAgg, plaintext) send consistent, complete data.
     */
    private fun buildGradientRequest(
        weightUpdate: WeightUpdate,
        deviceId: String,
        roundId: String,
        trainingResult: ai.edgeml.training.TrainingResult,
        isDegraded: Boolean,
    ): ai.edgeml.api.dto.GradientUpdateRequest =
        ai.edgeml.api.dto.GradientUpdateRequest(
            deviceId = deviceId,
            modelId = weightUpdate.modelId,
            version = weightUpdate.version,
            roundId = roundId,
            numSamples = weightUpdate.sampleCount,
            trainingTimeMs = (trainingResult.trainingTime * 1000).toLong(),
            metrics = ai.edgeml.api.dto.TrainingMetrics(
                loss = trainingResult.loss ?: 0.0,
                accuracy = trainingResult.accuracy,
                numBatches = trainingResult.metrics["epochs"]?.toInt() ?: 1,
                learningRate = trainingResult.metrics["learning_rate"],
                customMetrics = buildMap {
                    put("degraded", if (isDegraded) 1.0 else 0.0)
                    put("training_method", trainingResult.metrics["training_method"] ?: 0.0)
                    put("client_timestamp_ms", System.currentTimeMillis().toDouble())
                    config.appVersion?.let { put("app_version_hash", it.hashCode().toDouble()) }
                },
            ),
        )

    private suspend fun uploadWithSecAgg(
        weightUpdate: WeightUpdate,
        roundId: String,
        deviceId: String,
        trainingResult: ai.edgeml.training.TrainingResult = ai.edgeml.training.TrainingResult(0, null, null, 0.0),
        isDegraded: Boolean = false,
    ): Result<Unit> {
        val secAgg = secAggManager ?: return Result.failure(Exception("SecAgg not enabled"))

        Timber.d("Uploading weight update with SecAgg for round $roundId")

        secAgg.processWeightUpdate(weightUpdate, roundId, deviceId).getOrThrow()

        val request = buildGradientRequest(weightUpdate, deviceId, roundId, trainingResult, isDegraded)
        val response = api.submitGradients(roundId, request)
        return if (response.isSuccessful) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Upload failed: ${response.code()}"))
        }
    }

    private suspend fun uploadPlaintext(
        weightUpdate: WeightUpdate,
        deviceId: String,
        roundId: String?,
        trainingResult: ai.edgeml.training.TrainingResult = ai.edgeml.training.TrainingResult(0, null, null, 0.0),
        isDegraded: Boolean = false,
    ): Result<Unit> {
        Timber.d("Uploading weight update (plaintext)")

        if (roundId != null) {
            val request = buildGradientRequest(weightUpdate, deviceId, roundId, trainingResult, isDegraded)
            val response = api.submitGradients(roundId, request)
            return if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Upload failed: ${response.code()}"))
            }
        }

        // No round ID - just log that we completed training
        Timber.d("No round ID, skipping upload")
        return Result.success(Unit)
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
    class Builder(
        private val context: Context,
    ) {
        private var config: EdgeMLConfig? = null
        private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        private var mainDispatcher: CoroutineDispatcher = Dispatchers.Main
        private var api: EdgeMLApi? = null
        private var storage: SecureStorage? = null
        private var modelManager: ModelManager? = null
        private var trainer: TFLiteTrainer? = null
        private var syncManager: WorkManagerSync? = null
        private var eventQueue: EventQueue? = null

        /**
         * Set the configuration.
         */
        fun config(config: EdgeMLConfig) =
            apply {
                this.config = config
            }

        /**
         * Set the IO dispatcher (default: Dispatchers.IO).
         */
        fun ioDispatcher(dispatcher: CoroutineDispatcher) =
            apply {
                this.ioDispatcher = dispatcher
            }

        /**
         * Set the Main dispatcher (default: Dispatchers.Main).
         */
        fun mainDispatcher(dispatcher: CoroutineDispatcher) =
            apply {
                this.mainDispatcher = dispatcher
            }

        /** @hide */
        internal fun api(api: EdgeMLApi) = apply { this.api = api }

        /** @hide */
        internal fun storage(storage: SecureStorage) = apply { this.storage = storage }

        /** @hide */
        internal fun modelManager(modelManager: ModelManager) = apply { this.modelManager = modelManager }

        /** @hide */
        internal fun trainer(trainer: TFLiteTrainer) = apply { this.trainer = trainer }

        /** @hide */
        internal fun syncManager(syncManager: WorkManagerSync) = apply { this.syncManager = syncManager }

        /** @hide */
        internal fun eventQueue(eventQueue: EventQueue) = apply { this.eventQueue = eventQueue }

        /**
         * Build and return the EdgeMLClient instance.
         */
        fun build(): EdgeMLClient {
            val cfg =
                config
                    ?: throw IllegalStateException("Configuration is required. Call config() first.")

            // Setup logging in debug mode
            if (cfg.debugMode && Timber.treeCount == 0) {
                Timber.plant(Timber.DebugTree())
            }

            val appContext = context.applicationContext
            val resolvedApi = api ?: EdgeMLApiFactory.create(cfg)
            val resolvedStorage = storage ?: SecureStorage.getInstance(appContext, cfg.enableEncryptedStorage)

            val client =
                EdgeMLClient(
                    context = appContext,
                    config = cfg,
                    ioDispatcher = ioDispatcher,
                    mainDispatcher = mainDispatcher,
                    api = resolvedApi,
                    storage = resolvedStorage,
                    modelManager = modelManager ?: ModelManager(appContext, cfg, resolvedApi, resolvedStorage),
                    trainer = trainer ?: TFLiteTrainer(appContext, cfg),
                    syncManager = syncManager ?: WorkManagerSync(appContext, cfg),
                    eventQueue = eventQueue ?: EventQueue.getInstance(appContext),
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
 * Describes the input/output contract of a loaded model.
 *
 * Use this at initialization time to validate that your data pipeline
 * produces tensors with the correct shape and type. This catches
 * mismatches early rather than at inference time.
 *
 * ```kotlin
 * val contract = client.getModelContract()!!
 *
 * // Check input compatibility
 * val myInput = FloatArray(784)
 * require(contract.validateInput(myInput)) {
 *     "Bad input: ${contract.inputDescription}"
 * }
 *
 * // Check training support
 * if (!contract.hasTrainingSignature) {
 *     Log.w("Model does not support on-device training")
 * }
 * ```
 */
data class ModelContract(
    val modelId: String,
    val version: String,
    /** Expected input tensor shape (e.g., [1, 28, 28, 1] for MNIST). */
    val inputShape: IntArray,
    /** Expected output tensor shape (e.g., [1, 10] for 10-class classifier). */
    val outputShape: IntArray,
    /** Input tensor data type (e.g., "FLOAT32"). */
    val inputType: String,
    /** Output tensor data type (e.g., "FLOAT32"). */
    val outputType: String,
    /** Whether the model supports on-device gradient-based training. */
    val hasTrainingSignature: Boolean,
    /** Available TFLite signature keys (e.g., ["train", "infer", "save"]). */
    val signatureKeys: List<String>,
) {
    /** Total number of input elements expected (product of input shape dimensions). */
    val inputSize: Int get() = inputShape.fold(1) { acc, dim -> acc * dim }

    /** Total number of output elements produced (product of output shape dimensions). */
    val outputSize: Int get() = outputShape.fold(1) { acc, dim -> acc * dim }

    /** Human-readable description of expected input (e.g., "FloatArray[784] shape=[1, 28, 28, 1] type=FLOAT32"). */
    val inputDescription: String
        get() = "FloatArray[$inputSize] shape=${inputShape.contentToString()} type=$inputType"

    /** Human-readable description of output (e.g., "FloatArray[10] shape=[1, 10] type=FLOAT32"). */
    val outputDescription: String
        get() = "FloatArray[$outputSize] shape=${outputShape.contentToString()} type=$outputType"

    /**
     * Validate that a float array is compatible with this model's input.
     *
     * @param input The data to validate.
     * @return true if the input size matches the model's expected input size.
     */
    fun validateInput(input: FloatArray): Boolean = input.size == inputSize

    /**
     * Validate that a float array is compatible with this model's input,
     * throwing a descriptive exception if not.
     *
     * @param input The data to validate.
     * @throws IllegalArgumentException if the input size doesn't match.
     */
    fun requireValidInput(input: FloatArray) {
        require(input.size == inputSize) {
            "Input size mismatch: got ${input.size} elements, " +
                "expected $inputDescription"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ModelContract
        return modelId == other.modelId &&
            version == other.version &&
            inputShape.contentEquals(other.inputShape) &&
            outputShape.contentEquals(other.outputShape) &&
            inputType == other.inputType &&
            outputType == other.outputType &&
            hasTrainingSignature == other.hasTrainingSignature
    }

    override fun hashCode(): Int {
        var result = modelId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + inputShape.contentHashCode()
        result = 31 * result + outputShape.contentHashCode()
        result = 31 * result + inputType.hashCode()
        result = 31 * result + outputType.hashCode()
        result = 31 * result + hasTrainingSignature.hashCode()
        return result
    }
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
