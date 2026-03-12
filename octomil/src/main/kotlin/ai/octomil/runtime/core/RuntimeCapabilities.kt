package ai.octomil.runtime.core

data class RuntimeCapabilities(
    val supportsToolCalls: Boolean = false,
    val supportsStructuredOutput: Boolean = false,
    val supportsMultimodalInput: Boolean = false,
    val supportsStreaming: Boolean = true,
    val maxContextLength: Int? = null,
    val supportedFamilies: Set<String> = emptySet(),
)
