package ai.edgeml.sync

import ai.edgeml.api.EdgeMLApi
import ai.edgeml.api.EdgeMLApiFactory
import ai.edgeml.api.dto.TrainingEventRequest
import ai.edgeml.config.EdgeMLConfig
import ai.edgeml.models.ModelManager
import ai.edgeml.storage.SecureStorage
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Background sync worker for EdgeML SDK.
 *
 * Handles:
 * - Periodic model update checks
 * - Queued event reporting
 * - Offline sync recovery
 */
class EdgeMLSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "EdgeMLSyncWorker"
        const val WORK_NAME_PERIODIC = "edgeml_periodic_sync"
        const val WORK_NAME_ONE_TIME = "edgeml_one_time_sync"

        // Input data keys
        const val KEY_CONFIG_JSON = "config_json"
        const val KEY_SYNC_TYPE = "sync_type"

        // Sync types
        const val SYNC_TYPE_MODEL = "model"
        const val SYNC_TYPE_EVENTS = "events"
        const val SYNC_TYPE_FULL = "full"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Timber.d("Starting EdgeML sync work")

            // Get configuration from input data
            val configJson = inputData.getString(KEY_CONFIG_JSON)
            if (configJson.isNullOrBlank()) {
                Timber.e("No configuration provided to worker")
                return@withContext Result.failure()
            }

            val config = json.decodeFromString<EdgeMLConfig>(configJson)
            val syncType = inputData.getString(KEY_SYNC_TYPE) ?: SYNC_TYPE_FULL

            // Initialize components
            val storage = SecureStorage.getInstance(applicationContext, config.enableEncryptedStorage)
            val api = EdgeMLApiFactory.create(config)
            val modelManager = ModelManager(applicationContext, config, api, storage)

            // Perform sync based on type
            when (syncType) {
                SYNC_TYPE_MODEL -> syncModels(modelManager)
                SYNC_TYPE_EVENTS -> syncEvents(api, storage, config)
                SYNC_TYPE_FULL -> {
                    syncModels(modelManager)
                    syncEvents(api, storage, config)
                }
            }

            // Update last sync timestamp
            storage.setLastSyncTimestamp()

            Timber.d("EdgeML sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "EdgeML sync failed")

            // Retry with exponential backoff for transient failures
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        "error" to e.message,
                        "attempts" to runAttemptCount,
                    )
                )
            }
        }
    }

    private suspend fun syncModels(modelManager: ModelManager) {
        Timber.d("Checking for model updates")
        val result = modelManager.ensureModelAvailable()

        result.onSuccess { model ->
            Timber.i("Model sync successful: ${model.modelId} v${model.version}")
        }.onFailure { error ->
            Timber.w(error, "Model sync failed (non-critical)")
        }
    }

    private suspend fun syncEvents(
        api: EdgeMLApi,
        storage: SecureStorage,
        config: EdgeMLConfig,
    ) {
        Timber.d("Syncing queued events")

        val eventQueue = EventQueue.getInstance(applicationContext)
        val events = eventQueue.getPendingEvents()

        if (events.isEmpty()) {
            Timber.d("No pending events to sync")
            return
        }

        val deviceId = storage.getDeviceId() ?: return
        val experimentId = storage.getExperimentId() ?: "default"
        var successCount = 0

        for (event in events) {
            try {
                val request = TrainingEventRequest(
                    deviceId = deviceId,
                    modelId = config.modelId,
                    version = storage.getCurrentModelVersion() ?: "unknown",
                    eventType = event.type,
                    timestamp = Instant.ofEpochMilli(event.timestamp).toString(),
                    metrics = event.metrics,
                    metadata = event.metadata,
                )

                val response = api.reportTrainingEvent(experimentId, request)
                if (response.isSuccessful) {
                    eventQueue.removeEvent(event.id)
                    successCount++
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to sync event ${event.id}")
            }
        }

        Timber.i("Synced $successCount/${events.size} events")
    }
}

