package ai.octomil.sync

import ai.octomil.api.OctomilApi
import ai.octomil.api.OctomilApiFactory
import ai.octomil.api.dto.AnyValue
import ai.octomil.api.dto.ExportLogsServiceRequest
import ai.octomil.api.dto.InstrumentationScope
import ai.octomil.api.dto.KeyValue
import ai.octomil.api.dto.LogRecord
import ai.octomil.api.dto.ObservedModelStatus
import ai.octomil.api.dto.ObservedStateRequest
import ai.octomil.api.dto.OtlpResource
import ai.octomil.api.dto.ResourceLogs
import ai.octomil.api.dto.ScopeLogs
import ai.octomil.api.dto.TelemetryAttributes
import ai.octomil.api.dto.TelemetryV2Event
import ai.octomil.api.dto.TelemetryV2Resource
import ai.octomil.BuildConfig
import ai.octomil.config.OctomilConfig
import ai.octomil.control.ControlPlaneClient
import ai.octomil.models.ModelManager
import ai.octomil.storage.SecureStorage
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Background sync worker for Octomil SDK.
 *
 * Handles:
 * - Periodic model update checks
 * - Queued event reporting
 * - Offline sync recovery
 */
class OctomilSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        const val TAG = "OctomilSyncWorker"
        const val WORK_NAME_PERIODIC = "octomil_periodic_sync"
        const val WORK_NAME_ONE_TIME = "octomil_one_time_sync"

        // Input data keys
        const val KEY_CONFIG_JSON = "config_json"
        const val KEY_SYNC_TYPE = "sync_type"

        // Sync types
        const val SYNC_TYPE_MODEL = "model"
        const val SYNC_TYPE_EVENTS = "events"
        const val SYNC_TYPE_FULL = "full"
        const val SYNC_TYPE_DESIRED_STATE = "desired_state"
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    override suspend fun doWork(): Result {
        return try {
            Timber.d("Starting Octomil sync work")

            // Get configuration from input data
            val configJson = inputData.getString(KEY_CONFIG_JSON)
            if (configJson.isNullOrBlank()) {
                Timber.e("No configuration provided to worker")
                return Result.failure()
            }

            val config = json.decodeFromString<OctomilConfig>(configJson)
            val syncType = inputData.getString(KEY_SYNC_TYPE) ?: SYNC_TYPE_FULL

            // Initialize components
            val storage = SecureStorage.getInstance(applicationContext, config.enableEncryptedStorage)
            val api = OctomilApiFactory.create(config)
            val modelManager = ModelManager(applicationContext, config, api, storage)

            // Perform sync based on type
            when (syncType) {
                SYNC_TYPE_MODEL -> {
                    syncModels(modelManager)
                }

                SYNC_TYPE_EVENTS -> {
                    syncEvents(api, storage, config)
                }

                SYNC_TYPE_DESIRED_STATE -> {
                    syncDesiredState(api, storage, config)
                }

                SYNC_TYPE_FULL -> {
                    syncModels(modelManager)
                    syncEvents(api, storage, config)
                    syncDesiredState(api, storage, config)
                }
            }

            // Update last sync timestamp
            storage.setLastSyncTimestamp()

            Timber.d("Octomil sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Octomil sync failed")

            // Retry with exponential backoff for transient failures
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        "error" to e.message,
                        "attempts" to runAttemptCount,
                    ),
                )
            }
        }
    }

    private fun formatIso8601(epochMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMillis))
    }

    private suspend fun syncDesiredState(
        api: OctomilApi,
        storage: SecureStorage,
        config: OctomilConfig,
    ) {
        val deviceId = storage.getServerDeviceId() ?: return
        Timber.d("Syncing desired state for device $deviceId")

        val controlClient = ControlPlaneClient(api, config.orgId, deviceId)
        val desiredState = controlClient.fetchDesiredState(deviceId) ?: run {
            Timber.d("No desired state available")
            return
        }

        val observedModels = mutableListOf<ObservedModelStatus>()
        val artifactCacheDir = java.io.File(applicationContext.cacheDir, "octomil_artifacts").apply { mkdirs() }
        val metadataStore = ArtifactMetadataStore(artifactCacheDir)
        val activationManager = ActivationManager(metadataStore)

        for (model in desiredState.models) {
            val modelId = model.modelId
            val desiredVersion = model.desiredVersion
            val policy = ActivationPolicy.fromCode(model.activationPolicy)
            val manifest = model.artifactManifest

            // Version-based comparison: skip download if installed version matches desired
            val installedVersion = metadataStore.installedVersion(modelId)
            if (installedVersion != null && installedVersion == desiredVersion) {
                val activeEntry = metadataStore.activeEntry(modelId)
                observedModels.add(
                    ObservedModelStatus(
                        modelId = modelId,
                        status = "active",
                        installedVersion = desiredVersion,
                        activeVersion = activeEntry?.modelVersion,
                        health = "healthy",
                    ),
                )
                continue
            }

            // Need artifact manifest to download
            if (manifest == null) {
                Timber.w("No artifact manifest for $modelId desired=$desiredVersion, skipping")
                observedModels.add(
                    ObservedModelStatus(
                        modelId = modelId,
                        status = "failed_retryable",
                        lastError = "no_artifact_manifest",
                    ),
                )
                continue
            }

            val artifactId = manifest.artifactId
            val artifactVersion = manifest.version
            val downloadUrl = manifest.cdnBaseUrl
            val checksum = manifest.sha256 ?: ""
            val totalBytes = manifest.totalBytes
            val localFile = java.io.File(artifactCacheDir, "${artifactId}_${artifactVersion}")

            try {
                Timber.d("Downloading artifact $artifactId v$artifactVersion (model $modelId desired=$desiredVersion)")

                if (downloadUrl.isNullOrEmpty()) {
                    Timber.w("No download URL for $artifactId, skipping")
                    observedModels.add(
                        ObservedModelStatus(
                            modelId = modelId,
                            status = "failed_retryable",
                            lastError = "no_download_url",
                        ),
                    )
                    continue
                }

                val response = api.downloadModelFile(downloadUrl)
                if (response.isSuccessful) {
                    response.body()?.let { body ->
                        localFile.outputStream().use { out ->
                            body.byteStream().use { input ->
                                input.copyTo(out)
                            }
                        }
                    }
                    // Verify checksum (SHA-256)
                    val actualChecksum = checksumFile(localFile)
                    if (checksum.isNotEmpty() && actualChecksum != checksum) {
                        Timber.w("Checksum mismatch for $artifactId: expected=$checksum, actual=$actualChecksum")
                        localFile.delete()
                        metadataStore.markFailed(artifactId, artifactVersion, "checksum_mismatch")
                        observedModels.add(
                            ObservedModelStatus(
                                modelId = modelId,
                                status = "failed_corrupt",
                                installedVersion = desiredVersion,
                                lastError = "checksum_mismatch",
                            ),
                        )
                        continue
                    }

                    // Record verified artifact in metadata store
                    metadataStore.upsert(
                        ArtifactMetadataEntry(
                            artifactId = artifactId,
                            artifactVersion = artifactVersion,
                            modelId = modelId,
                            modelVersion = desiredVersion,
                            installedAt = System.currentTimeMillis(),
                            status = ArtifactSyncStatus.VERIFIED,
                            filePath = localFile.absolutePath,
                            checksum = actualChecksum,
                            sizeBytes = localFile.length(),
                        ),
                    )

                    // Activate according to policy
                    val activationResult = activationManager.activate(
                        modelId = modelId,
                        artifactId = artifactId,
                        artifactVersion = artifactVersion,
                        policy = policy,
                    )

                    if (!activationResult.success) {
                        // Activation failed — rollback to previous version
                        val rollbackResult = activationManager.rollback(
                            modelId = modelId,
                            failedArtifactId = artifactId,
                            failedVersion = artifactVersion,
                            errorCode = activationResult.errorCode ?: "activation_failed",
                        )
                        observedModels.add(
                            ObservedModelStatus(
                                modelId = modelId,
                                status = "failed_healthcheck",
                                installedVersion = desiredVersion,
                                activeVersion = rollbackResult.previousVersion,
                                lastError = activationResult.errorCode ?: "activation_failed",
                            ),
                        )
                    } else {
                        val activeEntry = metadataStore.activeEntry(modelId)
                        val statusCode = when (policy) {
                            ActivationPolicy.IMMEDIATE -> "active"
                            ActivationPolicy.NEXT_LAUNCH,
                            ActivationPolicy.WHEN_IDLE,
                            ActivationPolicy.MANUAL -> "staged"
                        }
                        observedModels.add(
                            ObservedModelStatus(
                                modelId = modelId,
                                status = statusCode,
                                installedVersion = desiredVersion,
                                activeVersion = activeEntry?.modelVersion,
                                health = if (statusCode == "active") "healthy" else null,
                            ),
                        )
                    }
                } else {
                    observedModels.add(
                        ObservedModelStatus(
                            modelId = modelId,
                            status = "failed_retryable",
                            lastError = "download_http_${response.code()}",
                        ),
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to download artifact $artifactId")
                observedModels.add(
                    ObservedModelStatus(
                        modelId = modelId,
                        status = "failed_retryable",
                        lastError = "download_exception",
                    ),
                )
            }
        }

        // GC eligible artifacts: remove from metadata store and delete files
        for (gcId in desiredState.gcEligibleArtifactIds) {
            val gcEntries = metadataStore.allEntries().filter { it.artifactId == gcId }
            for (entry in gcEntries) {
                metadataStore.remove(entry.artifactId, entry.artifactVersion)
            }
            val gcFiles = artifactCacheDir.listFiles { _, name -> name.startsWith("${gcId}_") }
            gcFiles?.forEach { file ->
                Timber.d("GC artifact: ${file.name}")
                file.delete()
            }
        }

        // Report observed state
        controlClient.reportObservedState(deviceId, observedModels)
        Timber.i("Desired state sync complete: ${observedModels.size} models processed")
    }

    private fun checksumFile(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun syncModels(modelManager: ModelManager) {
        Timber.d("Checking for model updates")
        val result = modelManager.ensureModelAvailable()

        result
            .onSuccess { model ->
                Timber.i("Model sync successful: ${model.modelId} v${model.version}")
            }.onFailure { error ->
                Timber.w(error, "Model sync failed (non-critical)")
            }
    }

    private suspend fun syncEvents(
        api: OctomilApi,
        storage: SecureStorage,
        config: OctomilConfig,
    ) {
        Timber.d("Syncing queued events")

        val eventQueue = EventQueue.getInstance(applicationContext)
        val events = eventQueue.getPendingEvents()

        if (events.isEmpty()) {
            Timber.d("No pending events to sync")
            return
        }

        val deviceId = storage.getServerDeviceId() ?: return
        val version = storage.getCurrentModelVersion() ?: "unknown"
        var successCount = 0

        // Build v2 events from the queued training events
        val v2Events = events.map { event ->
            val attrs = mutableMapOf<String, JsonPrimitive>(
                "model.id" to JsonPrimitive(config.modelId ?: ""),
                "model.version" to JsonPrimitive(version),
                "device.id" to JsonPrimitive(deviceId),
            )
            event.metrics?.forEach { (k, v) -> attrs["training.$k"] = JsonPrimitive(v) }
            event.metadata?.forEach { (k, v) -> attrs["training.$k"] = JsonPrimitive(v) }

            TelemetryV2Event(
                name = "training.${event.type}",
                timestamp = formatIso8601(event.timestamp),
                attributes = attrs,
            )
        }

        // Send as a single OTLP/JSON batch
        try {
            val resourceAttrs = listOfNotNull(
                KeyValue("service.name", AnyValue.StringValue("octomil-android-sdk")),
                KeyValue("service.version", AnyValue.StringValue(BuildConfig.OCTOMIL_VERSION)),
                KeyValue("telemetry.sdk.name", AnyValue.StringValue("android")),
                KeyValue("telemetry.sdk.language", AnyValue.StringValue("android")),
                deviceId?.let { KeyValue("device.id", AnyValue.StringValue(it)) },
                KeyValue("org.id", AnyValue.StringValue(config.orgId)),
            )

            val logRecords = v2Events.map { ev ->
                val attrs = ev.attributes.map { (k, v) ->
                    val anyValue: AnyValue = when {
                        v.isString -> AnyValue.StringValue(v.content)
                        v.content.toBooleanStrictOrNull() != null ->
                            AnyValue.BoolValue(v.content.toBooleanStrict())
                        v.content.toLongOrNull() != null ->
                            AnyValue.IntValue(v.content.toLong())
                        v.content.toDoubleOrNull() != null ->
                            AnyValue.DoubleValue(v.content.toDouble())
                        else -> AnyValue.StringValue(v.content)
                    }
                    KeyValue(k, anyValue)
                }
                LogRecord(
                    timeUnixNano = "0",
                    body = AnyValue.StringValue(ev.name),
                    attributes = attrs.ifEmpty { null },
                    traceId = ev.traceId,
                    spanId = ev.spanId,
                )
            }

            val otlpRequest = ExportLogsServiceRequest(
                resourceLogs = listOf(
                    ResourceLogs(
                        resource = OtlpResource(attributes = resourceAttrs),
                        scopeLogs = listOf(
                            ScopeLogs(
                                scope = InstrumentationScope(
                                    name = "ai.octomil.android",
                                    version = BuildConfig.OCTOMIL_VERSION,
                                ),
                                logRecords = logRecords,
                            ),
                        ),
                    ),
                ),
            )

            val response = api.sendTelemetryV2(otlpRequest)
            if (response.isSuccessful) {
                for (event in events) {
                    eventQueue.removeEvent(event.id)
                }
                successCount = events.size
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to sync training events via OTLP")
        }

        Timber.i("Synced $successCount/${events.size} events")
    }
}

/**
 * Manager for scheduling Octomil background sync operations.
 */
class WorkManagerSync(
    private val context: Context,
    private val config: OctomilConfig,
) {
    private val workManager = WorkManager.getInstance(context)
    private val json = Json { encodeDefaults = true }
    private val storage = SecureStorage.getInstance(context, config.enableEncryptedStorage)

    /**
     * Schedule periodic background sync.
     *
     * Runs at the configured interval when conditions are met:
     * - Network available
     * - Battery level above minimum
     * - Charging (if required)
     * - Unmetered network (if required)
     *
     * Policy is enforced based on server-side settings when available,
     * falling back to local config defaults.
     */
    fun schedulePeriodicSync() {
        if (!config.enableBackgroundSync) {
            Timber.d("Background sync disabled")
            return
        }

        val constraints = buildConstraints()
        val configJson = json.encodeToString(config)

        val syncRequest =
            PeriodicWorkRequestBuilder<OctomilSyncWorker>(
                config.syncIntervalMinutes,
                TimeUnit.MINUTES,
                // Flex interval of 15 minutes
                15,
                TimeUnit.MINUTES,
            ).setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        OctomilSyncWorker.KEY_CONFIG_JSON to configJson,
                        OctomilSyncWorker.KEY_SYNC_TYPE to OctomilSyncWorker.SYNC_TYPE_FULL,
                    ),
                ).setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                ).addTag(OctomilSyncWorker.TAG)
                .build()

        workManager.enqueueUniquePeriodicWork(
            OctomilSyncWorker.WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest,
        )

        Timber.i("Scheduled periodic sync every ${config.syncIntervalMinutes} minutes with server policy enforcement")
    }

    /**
     * Cancel periodic sync.
     */
    fun cancelPeriodicSync() {
        workManager.cancelUniqueWork(OctomilSyncWorker.WORK_NAME_PERIODIC)
        Timber.i("Cancelled periodic sync")
    }

    /**
     * Trigger an immediate sync.
     *
     * @param syncType Type of sync to perform (model, events, or full)
     * @param expedited If true, try to run immediately with expedited execution
     */
    fun triggerImmediateSync(
        syncType: String = OctomilSyncWorker.SYNC_TYPE_FULL,
        expedited: Boolean = true,
    ) {
        val configJson = json.encodeToString(config)

        val syncRequest =
            OneTimeWorkRequestBuilder<OctomilSyncWorker>()
                .setInputData(
                    workDataOf(
                        OctomilSyncWorker.KEY_CONFIG_JSON to configJson,
                        OctomilSyncWorker.KEY_SYNC_TYPE to syncType,
                    ),
                ).setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                ).apply {
                    if (expedited) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }.addTag(OctomilSyncWorker.TAG)
                .build()

        workManager.enqueueUniqueWork(
            OctomilSyncWorker.WORK_NAME_ONE_TIME,
            ExistingWorkPolicy.REPLACE,
            syncRequest,
        )

        Timber.i("Triggered immediate $syncType sync")
    }

    /**
     * Get sync work status.
     */
    fun getSyncStatus(): LiveDataStatus =
        LiveDataStatus(
            periodicWork =
                workManager.getWorkInfosForUniqueWorkLiveData(
                    OctomilSyncWorker.WORK_NAME_PERIODIC,
                ),
            oneTimeWork =
                workManager.getWorkInfosForUniqueWorkLiveData(
                    OctomilSyncWorker.WORK_NAME_ONE_TIME,
                ),
        )

    /**
     * Cancel all sync work.
     */
    fun cancelAllSync() {
        workManager.cancelAllWorkByTag(OctomilSyncWorker.TAG)
        Timber.i("Cancelled all sync work")
    }

    private fun buildConstraints(): Constraints {
        // Fetch device policy from server cache, fall back to local config
        val policy =
            runBlocking {
                try {
                    storage.getDevicePolicy()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to fetch cached device policy, using defaults")
                    null
                }
            }

        // Apply server policy if available, otherwise use local config
        val batteryThreshold = policy?.batteryThreshold ?: config.minBatteryLevel
        val requireWifiOnly =
            if (policy != null) {
                policy.networkPolicy == "wifi_only"
            } else {
                config.requireUnmeteredNetwork
            }

        Timber.d(
            "Building constraints with battery≥$batteryThreshold%, " +
                "wifi_only=$requireWifiOnly, charging=${config.requireCharging} " +
                "(source: ${if (policy != null) "server" else "local config"})",
        )

        return Constraints
            .Builder()
            .setRequiredNetworkType(
                if (requireWifiOnly) {
                    NetworkType.UNMETERED
                } else {
                    NetworkType.CONNECTED
                },
            ).setRequiresCharging(config.requireCharging)
            .setRequiresBatteryNotLow(batteryThreshold >= 20)
            .build()
    }
}

/**
 * Live data status wrapper for sync work.
 */
data class LiveDataStatus(
    val periodicWork: androidx.lifecycle.LiveData<List<WorkInfo>>,
    val oneTimeWork: androidx.lifecycle.LiveData<List<WorkInfo>>,
)
