package ai.octomil.chat

import ai.octomil.ModelResolver
import ai.octomil.responses.OctomilResponses
import ai.octomil.responses.runtime.LLMRuntimeAdapter
import ai.octomil.responses.runtime.ModelRuntimeRegistry
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID

/**
 * OkHttp interceptor that routes OpenAI-compatible API calls to on-device inference.
 *
 * Add this to any OkHttp client to transparently redirect `/v1/chat/completions`
 * requests to Octomil's on-device LLM, while passing all other requests through.
 *
 * ## Usage
 *
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(OctomilInterceptor(context))
 *     .build()
 *
 * // Now any library using this client that calls /v1/chat/completions
 * // will run inference on-device instead of hitting a remote server.
 * ```
 *
 * For apps using the OpenAI Kotlin client (`com.aallam.openai`), pass this
 * OkHttp client as the HTTP engine:
 *
 * ```kotlin
 * val openai = OpenAI(
 *     token = "unused",
 *     httpClient = HttpClient(OkHttp) {
 *         engine { preconfigured = client }
 *     }
 * )
 * // openai.chatCompletion(...) now runs on-device
 * ```
 *
 * @param context Android context for model resolution and engine creation.
 * @param resolver Model resolver for finding model files. Defaults to the
 *   standard paired → assets → cache chain.
 */
class OctomilInterceptor(
    private val context: Context,
    private val resolver: ModelResolver = ModelResolver.default(),
) : Interceptor {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath

        // Intercept /v1/models (GET)
        if (path.endsWith("/models") && request.method == "GET") {
            return handleModelsEndpoint(request)
        }

        // Intercept /v1/chat/completions (POST)
        if (!path.endsWith("/chat/completions") || request.method != "POST") {
            return chain.proceed(request)
        }

        val body = request.body ?: return chain.proceed(request)
        val bodyString = okio.Buffer().also { body.writeTo(it) }.readUtf8()

        return try {
            val openAiRequest = json.decodeFromString<OpenAIRequest>(bodyString)
            val completion = runBlocking { handleChatCompletion(openAiRequest) }
            val responseJson = json.encodeToString(OpenAIResponse.serializer(), completion)

            Response.Builder()
                .code(200)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .message("OK")
                .body(responseJson.toResponseBody("application/json".toMediaType()))
                .build()
        } catch (e: Exception) {
            val errorJson = """{"error":{"message":"${e.message?.replace("\"", "\\\"")}","type":"octomil_error"}}"""
            Response.Builder()
                .code(500)
                .protocol(Protocol.HTTP_1_1)
                .request(request)
                .message("Internal Error")
                .body(errorJson.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private suspend fun handleChatCompletion(request: OpenAIRequest): OpenAIResponse {
        val modelName = request.model
        val modelFile = resolver.resolve(context, modelName)
        val llmRuntime = modelFile?.let { file ->
            LLMRuntimeRegistry.factory?.invoke(file)
        }
        if (llmRuntime != null) {
            val adapter = LLMRuntimeAdapter(llmRuntime)
            ModelRuntimeRegistry.register(modelName) { adapter }
        }
        val responses = OctomilResponses()
        val chat = OctomilChat(modelName = modelName, responses = responses)

        val messages = request.messages.map { msg ->
            ChatMessage(
                role = when (msg.role) {
                    "system" -> ChatMessage.Role.SYSTEM
                    "assistant" -> ChatMessage.Role.ASSISTANT
                    "tool" -> ChatMessage.Role.TOOL
                    else -> ChatMessage.Role.USER
                },
                content = msg.content,
                toolCallId = msg.toolCallId,
            )
        }

        val chatRequest = ChatRequest(
            messages = messages,
            temperature = request.temperature ?: 0.7f,
            maxTokens = request.maxTokens ?: 512,
            topP = request.topP ?: 1.0f,
        )

        val result = chat.create(chatRequest)

        return OpenAIResponse(
            id = result.id,
            `object` = "chat.completion",
            created = result.created,
            model = modelName,
            choices = result.choices.map { choice ->
                OpenAIResponse.Choice(
                    index = choice.index,
                    message = OpenAIResponse.Message(
                        role = choice.message.role.name.lowercase(),
                        content = choice.message.content,
                    ),
                    finishReason = choice.finishReason,
                )
            },
            usage = result.usage?.let { usage ->
                OpenAIResponse.Usage(
                    promptTokens = usage.promptTokens,
                    completionTokens = usage.completionTokens,
                    totalTokens = usage.totalTokens,
                )
            },
        )
    }

    private fun handleModelsEndpoint(request: okhttp3.Request): Response {
        val modelsDir = java.io.File(context.filesDir, "octomil_models")
        val models = if (modelsDir.exists()) {
            modelsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?: emptyList()
        } else {
            emptyList()
        }

        val modelsJson = buildString {
            append("""{"object":"list","data":[""")
            models.forEachIndexed { index, name ->
                if (index > 0) append(",")
                append("""{"id":"$name","object":"model","owned_by":"octomil"}""")
            }
            append("]}")
        }

        return Response.Builder()
            .code(200)
            .protocol(Protocol.HTTP_1_1)
            .request(request)
            .message("OK")
            .body(modelsJson.toResponseBody("application/json".toMediaType()))
            .build()
    }

    // --- Wire types matching OpenAI's JSON exactly ---

    @Serializable
    internal data class OpenAIRequest(
        val model: String,
        val messages: List<OpenAIMessage>,
        val temperature: Float? = null,
        @SerialName("max_tokens")
        val maxTokens: Int? = null,
        @SerialName("top_p")
        val topP: Float? = null,
        val stream: Boolean? = null,
    )

    @Serializable
    internal data class OpenAIMessage(
        val role: String,
        val content: String? = null,
        @SerialName("tool_call_id")
        val toolCallId: String? = null,
    )

    @Serializable
    internal data class OpenAIResponse(
        val id: String,
        val `object`: String,
        val created: Long,
        val model: String,
        val choices: List<Choice>,
        val usage: Usage? = null,
    ) {
        @Serializable
        data class Choice(
            val index: Int,
            val message: Message,
            @SerialName("finish_reason")
            val finishReason: String? = null,
        )

        @Serializable
        data class Message(
            val role: String,
            val content: String? = null,
        )

        @Serializable
        data class Usage(
            @SerialName("prompt_tokens")
            val promptTokens: Int,
            @SerialName("completion_tokens")
            val completionTokens: Int,
            @SerialName("total_tokens")
            val totalTokens: Int,
        )
    }
}