/**
 * Manager for scheduling EdgeML background sync operations.
 */
class WorkManagerSync(
    private val context: Context,
    private val config: EdgeMLConfig,
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

        val syncRequest = PeriodicWorkRequestBuilder<EdgeMLSyncWorker>(
            config.syncIntervalMinutes,
            TimeUnit.MINUTES,
            // Flex interval of 15 minutes
            15,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    EdgeMLSyncWorker.KEY_CONFIG_JSON to configJson,
                    EdgeMLSyncWorker.KEY_SYNC_TYPE to EdgeMLSyncWorker.SYNC_TYPE_FULL,
                )
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .addTag(EdgeMLSyncWorker.TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            EdgeMLSyncWorker.WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest,
        )

        Timber.i("Scheduled periodic sync every ${config.syncIntervalMinutes} minutes with server policy enforcement")
    }

    /**
     * Cancel periodic sync.
     */
    fun cancelPeriodicSync() {
        workManager.cancelUniqueWork(EdgeMLSyncWorker.WORK_NAME_PERIODIC)
        Timber.i("Cancelled periodic sync")
    }

    /**
     * Trigger an immediate sync.
     *
     * @param syncType Type of sync to perform (model, events, or full)
     * @param expedited If true, try to run immediately with expedited execution
     */
    fun triggerImmediateSync(
        syncType: String = EdgeMLSyncWorker.SYNC_TYPE_FULL,
        expedited: Boolean = true,
    ) {
        val configJson = json.encodeToString(config)

        val syncRequest = OneTimeWorkRequestBuilder<EdgeMLSyncWorker>()
            .setInputData(
                workDataOf(
                    EdgeMLSyncWorker.KEY_CONFIG_JSON to configJson,
                    EdgeMLSyncWorker.KEY_SYNC_TYPE to syncType,
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .apply {
                if (expedited) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .addTag(EdgeMLSyncWorker.TAG)
            .build()

        workManager.enqueueUniqueWork(
            EdgeMLSyncWorker.WORK_NAME_ONE_TIME,
            ExistingWorkPolicy.REPLACE,
            syncRequest,
        )

        Timber.i("Triggered immediate $syncType sync")
    }

    /**
     * Get sync work status.
     */
    fun getSyncStatus(): LiveDataStatus {
        return LiveDataStatus(
            periodicWork = workManager.getWorkInfosForUniqueWorkLiveData(
                EdgeMLSyncWorker.WORK_NAME_PERIODIC
            ),
            oneTimeWork = workManager.getWorkInfosForUniqueWorkLiveData(
                EdgeMLSyncWorker.WORK_NAME_ONE_TIME
            ),
        )
    }

    /**
     * Cancel all sync work.
     */
    fun cancelAllSync() {
        workManager.cancelAllWorkByTag(EdgeMLSyncWorker.TAG)
        Timber.i("Cancelled all sync work")
    }

    private fun buildConstraints(): Constraints {
        // Fetch device policy from server cache, fall back to local config
        val policy = runBlocking {
            try {
                storage.getDevicePolicy()
            } catch (e: Exception) {
                Timber.w(e, "Failed to fetch cached device policy, using defaults")
                null
            }
        }

        // Apply server policy if available, otherwise use local config
        val batteryThreshold = policy?.batteryThreshold ?: config.minBatteryLevel
        val requireWifiOnly = policy?.networkPolicy == "wifi_only"
            ?: config.requireUnmeteredNetwork

        Timber.d(
            "Building constraints with battery≥$batteryThreshold%, " +
            "wifi_only=$requireWifiOnly, charging=${config.requireCharging} " +
            "(source: ${if (policy != null) "server" else "local config"})"
        )

        return Constraints.Builder()
            .setRequiredNetworkType(
                if (requireWifiOnly) NetworkType.UNMETERED
                else NetworkType.CONNECTED
            )
            .setRequiresCharging(config.requireCharging)
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
