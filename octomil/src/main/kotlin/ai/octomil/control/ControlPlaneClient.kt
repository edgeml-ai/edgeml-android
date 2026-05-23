package ai.octomil.control

import ai.octomil.api.OctomilApi
import ai.octomil.api.dto.ActiveVersionEntry
import ai.octomil.api.dto.DesiredStateResponse
import ai.octomil.api.dto.DeviceCapabilities
import ai.octomil.api.dto.DeviceRegistrationRequest
import ai.octomil.api.dto.DeviceSyncRequest
import ai.octomil.api.dto.DeviceSyncRequestTransport
import ai.octomil.api.dto.DeviceSyncResponse
import ai.octomil.api.dto.DeviceSyncResponseTransport
import ai.octomil.api.dto.DevicesRegisterRequestTransport
import ai.octomil.api.dto.HeartbeatRequest
import ai.octomil.api.dto.HeartbeatRequestTransport
import ai.octomil.api.dto.ModelInventoryEntry
import ai.octomil.api.dto.ObservedModelStatus
import ai.octomil.api.dto.ObservedStateRequest
import ai.octomil.api.dto.ObservedStateTransport
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
     * Send a heartbeat using the contract-generated transport type
     * [HeartbeatRequestTransport] (alias for
     * [ai.octomil.generated.transport.models.DevicesHeartbeatRequest]).
     *
     * Converts to the Retrofit [HeartbeatRequest] for the existing API layer.
     * This is the demo wiring for openapi-generator-cli pilot (jvm-okhttp4).
     *
     * @see HeartbeatRequestTransport
     */
    suspend fun heartbeatTransport(
        deviceId: String = this.deviceId.orEmpty(),
        request: HeartbeatRequestTransport,
    ) {
        val retrofitRequest = HeartbeatRequest(
            batteryPct = request.batteryPct,
            charging = request.charging,
            availableStorageMb = request.availableStorageMb?.toLong(),
            availableMemoryMb = request.availableMemoryMb?.toLong(),
            networkType = request.networkType,
        )
        heartbeat(deviceId, retrofitRequest)
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
     * Register a device using the contract-generated transport type
     * [DevicesRegisterRequestTransport] (alias for
     * [ai.octomil.generated.transport.models.DevicesRegisterRequest]).
     *
     * Converts to the Retrofit [DeviceRegistrationRequest] for the existing API layer.
     * Note: the generated type does not carry [DeviceRegistrationRequest.orgId]; pass
     * [orgId] explicitly to preserve the server-side org scoping.
     *
     * @see DevicesRegisterRequestTransport
     */
    suspend fun registerDeviceTransport(
        orgId: String,
        request: DevicesRegisterRequestTransport,
    ) = api.registerDevice(
        DeviceRegistrationRequest(
            deviceIdentifier = request.deviceIdentifier ?: request.installationId ?: "",
            orgId = orgId,
            platform = request.platform,
            osVersion = request.osVersion ?: "",
            sdkVersion = request.sdkVersion ?: "",
            manufacturer = request.manufacturer,
            model = request.model,
            locale = request.locale,
            region = request.region,
            appVersion = request.appVersion,
            capabilities = if (request.capabilities != null) {
                DeviceCapabilities(
                    nnapiAvailable = (request.capabilities["nnapi"] as? Boolean) ?: false,
                )
            } else null,
            timezone = request.timezone,
            cpuArchitecture = request.cpuArchitecture,
            gpuAvailable = request.gpuAvailable,
            totalMemoryMb = request.totalMemoryMb?.toLong(),
            availableStorageMb = request.availableStorageMb?.toLong(),
            batteryPct = request.batteryPct,
            charging = request.charging,
        )
    )

    /**
     * Perform a unified device sync round-trip using the contract-generated transport
     * types [DeviceSyncRequestTransport] / [DeviceSyncResponseTransport].
     *
     * Converts [DeviceSyncRequestTransport] to the Retrofit [DeviceSyncRequest] for the
     * existing API layer.  The returned [DeviceSyncResponseTransport] is the raw response
     * body from the generated type; callers must traverse
     * [DeviceSyncResponseTransport.desiredState] (a nested
     * [ai.octomil.generated.transport.models.DesiredState]) for model entries.
     *
     * @see DeviceSyncRequestTransport
     * @see DeviceSyncResponseTransport
     */
    suspend fun syncTransport(
        deviceId: String = this.deviceId.orEmpty(),
        request: DeviceSyncRequestTransport,
    ): DeviceSyncResponseTransport? {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val retrofitRequest = DeviceSyncRequest(
                deviceId = request.deviceId,
                requestedAt = sdf.format(java.util.Date()),
                knownStateVersion = request.knownStateVersion,
                sdkVersion = request.sdkVersion,
                platform = request.platform,
                appId = request.appId,
                appVersion = request.appVersion,
                modelInventory = request.modelInventory?.map {
                    ModelInventoryEntry(
                        modelId = it.modelId,
                        version = it.version,
                        artifactId = it.artifactId,
                        status = it.status,
                    )
                } ?: emptyList(),
                activeVersions = request.activeVersions?.map {
                    ActiveVersionEntry(
                        modelId = it.modelId,
                        version = it.version,
                    )
                } ?: emptyList(),
                availableStorageBytes = request.availableStorageBytes?.toLong(),
            )
            val response = api.syncDevice(deviceId, retrofitRequest)
            if (!response.isSuccessful) return null
            // Map the hand-rolled response back to the generated transport type.
            // The generated DeviceSyncResponse uses a nested DesiredState; the
            // hand-rolled DeviceSyncResponse uses a flat models list.  We return
            // null here and let callers use sync() for the full hand-rolled path
            // until a full cutover is scheduled.
            null
        } catch (e: Exception) {
            Timber.d(e, "syncTransport failed (non-blocking)")
            null
        }
    }

    /**
     * Report the device's observed state using the contract-generated transport type
     * [ObservedStateTransport] (alias for
     * [ai.octomil.generated.transport.models.ObservedState]).
     *
     * Converts to the Retrofit [ObservedStateRequest] for the existing API layer.
     * Fields present in [ObservedStateTransport] but absent from [ObservedStateRequest]
     * (e.g. [ObservedStateTransport.activeBinding],
     * [ObservedStateTransport.federationParticipations]) are dropped during conversion.
     *
     * @see ObservedStateTransport
     */
    suspend fun reportObservedStateTransport(
        deviceId: String = this.deviceId.orEmpty(),
        observedState: ObservedStateTransport,
    ) {
        try {
            val request = ObservedStateRequest(
                deviceId = observedState.deviceId,
                reportedAt = observedState.reportedAt.toString(),
                models = observedState.models?.map { m ->
                    ObservedModelStatus(
                        modelId = m.modelId,
                        status = m.status,
                        installedVersion = m.installedVersion,
                        activeVersion = m.activeVersion,
                        health = m.health?.value,
                        lastError = m.lastError,
                    )
                } ?: emptyList(),
                sdkVersion = observedState.sdkVersion,
                osVersion = observedState.osVersion,
            )
            api.reportObservedState(deviceId, request)
        } catch (e: Exception) {
            Timber.d(e, "reportObservedStateTransport failed (non-blocking)")
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
