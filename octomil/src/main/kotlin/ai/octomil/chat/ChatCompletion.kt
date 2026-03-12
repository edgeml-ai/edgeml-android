package ai.octomil.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A chat completion response, mirroring the OpenAI Chat Completions API.
 *
 * Returned by [OctomilChat.create]. Contains the model's response along with
 * usage statistics and timing information.
 */
@Serializable
data class ChatCompletion(
    /** Unique identifier for this completion. */
    val id: String,
    /** Always "chat.completion". */
    val `object`: String = "chat.completion",
    /** Unix timestamp when this completion was created. */
    val created: Long,
    /** The model that generated this completion. */
    val model: String,
    /** The list of completion choices. Typically one. */
    val choices: List<Choice>,
    /** Token usage statistics. */
    val usage: Usage? = null,
) {
    @Serializable
    data class Choice(
        /** The index of this choice in the list. */
        val index: Int = 0,
        /** The generated message. */
        val message: ChatMessage,
        /** The reason the model stopped generating. */
        @SerialName("finish_reason")
        val finishReason: String? = null,
    )

    @Serializable
    data class Usage(
        /** Number of tokens in the prompt. */
        @SerialName("prompt_tokens")
        val promptTokens: Int = 0,
        /** Number of tokens in the completion. */
        @SerialName("completion_tokens")
        val completionTokens: Int = 0,
        /** Total tokens used. */
        @SerialName("total_tokens")
        val totalTokens: Int = 0,
    )
}

/**
 * A streamed chat completion chunk, mirroring OpenAI's streaming format.
 *
 * Emitted by [OctomilChat.stream]. Each chunk contains a delta with
 * incremental content.
 */
@Serializable
data class ChatCompletionChunk(
    /** Unique identifier, same across all chunks in one generation. */
    val id: String,
    /** Always "chat.completion.chunk". */
    val `object`: String = "chat.completion.chunk",
    /** Unix timestamp. */
    val created: Long,
    /** The model name. */
    val model: String,
    /** The list of chunk choices. */
    val choices: List<ChunkChoice>,
) {
    @Serializable
    data class ChunkChoice(
        /** The index of this choice. */
        val index: Int = 0,
        /** Incremental message content. */
        val delta: Delta,
        /** Non-null on the final chunk. */
        @SerialName("finish_reason")
        val finishReason: String? = null,
    )

    @Serializable
    data class Delta(
        /** The role (only sent in the first chunk). */
        val role: ChatMessage.Role? = null,
        /** Incremental text content. */
        val content: String? = null,
        /** Incremental tool calls. */
        @SerialName("tool_calls")
        val toolCalls: List<ToolCall>? = null,
    )
}
