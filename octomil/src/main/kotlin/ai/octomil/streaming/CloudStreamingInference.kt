package ai.octomil.streaming

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * A single token received from the cloud streaming inference endpoint.
 */
data class StreamToken(
    val token: String,
    val done: Boolean,
    val provider: String? = null,
    val latencyMs: Double? = null,
    val sessionId: String? = null,
)

/**
 * Consumes SSE responses from `POST /api/v1/inference/stream` and
 * emits [StreamToken] values via a Kotlin [Flow].
 *
 * Usage:
 * ```kotlin
 * val client = CloudStreamingClient(
 *     serverUrl = "https://api.octomil.com/api/v1",
 *     apiKey = "your-key"
 * )
 * client.streamInference("phi-4-mini", input = "Hello")
 *     .collect { token -> print(token.token) }
 * ```
 */
class CloudStreamingClient(
    private val serverUrl: String,
    private val apiKey: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Stream tokens from the cloud inference endpoint using a string prompt.
     */
    fun streamInference(
        modelId: String,
        input: String,
        parameters: Map<String, Any>? = null,
    ): Flow<StreamToken> = streamInternal(
        buildPayload(modelId, inputData = input, messages = null, parameters = parameters),
    )

    /**
     * Stream tokens from the cloud inference endpoint using chat-style messages.
     */
    fun streamInference(
        modelId: String,
        messages: List<Map<String, String>>,
        parameters: Map<String, Any>? = null,
    ): Flow<StreamToken> = streamInternal(
        buildPayload(modelId, inputData = null, messages = messages, parameters = parameters),
    )

    // =========================================================================
    // Internal
    // =========================================================================

    private fun streamInternal(payload: String): Flow<StreamToken> = flow {
        val url = "${serverUrl.trimEnd('/')}/inference/stream"
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("User-Agent", "octomil-android/1.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw OctomilException(
                    OctomilErrorCode.fromHttpStatus(response.code),
                    "Cloud streaming inference failed: HTTP ${response.code}",
                )
            }

            val body = response.body
                ?: throw OctomilException(OctomilErrorCode.SERVER_ERROR, "Cloud streaming inference returned empty body")

            BufferedReader(InputStreamReader(body.byteStream())).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val token = parseSSELine(line!!)
                    if (token != null) {
                        emit(token)
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Parse a single SSE line into a [StreamToken], or `null` if the
         * line is not a valid `data:` event.
         */
        fun parseSSELine(line: String): StreamToken? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return null

            val dataStr = trimmed.removePrefix("data:").trim()
            if (dataStr.isEmpty()) return null

            return try {
                val parsed = Json.decodeFromString<SSEData>(dataStr)
                StreamToken(
                    token = parsed.token ?: "",
                    done = parsed.done ?: false,
                    provider = parsed.provider,
                    latencyMs = parsed.latencyMs,
                    sessionId = parsed.sessionId,
                )
            } catch (_: Exception) {
                Timber.w("Failed to parse SSE data: $dataStr")
                null
            }
        }
    }
}

// =========================================================================
// Internal serialization helpers
// =========================================================================

@Serializable
private data class SSEData(
    val token: String? = null,
    val done: Boolean? = null,
    val provider: String? = null,
    @SerialName("latency_ms") val latencyMs: Double? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

private fun buildPayload(
    modelId: String,
    inputData: String?,
    messages: List<Map<String, String>>?,
    parameters: Map<String, Any>?,
): String {
    val parts = mutableListOf<String>()
    parts.add(""""model_id":${Json.encodeToString(modelId)}""")
    if (inputData != null) {
        parts.add(""""input_data":${Json.encodeToString(inputData)}""")
    }
    if (messages != null) {
        val msgsJson = messages.joinToString(",", "[", "]") { msg ->
            msg.entries.joinToString(",", "{", "}") { (k, v) ->
                """"$k":${Json.encodeToString(v)}"""
            }
        }
        parts.add(""""messages":$msgsJson""")
    }
    if (parameters != null && parameters.isNotEmpty()) {
        val paramsJson = parameters.entries.joinToString(",", "{", "}") { (k, v) ->
            """"$k":$v"""
        }
        parts.add(""""parameters":$paramsJson""")
    }
    return "{${parts.joinToString(",")}}"
}
