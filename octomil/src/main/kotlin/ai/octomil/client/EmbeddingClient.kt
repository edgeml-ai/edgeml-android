package ai.octomil.client

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Token usage statistics from the embeddings endpoint.
 */
data class EmbeddingUsage(
    val promptTokens: Int,
    val totalTokens: Int,
)

/**
 * Result returned by [EmbeddingClient.embed].
 */
data class EmbeddingResult(
    val embeddings: List<List<Double>>,
    val model: String,
    val usage: EmbeddingUsage,
)

/**
 * Calls `POST /api/v1/embeddings` and returns dense vectors.
 *
 * Usage:
 * ```kotlin
 * val client = EmbeddingClient(
 *     serverUrl = "https://api.octomil.com",
 *     apiKey = "your-key"
 * )
 * val result = client.embed("nomic-embed-text", input = "Hello, world!")
 * println(result.embeddings) // [[0.1, 0.2, ...]]
 * ```
 */
class EmbeddingClient(
    private val serverUrl: String,
    private val apiKey: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Generate embeddings for a single string.
     */
    fun embed(modelId: String, input: String): EmbeddingResult {
        val payload = buildPayload(modelId, singleInput = input, batchInput = null)
        return sendRequest(payload)
    }

    /**
     * Generate embeddings for multiple strings.
     */
    fun embed(modelId: String, input: List<String>): EmbeddingResult {
        val payload = buildPayload(modelId, singleInput = null, batchInput = input)
        return sendRequest(payload)
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private fun sendRequest(payload: String): EmbeddingResult {
        val url = "${serverUrl.trimEnd('/')}/api/v1/embeddings"
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("User-Agent", "octomil-android/1.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw OctomilException(
                    OctomilErrorCode.fromHttpStatus(response.code),
                    "Embeddings request failed: HTTP ${response.code}",
                )
            }

            val body = response.body?.string()
                ?: throw OctomilException(OctomilErrorCode.SERVER_ERROR, "Embeddings request returned empty body")

            val parsed = json.decodeFromString<EmbeddingResponse>(body)

            return EmbeddingResult(
                embeddings = parsed.data.map { it.embedding },
                model = parsed.model,
                usage = EmbeddingUsage(
                    promptTokens = parsed.usage.promptTokens,
                    totalTokens = parsed.usage.totalTokens,
                ),
            )
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// =========================================================================
// Internal serialization models
// =========================================================================

@Serializable
internal data class EmbeddingResponse(
    val data: List<EmbeddingDataItem>,
    val model: String,
    val usage: EmbeddingUsageResponse,
)

@Serializable
internal data class EmbeddingDataItem(
    val embedding: List<Double>,
    val index: Int,
)

@Serializable
internal data class EmbeddingUsageResponse(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

private fun buildPayload(
    modelId: String,
    singleInput: String?,
    batchInput: List<String>?,
): String {
    val parts = mutableListOf<String>()
    parts.add(""""model_id":${Json.encodeToString(modelId)}""")
    if (singleInput != null) {
        parts.add(""""input":${Json.encodeToString(singleInput)}""")
    }
    if (batchInput != null) {
        val inputsJson = batchInput.joinToString(",", "[", "]") { str ->
            Json.encodeToString(str)
        }
        parts.add(""""input":$inputsJson""")
    }
    return "{${parts.joinToString(",")}}"
}
