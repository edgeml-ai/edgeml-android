package ai.octomil.responses.runtime

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Cloud-based model runtime that delegates inference to a remote server
 * using the OpenAI-compatible `/v1/chat/completions` endpoint.
 *
 * For non-streaming requests, posts with `"stream": false` and parses the
 * full JSON response. For streaming, posts with `"stream": true` and
 * consumes SSE `data:` lines, emitting [RuntimeChunk] values.
 *
 * @param serverUrl Base URL of the API server (e.g. `https://api.octomil.com`).
 * @param apiKey    Bearer token for authentication. Null disables the Authorization header.
 * @param model     Model identifier sent in the request body. Defaults to `"default"`.
 * @param httpClient OkHttp client instance; allows injection for testing.
 */
class CloudModelRuntime(
    private val serverUrl: String = "https://api.octomil.com",
    private val apiKey: String? = null,
    private val model: String = "default",
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : ModelRuntime {
    override val capabilities = RuntimeCapabilities(
        supportsToolCalls = true,
        supportsStructuredOutput = true,
        supportsStreaming = true,
    )

    private val json = Json { ignoreUnknownKeys = true }

    // =========================================================================
    // Non-streaming run
    // =========================================================================

    override suspend fun run(request: RuntimeRequest): RuntimeResponse =
        withContext(Dispatchers.IO) {
            val body = buildRequestBody(request, stream = false)
            val httpRequest = buildHttpRequest(body)

            val response = try {
                httpClient.newCall(httpRequest).execute()
            } catch (e: SocketTimeoutException) {
                throw OctomilException(OctomilErrorCode.REQUEST_TIMEOUT, "Request timed out", e)
            } catch (e: IOException) {
                throw OctomilException(OctomilErrorCode.NETWORK_UNAVAILABLE, "Network error: ${e.message}", e)
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw OctomilException(
                        OctomilErrorCode.fromHttpStatus(resp.code),
                        "Cloud inference failed: HTTP ${resp.code}",
                    )
                }

                val responseBody = resp.body?.string()
                    ?: throw OctomilException(
                        OctomilErrorCode.SERVER_ERROR,
                        "Empty response body from cloud inference",
                    )

                parseNonStreamingResponse(responseBody)
            }
        }

    // =========================================================================
    // Streaming
    // =========================================================================

    override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = flow {
        val body = buildRequestBody(request, stream = true)
        val httpRequest = buildHttpRequest(body)

        val response = try {
            httpClient.newCall(httpRequest).execute()
        } catch (e: SocketTimeoutException) {
            throw OctomilException(OctomilErrorCode.REQUEST_TIMEOUT, "Request timed out", e)
        } catch (e: IOException) {
            throw OctomilException(OctomilErrorCode.NETWORK_UNAVAILABLE, "Network error: ${e.message}", e)
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                throw OctomilException(
                    OctomilErrorCode.fromHttpStatus(resp.code),
                    "Cloud streaming inference failed: HTTP ${resp.code}",
                )
            }

            val bodyStream = resp.body?.byteStream()
                ?: throw OctomilException(
                    OctomilErrorCode.SERVER_ERROR,
                    "Empty response body from streaming inference",
                )

            BufferedReader(InputStreamReader(bodyStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val chunk = parseSSELine(line!!)
                    if (chunk != null) {
                        emit(chunk)
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        // OkHttpClient is shared and does not need explicit cleanup here.
    }

    // =========================================================================
    // Request building
    // =========================================================================

    internal fun buildRequestBody(request: RuntimeRequest, stream: Boolean): String {
        val root = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", request.prompt)
                })
            })
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature)
            put("top_p", request.topP)
            put("stream", stream)

            request.stop?.let { stopList ->
                put("stop", buildJsonArray {
                    stopList.forEach { add(JsonPrimitive(it)) }
                })
            }

            request.toolDefinitions?.let { tools ->
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                tool.parametersSchema?.let { schema ->
                                    put("parameters", json.parseToJsonElement(schema))
                                }
                            })
                        })
                    }
                })
            }

            request.jsonSchema?.let { schema ->
                put("response_format", buildJsonObject {
                    put("type", "json_schema")
                    put("json_schema", buildJsonObject {
                        put("name", "response")
                        put("schema", json.parseToJsonElement(schema))
                    })
                })
            }
        }

        return root.toString()
    }

    private fun buildHttpRequest(body: String): Request {
        val url = "${serverUrl.trimEnd('/')}/v1/chat/completions"
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("User-Agent", "octomil-android/1.0")

        apiKey?.let { key ->
            builder.header("Authorization", "Bearer $key")
        }

        return builder.build()
    }

    // =========================================================================
    // Response parsing — non-streaming
    // =========================================================================

    internal fun parseNonStreamingResponse(body: String): RuntimeResponse {
        val parsed = json.decodeFromString<ChatCompletionResponse>(body)
        val choice = parsed.choices.firstOrNull()
            ?: return RuntimeResponse(text = "", finishReason = "stop")

        val text = choice.message?.content ?: ""
        val toolCalls = choice.message?.toolCalls?.map { tc ->
            RuntimeToolCall(
                id = tc.id,
                name = tc.function.name,
                arguments = tc.function.arguments,
            )
        }
        val usage = parsed.usage?.let {
            RuntimeUsage(
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens,
            )
        }

        return RuntimeResponse(
            text = text,
            toolCalls = toolCalls,
            finishReason = choice.finishReason ?: "stop",
            usage = usage,
        )
    }

    // =========================================================================
    // Response parsing — SSE streaming
    // =========================================================================

    internal fun parseSSELine(line: String): RuntimeChunk? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return null

        val dataStr = trimmed.removePrefix("data:").trim()
        if (dataStr.isEmpty() || dataStr == "[DONE]") return null

        return try {
            val parsed = json.decodeFromString<ChatCompletionChunkResponse>(dataStr)
            val delta = parsed.choices.firstOrNull()?.delta

            val toolCallDelta = delta?.toolCalls?.firstOrNull()?.let { tc ->
                RuntimeToolCallDelta(
                    index = tc.index,
                    id = tc.id,
                    name = tc.function?.name,
                    argumentsDelta = tc.function?.arguments,
                )
            }

            val usage = parsed.usage?.let {
                RuntimeUsage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens,
                )
            }

            val finishReason = parsed.choices.firstOrNull()?.finishReason

            RuntimeChunk(
                text = delta?.content,
                toolCallDelta = toolCallDelta,
                finishReason = finishReason,
                usage = usage,
            )
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // Serialization models (OpenAI-compatible)
    // =========================================================================

    @Serializable
    internal data class ChatCompletionResponse(
        val id: String? = null,
        val choices: List<Choice> = emptyList(),
        val usage: UsageDto? = null,
    )

    @Serializable
    internal data class Choice(
        val index: Int = 0,
        val message: MessageDto? = null,
        @SerialName("finish_reason")
        val finishReason: String? = null,
    )

    @Serializable
    internal data class MessageDto(
        val role: String? = null,
        val content: String? = null,
        @SerialName("tool_calls")
        val toolCalls: List<ToolCallDto>? = null,
    )

    @Serializable
    internal data class ToolCallDto(
        val id: String = "",
        val type: String = "function",
        val function: FunctionDto,
    )

    @Serializable
    internal data class FunctionDto(
        val name: String = "",
        val arguments: String = "",
    )

    @Serializable
    internal data class UsageDto(
        @SerialName("prompt_tokens")
        val promptTokens: Int = 0,
        @SerialName("completion_tokens")
        val completionTokens: Int = 0,
        @SerialName("total_tokens")
        val totalTokens: Int = 0,
    )

    // --- Streaming chunk models ---

    @Serializable
    internal data class ChatCompletionChunkResponse(
        val id: String? = null,
        val choices: List<ChunkChoice> = emptyList(),
        val usage: UsageDto? = null,
    )

    @Serializable
    internal data class ChunkChoice(
        val index: Int = 0,
        val delta: DeltaDto? = null,
        @SerialName("finish_reason")
        val finishReason: String? = null,
    )

    @Serializable
    internal data class DeltaDto(
        val role: String? = null,
        val content: String? = null,
        @SerialName("tool_calls")
        val toolCalls: List<ChunkToolCallDto>? = null,
    )

    @Serializable
    internal data class ChunkToolCallDto(
        val index: Int = 0,
        val id: String? = null,
        val type: String? = null,
        val function: ChunkFunctionDto? = null,
    )

    @Serializable
    internal data class ChunkFunctionDto(
        val name: String? = null,
        val arguments: String? = null,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
