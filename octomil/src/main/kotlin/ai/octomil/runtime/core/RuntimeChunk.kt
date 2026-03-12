package ai.octomil.runtime.core

data class RuntimeChunk(
    val text: String? = null,
    val toolCallDelta: RuntimeToolCallDelta? = null,
    val finishReason: String? = null,
    val usage: RuntimeUsage? = null,
)

data class RuntimeToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val argumentsDelta: String? = null,
)
