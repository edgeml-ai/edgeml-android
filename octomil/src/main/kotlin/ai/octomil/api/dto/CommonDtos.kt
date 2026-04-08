package ai.octomil.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Error response from the server.
 *
 * The [code] field carries the canonical error code string (e.g. "model_not_found")
 * from the server's structured error envelope. Use
 * [ai.octomil.errors.OctomilErrorCode.fromContractCode] to map it to the SDK enum.
 */
@Serializable
data class ErrorResponse(
    @SerialName("detail")
    val detail: String,
    @SerialName("status_code")
    val statusCode: Int? = null,
    @SerialName("code")
    val code: String? = null,
)

/**
 * Health check response.
 */
@Serializable
data class HealthResponse(
    @SerialName("status")
    val status: String,
    @SerialName("version")
    val version: String? = null,
    @SerialName("timestamp")
    val timestamp: String? = null,
)
