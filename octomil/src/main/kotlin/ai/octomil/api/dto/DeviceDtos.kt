package ai.octomil.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request to register a device with the Octomil server.
 * Field names aligned with server API contract.
 */
@Serializable
data class DeviceRegistrationRequest(
    @SerialName("device_identifier")
    val deviceIdentifier: String,
    @SerialName("org_id")
    val orgId: String,
    @SerialName("platform")
    val platform: String = "android",
    @SerialName("os_version")
    val osVersion: String,
    @SerialName("sdk_version")
    val sdkVersion: String,
    @SerialName("manufacturer")
    val manufacturer: String? = null,
    @SerialName("model")
    val model: String? = null,
    @SerialName("locale")
    val locale: String? = null,
    @SerialName("region")
    val region: String? = null,
    @SerialName("app_version")
    val appVersion: String? = null,
    @SerialName("capabilities")
    val capabilities: DeviceCapabilities? = null,
    @SerialName("timezone")
    val timezone: String? = null,
    @SerialName("metadata")
    val metadata: Map<String, String>? = null,
    @SerialName("device_info")
    val deviceInfo: DeviceInfoRequest? = null,
    @SerialName("cpu_architecture")
    val cpuArchitecture: String? = null,
    @SerialName("gpu_available")
    val gpuAvailable: Boolean? = null,
    @SerialName("total_memory_mb")
    val totalMemoryMb: Long? = null,
    @SerialName("available_storage_mb")
    val availableStorageMb: Long? = null,
    @SerialName("battery_pct")
    val batteryPct: Int? = null,
    @SerialName("charging")
    val charging: Boolean? = null,
)

/**
 * Device hardware capabilities for registration.
 */
@Serializable
data class DeviceCapabilities(
    @SerialName("nnapi_available")
    val nnapiAvailable: Boolean = false,
)

/**
 * Device hardware info for registration (nested under device_info).
 */
@Serializable
data class DeviceInfoRequest(
    @SerialName("manufacturer")
    val manufacturer: String? = null,
    @SerialName("model")
    val model: String? = null,
    @SerialName("cpu_architecture")
    val cpuArchitecture: String? = null,
    @SerialName("gpu_available")
    val gpuAvailable: Boolean? = null,
    @SerialName("total_memory_mb")
    val totalMemoryMb: Long? = null,
    @SerialName("available_storage_mb")
    val availableStorageMb: Long? = null,
)

/**
 * Response from device registration.
 * Server returns the device record with server-assigned UUID.
 */
@Serializable
data class DeviceRegistrationResponse(
    @SerialName("id")
    val id: String,
    @SerialName("device_identifier")
    val deviceIdentifier: String,
    @SerialName("org_id")
    val orgId: String,
    @SerialName("platform")
    val platform: String,
    @SerialName("status")
    val status: String,
    @SerialName("manufacturer")
    val manufacturer: String? = null,
    @SerialName("model")
    val model: String? = null,
    @SerialName("os_version")
    val osVersion: String? = null,
    @SerialName("sdk_version")
    val sdkVersion: String? = null,
    @SerialName("locale")
    val locale: String? = null,
    @SerialName("region")
    val region: String? = null,
    @SerialName("last_heartbeat")
    val lastHeartbeat: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("api_token")
    val apiToken: String? = null,
    @SerialName("app_version")
    val appVersion: String? = null,
    @SerialName("timezone")
    val timezone: String? = null,
    @SerialName("capabilities")
    val capabilities: Map<String, String>? = null,
    @SerialName("heartbeat_interval_seconds")
    val heartbeatIntervalSeconds: Int? = null,
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
)

// =========================================================================
// Heartbeat
// =========================================================================

/**
 * Request to send device heartbeat.
 */
@Serializable
data class HeartbeatRequest(
    @SerialName("sdk_version")
    val sdkVersion: String? = null,
    @SerialName("os_version")
    val osVersion: String? = null,
    @SerialName("app_version")
    val appVersion: String? = null,
    @SerialName("battery_pct")
    val batteryPct: Int? = null,
    @SerialName("charging")
    val charging: Boolean? = null,
    @SerialName("available_storage_mb")
    val availableStorageMb: Long? = null,
    @SerialName("available_memory_mb")
    val availableMemoryMb: Long? = null,
    @SerialName("network_type")
    val networkType: String? = null,
)

/**
 * Response from heartbeat request.
 */
@Serializable
data class HeartbeatResponse(
    @SerialName("acknowledged")
    val acknowledged: Boolean,
    @SerialName("server_time")
    val serverTime: String? = null,
    @SerialName("id")
    val id: String? = null,
    @SerialName("device_identifier")
    val deviceIdentifier: String? = null,
    @SerialName("status")
    val status: String? = null,
    @SerialName("last_heartbeat")
    val lastHeartbeat: String? = null,
)

// =========================================================================
// Device Groups
// =========================================================================

/**
 * Device group information.
 */
@Serializable
data class DeviceGroup(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("description")
    val description: String? = null,
    @SerialName("group_type")
    val groupType: String,
    @SerialName("is_active")
    val isActive: Boolean,
    @SerialName("device_count")
    val deviceCount: Int,
    @SerialName("tags")
    val tags: List<String>? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

/**
 * Response containing device groups.
 */
@Serializable
data class DeviceGroupsResponse(
    @SerialName("groups")
    val groups: List<DeviceGroup>,
    @SerialName("count")
    val count: Int,
)

/**
 * Group membership information.
 */
@Serializable
data class GroupMembership(
    @SerialName("id")
    val id: String,
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("group_id")
    val groupId: String,
    @SerialName("group_name")
    val groupName: String? = null,
    @SerialName("membership_type")
    val membershipType: String,
    @SerialName("created_at")
    val createdAt: String,
)

/**
 * Response containing device group memberships.
 */
@Serializable
data class GroupMembershipsResponse(
    @SerialName("memberships")
    val memberships: List<GroupMembership>,
    @SerialName("count")
    val count: Int,
)

/**
 * Device policy configuration from organization settings.
 * Controls device training behavior based on server-side policy.
 */
@Serializable
data class DevicePolicyResponse(
    @SerialName("battery_threshold")
    val batteryThreshold: Int,
    @SerialName("network_policy")
    val networkPolicy: String,
    @SerialName("sampling_policy")
    val samplingPolicy: String? = null,
    @SerialName("training_window")
    val trainingWindow: String? = null,
)
