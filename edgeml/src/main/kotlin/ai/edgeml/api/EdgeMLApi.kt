package ai.edgeml.api

import ai.edgeml.api.dto.AssignmentRequest
import ai.edgeml.api.dto.DevicePolicyResponse
import ai.edgeml.api.dto.DeviceRegistrationRequest
import ai.edgeml.api.dto.DeviceRegistrationResponse
import ai.edgeml.api.dto.GradientUpdateRequest
import ai.edgeml.api.dto.GradientUpdateResponse
import ai.edgeml.api.dto.GroupMembershipsResponse
import ai.edgeml.api.dto.HealthResponse
import ai.edgeml.api.dto.HeartbeatRequest
import ai.edgeml.api.dto.HeartbeatResponse
import ai.edgeml.api.dto.InferenceEventRequest
import ai.edgeml.api.dto.InferenceEventResponse
import ai.edgeml.api.dto.ModelDownloadResponse
import ai.edgeml.api.dto.ModelResponse
import ai.edgeml.api.dto.ModelUpdateInfo
import ai.edgeml.api.dto.ModelVersionResponse
import ai.edgeml.api.dto.RoundAssignment
import ai.edgeml.api.dto.SecAggKeyExchangeRequest
import ai.edgeml.api.dto.SecAggMaskedInputRequest
import ai.edgeml.api.dto.SecAggSessionResponse
import ai.edgeml.api.dto.SecAggShareSubmitRequest
import ai.edgeml.api.dto.SecAggShareSubmitResponse
import ai.edgeml.api.dto.SecAggUnmaskRequest
import ai.edgeml.api.dto.SecAggUnmaskResponse
import ai.edgeml.api.dto.TrainingEventRequest
import ai.edgeml.api.dto.VersionResolutionResponse
import ai.edgeml.api.dto.WeightUploadRequest
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

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
     * Get full device information from the server.
     */
    @GET("api/v1/devices/{device_id}")
    suspend fun getDeviceInfo(
        @Path("device_id") deviceId: String,
    ): Response<DeviceRegistrationResponse>

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

    /**
     * Get device-specific optimized runtime config (e.g., MNN settings).
     */
    @GET("api/v1/models/{modelId}/optimized-config/{deviceType}")
    suspend fun getDeviceConfig(
        @Path("modelId") modelId: String,
        @Path("deviceType") deviceType: String,
    ): Response<Map<String, Any>>

    /**
     * Check for model updates.
     */
    @GET("api/v1/models/{model_id}/updates")
    suspend fun checkForUpdates(
        @Path("model_id") modelId: String,
        @Query("current_version") currentVersion: String,
    ): Response<ModelUpdateInfo>

    // =========================================================================
    // Weight Upload
    // =========================================================================

    /**
     * Upload trained weights to the server.
     */
    @POST("api/v1/training/weights")
    suspend fun uploadWeights(
        @Body request: WeightUploadRequest,
    ): Response<Unit>

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
    // Inference Events
    // =========================================================================

    /**
     * Report a streaming inference event to the server.
     */
    @POST("api/v1/inference/events")
    suspend fun reportInferenceEvent(
        @Body request: InferenceEventRequest,
    ): Response<InferenceEventResponse>

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
    // Round Management
    // =========================================================================

    /**
     * List active training rounds for a model, optionally filtered by device.
     */
    @GET("api/v1/training/rounds")
    suspend fun listRounds(
        @Query("model_id") modelId: String,
        @Query("state") state: String? = null,
        @Query("device_id") deviceId: String? = null,
    ): Response<List<RoundAssignment>>

    /**
     * Get details for a specific training round.
     */
    @GET("api/v1/training/rounds/{round_id}")
    suspend fun getRound(
        @Path("round_id") roundId: String,
    ): Response<RoundAssignment>

    // =========================================================================
    // Secure Aggregation
    // =========================================================================

    /**
     * Join a SecAgg session for a federated learning round.
     */
    @POST("api/v1/training/rounds/{round_id}/secagg/join")
    suspend fun joinSecAggSession(
        @Path("round_id") roundId: String,
        @Body request: SecAggKeyExchangeRequest,
    ): Response<SecAggSessionResponse>

    /**
     * Submit Shamir secret shares for a SecAgg session.
     */
    @POST("api/v1/training/secagg/{session_id}/shares")
    suspend fun submitSecAggShares(
        @Path("session_id") sessionId: String,
        @Body request: SecAggShareSubmitRequest,
    ): Response<SecAggShareSubmitResponse>

    /**
     * Submit masked model update during SecAgg Phase 2.
     */
    @POST("api/v1/secagg/masked-input")
    suspend fun submitSecAggMaskedInput(
        @Body request: SecAggMaskedInputRequest,
    ): Response<Unit>

    /**
     * Get unmasking info during SecAgg Phase 3.
     */
    @GET("api/v1/secagg/unmask")
    suspend fun getSecAggUnmaskInfo(
        @Query("session_id") sessionId: String,
        @Query("device_id") deviceId: String,
    ): Response<SecAggUnmaskResponse>

    /**
     * Submit unmasking shares during SecAgg Phase 3.
     */
    @POST("api/v1/secagg/unmask")
    suspend fun submitSecAggUnmask(
        @Body request: SecAggUnmaskRequest,
    ): Response<Unit>

    // =========================================================================
    // Federated Analytics
    // =========================================================================

    /**
     * Run descriptive statistics across groups in a federation.
     */
    @POST("api/v1/federations/{federation_id}/analytics/descriptive")
    suspend fun runDescriptive(
        @Path("federation_id") federationId: String,
        @Body request: ai.edgeml.analytics.DescriptiveRequest,
    ): Response<ai.edgeml.analytics.DescriptiveResult>

    /**
     * Run a two-sample t-test between two groups.
     */
    @POST("api/v1/federations/{federation_id}/analytics/t-test")
    suspend fun runTTest(
        @Path("federation_id") federationId: String,
        @Body request: ai.edgeml.analytics.TTestRequest,
    ): Response<ai.edgeml.analytics.TTestResult>

    /**
     * Run a chi-square test of independence.
     */
    @POST("api/v1/federations/{federation_id}/analytics/chi-square")
    suspend fun runChiSquare(
        @Path("federation_id") federationId: String,
        @Body request: ai.edgeml.analytics.ChiSquareRequest,
    ): Response<ai.edgeml.analytics.ChiSquareResult>

    /**
     * Run one-way ANOVA across groups.
     */
    @POST("api/v1/federations/{federation_id}/analytics/anova")
    suspend fun runAnova(
        @Path("federation_id") federationId: String,
        @Body request: ai.edgeml.analytics.AnovaRequest,
    ): Response<ai.edgeml.analytics.AnovaResult>

    /**
     * List past analytics queries for a federation.
     */
    @GET("api/v1/federations/{federation_id}/analytics/queries")
    suspend fun listAnalyticsQueries(
        @Path("federation_id") federationId: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): Response<ai.edgeml.analytics.AnalyticsQueryListResponse>

    /**
     * Get a specific analytics query by ID.
     */
    @GET("api/v1/federations/{federation_id}/analytics/queries/{query_id}")
    suspend fun getAnalyticsQuery(
        @Path("federation_id") federationId: String,
        @Path("query_id") queryId: String,
    ): Response<ai.edgeml.analytics.AnalyticsQuery>

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
