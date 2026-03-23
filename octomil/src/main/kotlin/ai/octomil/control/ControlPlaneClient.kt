package ai.octomil.control

import ai.octomil.api.OctomilApi
import ai.octomil.api.dto.ActiveVersionEntry
import ai.octomil.api.dto.DesiredStateResponse
import ai.octomil.api.dto.DeviceSyncRequest
import ai.octomil.api.dto.DeviceSyncResponse
import ai.octomil.api.dto.HeartbeatRequest
import ai.octomil.api.dto.ModelInventoryEntry
import ai.octomil.api.dto.ObservedModelStatus
import ai.octomil.api.dto.ObservedStateRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Response DTO from GET /api/v1/control/sync.
 */
@Serializable
data class ControlSyncResponse(
    @SerialName("config_version") val configVersion: String,
    val assignments: Map<String, String> = emptyMap(),
    val rollouts: Map<String, String> = emptyMap(),
)

/**
 * Client for the Octomil control plane.
 *
 * Fetches remote configuration (feature flags, experiment assignments,
 * rollout rules) and reports what changed since the last sync.
 *
 * @param api The Retrofit API interface.
 * @param orgId The organization ID used for the sync request.
 * @param deviceId Optional client-side device identifier. When provided, the
 *   server can return device-specific assignments and rollout decisions.
 */
class ControlPlaneClient(
    private val api: OctomilApi,
    private val orgId: String,
    private val deviceId: String? = null,
) {
    private var lastConfigVersion: String? = null
    private var lastAssignments: Map<String, String> = emptyMap()
    private var lastRollouts: Map<String, String> = emptyMap()

    /**
     * Send a heartbeat for the device. Fire-and-forget: swallows all exceptions.
     */
    suspend fun heartbeat(deviceId: String = this.deviceId.orEmpty(), request: HeartbeatRequest) {
        try {
            api.sendHeartbeat(deviceId, request)
        } catch (e: Exception) {
            Timber.d(e, "Heartbeat failed (non-blocking)")
        }
    }

    /**
     * Fetch the desired state for a device. Returns null on error.
     */
    suspend fun fetchDesiredState(deviceId: String = this.deviceId.orEmpty()): DesiredStateResponse? {
        return try {
            val response = api.getDesiredState(deviceId)
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            Timber.d(e, "Desired state fetch failed")
            null
        }
    }

    /**
     * Report the device's observed state. Swallows errors.
     */
    suspend fun reportObservedState(
        deviceId: String = this.deviceId.orEmpty(),
        models: List<ObservedModelStatus>,
    ) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val request = ObservedStateRequest(
                deviceId = deviceId,
                reportedAt = sdf.format(Date()),
                models = models,
                sdkVersion = ai.octomil.BuildConfig.OCTOMIL_VERSION,
                osVersion = "Android ${android.os.Build.VERSION.SDK_INT}",
            )
            api.reportObservedState(deviceId, request)
        } catch (e: Exception) {
            Timber.d(e, "Observed state report failed (non-blocking)")
        }
    }

    /**
     * Perform a unified device sync round-trip.
     */
    suspend fun sync(
        deviceId: String = this.deviceId.orEmpty(),
        modelInventory: List<ModelInventoryEntry> = emptyList(),
        activeVersions: List<ActiveVersionEntry> = emptyList(),
        knownStateVersion: String? = null,
        appId: String? = null,
        appVersion: String? = null,
        availableStorageBytes: Long? = null,
    ): DeviceSyncResponse? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val request = DeviceSyncRequest(
                deviceId = deviceId,
                requestedAt = sdf.format(Date()),
                knownStateVersion = knownStateVersion,
                sdkVersion = ai.octomil.BuildConfig.OCTOMIL_VERSION,
                platform = "android",
                appId = appId,
                appVersion = appVersion,
                modelInventory = modelInventory,
                activeVersions = activeVersions,
                availableStorageBytes = availableStorageBytes,
            )
            val response = api.syncDevice(deviceId, request)
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            Timber.d(e, "Unified device sync failed")
            null
        }
    }

    /**
     * Fetch the latest control-plane configuration from the server and
     * return a [ControlSyncResult] describing what changed.
     */
    suspend fun refresh(): ControlSyncResult {
        val fetchedAt = System.currentTimeMillis()
        return try {
            val response = api.syncControl(orgId, deviceId)
            if (!response.isSuccessful) {
                Timber.w("Control sync failed: HTTP %d", response.code())
                return ControlSyncResult(
                    updated = false,
                    configVersion = lastConfigVersion ?: "",
                    assignmentsChanged = false,
                    rolloutsChanged = false,
                    fetchedAt = fetchedAt,
                )
            }

            val body = response.body() ?: return ControlSyncResult(
                updated = false,
                configVersion = lastConfigVersion ?: "",
                assignmentsChanged = false,
                rolloutsChanged = false,
                fetchedAt = fetchedAt,
            )

            val versionChanged = body.configVersion != lastConfigVersion
            val assignmentsChanged = body.assignments != lastAssignments
            val rolloutsChanged = body.rollouts != lastRollouts
            val updated = versionChanged || assignmentsChanged || rolloutsChanged

            // Update local state
            lastConfigVersion = body.configVersion
            lastAssignments = body.assignments
            lastRollouts = body.rollouts

            ControlSyncResult(
                updated = updated,
                configVersion = body.configVersion,
                assignmentsChanged = assignmentsChanged,
                rolloutsChanged = rolloutsChanged,
                fetchedAt = fetchedAt,
            )
        } catch (e: Exception) {
            Timber.w(e, "Control sync failed")
            ControlSyncResult(
                updated = false,
                configVersion = lastConfigVersion ?: "",
                assignmentsChanged = false,
                rolloutsChanged = false,
                fetchedAt = fetchedAt,
            )
        }
    }
}
