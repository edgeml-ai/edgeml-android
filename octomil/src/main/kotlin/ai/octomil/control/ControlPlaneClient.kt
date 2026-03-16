package ai.octomil.control

import ai.octomil.api.OctomilApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber

/**
 * Response DTO from GET /api/v1/control/sync.
 */
@Serializable
data class ControlSyncResponse(
    @SerialName("config_version") val configVersion: String,
    val assignments: Map<String, String> = emptyMap(),
    val rollouts: Map<String, String> = emptyMap(),
)

// ---------------------------------------------------------------------------
// Observed State (GAP-05, contract: devices.observed_state 1.4.0)
// ---------------------------------------------------------------------------

/**
 * Per-artifact status entry in an observed state report.
 */
@Serializable
data class ArtifactStatusEntry(
    @SerialName("artifactId") val artifactId: String,
    val status: String,
    @SerialName("bytesDownloaded") val bytesDownloaded: Long? = null,
    @SerialName("totalBytes") val totalBytes: Long? = null,
    @SerialName("errorCode") val errorCode: String? = null,
)

/**
 * Request body for POST /devices/{id}/observed-state.
 */
@Serializable
data class ObservedStateRequest(
    @SerialName("schemaVersion") val schemaVersion: String = "1.4.0",
    @SerialName("deviceId") val deviceId: String,
    @SerialName("reportedAt") val reportedAt: String,
    @SerialName("artifactStatuses") val artifactStatuses: List<ArtifactStatusEntry> = emptyList(),
    @SerialName("sdkVersion") val sdkVersion: String? = null,
    @SerialName("osVersion") val osVersion: String? = null,
)

// ---------------------------------------------------------------------------
// Desired State (GAP-13, contract: devices.desired_state 1.4.0)
// ---------------------------------------------------------------------------

/**
 * Response from GET /devices/{id}/desired-state.
 */
@Serializable
data class DesiredStateResponse(
    @SerialName("schema_version") val schemaVersion: String = "",
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("generated_at") val generatedAt: String = "",
    @SerialName("artifacts") val artifacts: List<DesiredArtifact> = emptyList(),
    @SerialName("gc_eligible_artifact_ids") val gcEligibleArtifactIds: List<String> = emptyList(),
)

/**
 * An artifact entry in the desired state response.
 */
@Serializable
data class DesiredArtifact(
    @SerialName("artifact_id") val artifactId: String = "",
    @SerialName("model_id") val modelId: String = "",
    val version: String = "",
    @SerialName("total_bytes") val totalBytes: Long = 0,
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

    /**
     * Report observed device state to the server (GAP-05).
     *
     * Posts artifact statuses, SDK version, and OS info to
     * `POST /api/v1/devices/{id}/observed-state`.
     *
     * @param artifactStatuses Per-artifact status entries.
     * @return true if the server accepted the report.
     */
    suspend fun reportObservedState(
        artifactStatuses: List<ArtifactStatusEntry> = emptyList(),
    ): Boolean {
        val id = deviceId ?: run {
            Timber.w("Cannot report observed state: no deviceId")
            return false
        }
        return try {
            val request = ObservedStateRequest(
                deviceId = id,
                reportedAt = java.time.Instant.now().toString(),
                artifactStatuses = artifactStatuses,
                sdkVersion = ai.octomil.BuildConfig.OCTOMIL_VERSION,
                osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
            )
            val response = api.reportObservedState(id, request)
            if (!response.isSuccessful) {
                Timber.w("Report observed state failed: HTTP %d", response.code())
            }
            response.isSuccessful
        } catch (e: Exception) {
            Timber.w(e, "Report observed state failed")
            false
        }
    }

    /**
     * Fetch server-authoritative desired state for this device (GAP-13).
     *
     * Gets `GET /api/v1/devices/{id}/desired-state` containing target
     * binding, artifacts to download, policy config, and GC candidates.
     *
     * @return The desired state, or null on failure.
     */
    suspend fun fetchDesiredState(): DesiredStateResponse? {
        val id = deviceId ?: run {
            Timber.w("Cannot fetch desired state: no deviceId")
            return null
        }
        return try {
            val response = api.fetchDesiredState(id)
            if (!response.isSuccessful) {
                Timber.w("Fetch desired state failed: HTTP %d", response.code())
                return null
            }
            response.body()
        } catch (e: Exception) {
            Timber.w(e, "Fetch desired state failed")
            null
        }
    }
}
