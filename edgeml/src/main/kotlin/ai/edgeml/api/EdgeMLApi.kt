package ai.edgeml.api

import ai.edgeml.api.dto.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit API interface for EdgeML server communication.
 *
 * All endpoints require authentication via API key in the header.
 */
interface EdgeMLApi {

    // =========================================================================
    // Health Check
    // =========================================================================

    /**
     * Check server health status.
     */
    @GET("health")
    suspend fun healthCheck(): Response<HealthResponse>

    // =========================================================================
    // Device Registration & Assignment
    // =========================================================================

    /**
     * Register a new device with the server.
     */
    @POST("api/v1/devices/register")
    suspend fun registerDevice(
        @Body request: DeviceRegistrationRequest,
    ): Response<DeviceRegistrationResponse>

    /**
     * Send a heartbeat for a device.
     */
    @POST("api/v1/devices/{device_id}/heartbeat")
    suspend fun sendHeartbeat(
        @Path("device_id") deviceId: String,
        @Body request: HeartbeatRequest,
    ): Response<HeartbeatResponse>

    /**
     * Get groups that a device belongs to.
     */
    @GET("api/v1/devices/{device_id}/groups")
    suspend fun getDeviceGroups(
        @Path("device_id") deviceId: String,
    ): Response<GroupMembershipsResponse>

    /**
     * Get the resolved model version for a device.
     */
    @GET("devices/{device_id}/models/{model_id}/version")
    suspend fun getDeviceVersion(
        @Path("device_id") deviceId: String,
        @Path("model_id") modelId: String,
        @Query("include_bucket") includeBucket: Boolean = false,
    ): Response<VersionResolutionResponse>

    /**
     * Assign a device to a specific model version.
     */
    @POST("devices/{device_id}/models/{model_id}/assign")
    suspend fun assignDeviceToModel(
        @Path("device_id") deviceId: String,
        @Path("model_id") modelId: String,
        @Body request: AssignmentRequest,
    ): Response<Unit>

    /**
     * Get the device bucket for rollout calculations.
     */
    @GET("devices/{device_id}/models/{model_id}/bucket")
    suspend fun getDeviceBucket(
        @Path("device_id") deviceId: String,
        @Path("model_id") modelId: String,
    ): Response<Map<String, Any>>

    // =========================================================================
    // Model Catalog
    // =========================================================================

    /**
     * Get model metadata by ID.
     */
    @GET("api/v1/models/{model_id}")
    suspend fun getModel(
        @Path("model_id") modelId: String,
    ): Response<ModelResponse>

    /**
     * Get a specific version of a model.
     */
    @GET("api/v1/models/{model_id}/versions/{version}")
    suspend fun getModelVersion(
        @Path("model_id") modelId: String,
        @Path("version") version: String,
    ): Response<ModelVersionResponse>

    /**
     * Get the latest version of a model.
     */
    @GET("api/v1/models/{model_id}/versions/latest")
    suspend fun getLatestVersion(
        @Path("model_id") modelId: String,
        @Query("status") status: String? = "published",
    ): Response<ModelVersionResponse>

    /**
     * Get a pre-signed download URL for a model.
     */
    @GET("api/v1/models/{model_id}/versions/{version}/download")
    suspend fun getModelDownloadUrl(
        @Path("model_id") modelId: String,
        @Path("version") version: String,
        @Query("format") format: String = "tensorflow_lite",
    ): Response<ModelDownloadResponse>

    // =========================================================================
    // Training Events & Metrics
    // =========================================================================

    /**
     * Report a training event to the server.
     */
    @POST("api/v1/experiments/{experiment_id}/events")
    suspend fun reportTrainingEvent(
        @Path("experiment_id") experimentId: String,
        @Body request: TrainingEventRequest,
    ): Response<Unit>

    /**
     * Submit gradient updates to the server.
     */
    @POST("api/v1/experiments/{experiment_id}/gradients")
    suspend fun submitGradients(
        @Path("experiment_id") experimentId: String,
        @Body request: GradientUpdateRequest,
    ): Response<GradientUpdateResponse>

    // =========================================================================
    // Organization Settings
    // =========================================================================

    /**
     * Get device policy configuration for the organization.
     * Used to enforce server-side policy on the device.
     */
    @GET("api/v1/settings/org/{org_id}/device-policy")
    suspend fun getDevicePolicy(
        @Path("org_id") orgId: String,
    ): Response<DevicePolicyResponse>

    // =========================================================================
    // Model Download (direct URL)
    // =========================================================================

    /**
     * Download model file from a pre-signed URL.
     */
    @GET
    @Streaming
    suspend fun downloadModelFile(
        @Url downloadUrl: String,
    ): Response<ResponseBody>
}
