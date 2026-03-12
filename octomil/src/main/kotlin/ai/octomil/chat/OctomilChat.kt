package ai.octomil.chat

import ai.octomil.inference.InferenceChunk
import ai.octomil.inference.Modality
import ai.octomil.inference.StreamingInferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * OpenAI-compatible chat interface for on-device inference.
 *
 * Drop-in replacement for OpenAI/Groq client calls — same message format,
 * same response shapes, same streaming semantics. Runs entirely on-device.
 *
 * ```kotlin
 * val chat = Octomil.chat(context, "phi-4-mini")
 *
 * // Non-streaming:
 * val response = chat.create(ChatRequest(
 *     messages = listOf(ChatMessage.user("What is ML?")),
 * ))
 * println(response.choices[0].message.content)
 *
 * // Streaming:
 * chat.stream(ChatRequest(
 *     messages = listOf(ChatMessage.user("What is ML?")),
 * )).collect { chunk ->
 *     print(chunk.choices[0].delta.content.orEmpty())
 * }
 * ```
 *
 * @property modelName The logical model name.
 * @property engine The underlying inference engine.
 */
class OctomilChat internal constructor(
    val modelName: String,
    private val engine: StreamingInferenceEngine,
    private val runtime: LLMRuntime? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Create a chat completion (non-streaming).
     *
     * Collects all tokens and returns the full response.
     * Equivalent to OpenAI's `client.chat.completions.create(stream=false)`.
     */
    suspend fun create(request: ChatRequest): ChatCompletion {
        val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
        val prompt = formatPrompt(request)
        val config = GenerateConfig(
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            topP = request.topP,
            stop = request.stop,
        )
        val tokens = mutableListOf<String>()
        var tokenCount = 0

        val tokenFlow = runtime?.generate(prompt, config)
            ?: engine.generate(prompt, Modality.TEXT).let { engineFlow ->
                kotlinx.coroutines.flow.flow {
                    engineFlow.collect { chunk ->
                        emit(chunk.data.toString(Charsets.UTF_8))
                    }
                }
            }

        tokenFlow.collect { text ->
            tokens.add(text)
            tokenCount++
        }

        val fullContent = tokens.joinToString("")
        val toolCalls = extractToolCalls(fullContent, request.tools)
        val finishReason = if (toolCalls != null) "tool_calls" else "stop"

        val message = if (toolCalls != null) {
            ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                content = null,
                toolCalls = toolCalls,
            )
        } else {
            ChatMessage.assistant(fullContent)
        }

        return ChatCompletion(
            id = completionId,
            created = System.currentTimeMillis() / 1000,
            model = modelName,
            choices = listOf(
                ChatCompletion.Choice(
                    index = 0,
                    message = message,
                    finishReason = finishReason,
                ),
            ),
            usage = ChatCompletion.Usage(
                promptTokens = estimateTokens(prompt),
                completionTokens = tokenCount,
                totalTokens = estimateTokens(prompt) + tokenCount,
            ),
        )
    }

    /**
     * Stream a chat completion, emitting chunks as they are generated.
     *
     * Equivalent to OpenAI's `client.chat.completions.create(stream=true)`.
     * Each chunk contains a delta with incremental content.
     */
    fun stream(request: ChatRequest): Flow<ChatCompletionChunk> = flow {
        val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
        val created = System.currentTimeMillis() / 1000
        val prompt = formatPrompt(request)
        val config = GenerateConfig(
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            topP = request.topP,
            stop = request.stop,
        )
        var isFirst = true

        val tokenFlow = runtime?.generate(prompt, config)
            ?: engine.generate(prompt, Modality.TEXT).let { engineFlow ->
                kotlinx.coroutines.flow.flow {
                    engineFlow.collect { chunk ->
                        emit(chunk.data.toString(Charsets.UTF_8))
                    }
                }
            }

        tokenFlow.collect { text ->
            emit(
                ChatCompletionChunk(
                    id = completionId,
                    created = created,
                    model = modelName,
                    choices = listOf(
                        ChatCompletionChunk.ChunkChoice(
                            index = 0,
                            delta = ChatCompletionChunk.Delta(
                                role = if (isFirst) ChatMessage.Role.ASSISTANT else null,
                                content = text,
                            ),
                        ),
                    ),
                ),
            )
            isFirst = false
        }

        // Final chunk with finish_reason
        emit(
            ChatCompletionChunk(
                id = completionId,
                created = created,
                model = modelName,
                choices = listOf(
                    ChatCompletionChunk.ChunkChoice(
                        index = 0,
                        delta = ChatCompletionChunk.Delta(),
                        finishReason = "stop",
                    ),
                ),
            ),
        )
    }

    /**
     * Convenience: create a completion from a single user message.
     */
    suspend fun create(message: String): ChatCompletion =
        create(ChatRequest(messages = listOf(ChatMessage.user(message))))

    /**
     * Convenience: stream a completion from a single user message.
     */
    fun stream(message: String): Flow<ChatCompletionChunk> =
        stream(ChatRequest(messages = listOf(ChatMessage.user(message))))

    /**
     * Format a chat request into a prompt string for the engine.
     *
     * Uses a ChatML-style template. Can be overridden for model-specific
     * prompt formats (Llama, Phi, Gemma, etc.).
     */
    internal fun formatPrompt(request: ChatRequest): String {
        val sb = StringBuilder()

        for (msg in request.messages) {
            when (msg.role) {
                ChatMessage.Role.SYSTEM -> sb.append("<|system|>\n${msg.content}\n")
                ChatMessage.Role.USER -> sb.append("<|user|>\n${msg.content}\n")
                ChatMessage.Role.ASSISTANT -> sb.append("<|assistant|>\n${msg.content}\n")
                ChatMessage.Role.TOOL -> sb.append("<|tool|>\n${msg.content}\n")
            }
        }

        // Add tool definitions to system prompt if present
        if (!request.tools.isNullOrEmpty()) {
            sb.insert(0, buildToolSystemPrompt(request.tools))
        }

        sb.append("<|assistant|>\n")
        return sb.toString()
    }

    /**
     * Build a system prompt section describing available tools.
     */
    private fun buildToolSystemPrompt(tools: List<Tool>): String {
        val sb = StringBuilder("<|system|>\nYou have access to the following tools:\n\n")
        for (tool in tools) {
            sb.append("Function: ${tool.function.name}\n")
            sb.append("Description: ${tool.function.description}\n")
            if (tool.function.parameters != null) {
                sb.append("Parameters: ${tool.function.parameters}\n")
            }
            sb.append("\n")
        }
        sb.append("To use a tool, respond with JSON: {\"tool_call\": {\"name\": \"function_name\", \"arguments\": {...}}}\n\n")
        return sb.toString()
    }

    /**
     * Extract tool calls from the model's response.
     *
     * Looks for JSON blocks matching the tool call format and parses them
     * into structured [ToolCall] objects.
     */
    private fun extractToolCalls(content: String, tools: List<Tool>?): List<ToolCall>? {
        if (tools.isNullOrEmpty()) return null

        val toolNames = tools.map { it.function.name }.toSet()

        return try {
            // Try to parse as JSON tool call
            val trimmed = content.trim()
            val jsonObj = json.parseToJsonElement(trimmed).jsonObject

            val toolCallObj = jsonObj["tool_call"]?.jsonObject ?: return null
            val name = toolCallObj["name"]?.jsonPrimitive?.content ?: return null

            if (name !in toolNames) return null

            val arguments = toolCallObj["arguments"]?.toString() ?: "{}"

            listOf(
                ToolCall(
                    id = "call_${UUID.randomUUID().toString().take(8)}",
                    function = FunctionCall(name = name, arguments = arguments),
                ),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Rough token count estimate (whitespace split). Not exact but sufficient
     * for usage reporting. Real tokenizers can be plugged in via LLMRuntime.
     */
    private fun estimateTokens(text: String): Int =
        text.split("\\s+".toRegex()).size
}
