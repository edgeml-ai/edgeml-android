package ai.octomil.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// =========================================================================
// Round Management
// =========================================================================

/**
 * A federated learning round returned from the server.
 */
@Serializable
data class RoundAssignment(
    @SerialName("id")
    val id: String,
    @SerialName("org_id")
    val orgId: String,
    @SerialName("model_id")
    val modelId: String,
    @SerialName("version_id")
    val versionId: String,
    @SerialName("state")
    val state: String,
    @SerialName("min_clients")
    val minClients: Int,
    @SerialName("max_clients")
    val maxClients: Int,
    @SerialName("client_selection_strategy")
    val clientSelectionStrategy: String,
    @SerialName("aggregation_type")
    val aggregationType: String,
    @SerialName("timeout_minutes")
    val timeoutMinutes: Int,
    @SerialName("differential_privacy")
    val differentialPrivacy: Boolean = false,
    @SerialName("dp_epsilon")
    val dpEpsilon: Double? = null,
    @SerialName("dp_delta")
    val dpDelta: Double? = null,
    @SerialName("secure_aggregation")
    val secureAggregation: Boolean = false,
    @SerialName("secagg_threshold")
    val secaggThreshold: Int? = null,
    @SerialName("selected_client_count")
    val selectedClientCount: Int = 0,
    @SerialName("received_update_count")
    val receivedUpdateCount: Int = 0,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("client_selection_started_at")
    val clientSelectionStartedAt: String? = null,
    @SerialName("aggregation_completed_at")
    val aggregationCompletedAt: String? = null,
)

/**
 * Response wrapping a list of rounds.
 * The server may return a list directly or wrapped in a `rounds` field.
 */
@Serializable
data class RoundsListResponse(
    @SerialName("rounds")
    val rounds: List<RoundAssignment> = emptyList(),
)

// =========================================================================
// Secure Aggregation
// =========================================================================

/**
 * Request to join a SecAgg session for a round.
 */
@Serializable
data class SecAggKeyExchangeRequest(
    @SerialName("device_id")
    val deviceId: String,
)

/**
 * Response from joining a SecAgg session.
 */
@Serializable
data class SecAggSessionResponse(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("round_id")
    val roundId: String,
    @SerialName("threshold")
    val threshold: Int,
    @SerialName("total_clients")
    val totalClients: Int,
    @SerialName("participant_ids")
    val participantIds: List<String>,
    @SerialName("state")
    val state: String,
    @SerialName("client_index")
    val clientIndex: Int? = null,
    @SerialName("privacy_budget")
    val privacyBudget: Double? = null,
    @SerialName("key_length")
    val keyLength: Int? = null,
)

/**
 * Request to submit SecAgg shares to the server.
 */
@Serializable
data class SecAggShareSubmitRequest(
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("shares")
    val shares: Map<String, String>,
    @SerialName("verification_tag")
    val verificationTag: String,
)

/**
 * Response from submitting SecAgg shares.
 */
@Serializable
data class SecAggShareSubmitResponse(
    @SerialName("accepted")
    val accepted: Boolean,
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("message")
    val message: String? = null,
)

// =========================================================================
// Secure Aggregation Phase 2-3
// =========================================================================

/**
 * Request to submit masked model update during SecAgg Phase 2.
 */
@Serializable
data class SecAggMaskedInputRequest(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("masked_weights_data")
    val maskedWeightsData: String,
    @SerialName("sample_count")
    val sampleCount: Int,
    @SerialName("metrics")
    val metrics: Map<String, Double>? = null,
)

/**
 * Server response when requesting unmasking info during SecAgg Phase 3.
 */
@Serializable
data class SecAggUnmaskResponse(
    @SerialName("dropped_client_indices")
    val droppedClientIndices: List<Int>,
    @SerialName("unmasking_required")
    val unmaskingRequired: Boolean,
)

/**
 * Request to submit unmasking shares during SecAgg Phase 3.
 */
@Serializable
data class SecAggUnmaskRequest(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("unmask_data")
    val unmaskData: String,
)
