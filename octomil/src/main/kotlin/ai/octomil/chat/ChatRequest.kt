package ai.octomil.chat

/**
 * Request parameters for a chat completion, mirroring OpenAI's request format.
 *
 * ```kotlin
 * val request = ChatRequest(
 *     messages = listOf(ChatMessage.user("Hello")),
 *     temperature = 0.7f,
 *     maxTokens = 256,
 * )
 * ```
 */
data class ChatRequest(
    /** The conversation messages. */
    val messages: List<ChatMessage>,
    /** Sampling temperature (0.0 = deterministic, 2.0 = very random). */
    val temperature: Float = 0.7f,
    /** Maximum number of tokens to generate. */
    val maxTokens: Int = 512,
    /** Top-p nucleus sampling. */
    val topP: Float = 1.0f,
    /** Tools the model may call. */
    val tools: List<Tool>? = null,
    /** Stop sequences that halt generation. */
    val stop: List<String>? = null,
)
