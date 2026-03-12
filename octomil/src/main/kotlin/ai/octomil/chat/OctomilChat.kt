package ai.octomil.chat

import ai.octomil.responses.ContentPart
import ai.octomil.responses.InputItem
import ai.octomil.responses.OctomilResponses
import ai.octomil.responses.OutputItem
import ai.octomil.responses.ResponseRequest
import ai.octomil.responses.ResponseStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * COMPATIBILITY shim — OpenAI Chat Completions API surface backed by
 * [OctomilResponses].
 *
 * Drop-in replacement for OpenAI/Groq client calls — same message format,
 * same response shapes, same streaming semantics. Delegates all inference
 * to the Responses API; this class only handles request/response conversion.
 *
 * ```kotlin
 * val chat = OctomilChat(modelName = "phi-4-mini", responses = responses)
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
 * @property responses The OctomilResponses instance that handles inference.
 */
class OctomilChat internal constructor(
    val modelName: String,
    private val responses: OctomilResponses,
) {
    /**
     * Create a chat completion (non-streaming).
     *
     * Converts [ChatRequest] to [ResponseRequest], delegates to
     * [OctomilResponses.create], and converts back.
     */
    suspend fun create(request: ChatRequest): ChatCompletion {
        val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
        val responseRequest = toResponseRequest(request)
        val response = responses.create(responseRequest)

        val textContent = response.output
            .filterIsInstance<OutputItem.Text>()
            .joinToString("") { it.text }

        val toolCalls = response.output
            .filterIsInstance<OutputItem.ToolCallItem>()
            .map { item ->
                ToolCall(
                    id = item.toolCall.id,
                    function = FunctionCall(
                        name = item.toolCall.name,
                        arguments = item.toolCall.arguments,
                    ),
                )
            }
            .ifEmpty { null }

        val finishReason = if (toolCalls != null) "tool_calls" else response.finishReason

        val message = if (toolCalls != null) {
            ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                content = if (textContent.isNotEmpty()) textContent else null,
                toolCalls = toolCalls,
            )
        } else {
            ChatMessage.assistant(textContent)
        }

        val usage = response.usage?.let {
            ChatCompletion.Usage(
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens,
            )
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
            usage = usage,
        )
    }

    /**
     * Stream a chat completion, emitting chunks as they are generated.
     *
     * Delegates to [OctomilResponses.stream] and converts each
     * [ResponseStreamEvent] to a [ChatCompletionChunk].
     */
    fun stream(request: ChatRequest): Flow<ChatCompletionChunk> = flow {
        val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
        val created = System.currentTimeMillis() / 1000
        val responseRequest = toResponseRequest(request)
        var isFirst = true

        responses.stream(responseRequest).collect { event ->
            when (event) {
                is ResponseStreamEvent.TextDelta -> {
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
                                        content = event.delta,
                                    ),
                                ),
                            ),
                        ),
                    )
                    isFirst = false
                }
                is ResponseStreamEvent.ToolCallDelta -> {
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
                                        toolCalls = listOf(
                                            ToolCall(
                                                id = event.id ?: "",
                                                function = FunctionCall(
                                                    name = event.name ?: "",
                                                    arguments = event.argumentsDelta ?: "",
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    )
                    isFirst = false
                }
                is ResponseStreamEvent.Done -> {
                    emit(
                        ChatCompletionChunk(
                            id = completionId,
                            created = created,
                            model = modelName,
                            choices = listOf(
                                ChatCompletionChunk.ChunkChoice(
                                    index = 0,
                                    delta = ChatCompletionChunk.Delta(),
                                    finishReason = event.response.finishReason,
                                ),
                            ),
                        ),
                    )
                }
                is ResponseStreamEvent.Error -> {
                    throw event.error
                }
            }
        }
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

    // =========================================================================
    // ChatRequest → ResponseRequest conversion
    // =========================================================================

    private fun toResponseRequest(request: ChatRequest): ResponseRequest {
        val input = request.messages.map { msg ->
            when (msg.role) {
                ChatMessage.Role.SYSTEM -> InputItem.System(msg.content ?: "")
                ChatMessage.Role.USER -> InputItem.User.text(msg.content ?: "")
                ChatMessage.Role.ASSISTANT -> InputItem.Assistant(
                    content = msg.content?.let { listOf(ContentPart.Text(it)) },
                )
                ChatMessage.Role.TOOL -> InputItem.ToolResult(
                    toolCallId = msg.toolCallId ?: "",
                    content = msg.content ?: "",
                )
            }
        }

        return ResponseRequest(
            model = modelName,
            input = input,
            tools = request.tools ?: emptyList(),
            maxOutputTokens = request.maxTokens,
            temperature = request.temperature,
            topP = request.topP,
            stop = request.stop,
        )
    }
}
