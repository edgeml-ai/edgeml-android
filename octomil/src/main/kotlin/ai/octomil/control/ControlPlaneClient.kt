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

/**
 * Client for the Octomil control plane.
 *
 * Fetches remote configuration (feature flags, experiment assignments,
 * rollout rules) and reports what changed since the last sync.
 *
 * @param api The Retrofit API interface.
 * @param orgId The organization ID used for the sync request.
 */
class ControlPlaneClient(
    private val api: OctomilApi,
    private val orgId: String,
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
            val response = api.syncControl(orgId)
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
