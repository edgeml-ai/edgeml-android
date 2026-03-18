package ai.octomil.runtime.core

import ai.octomil.generated.Modality

data class RuntimeCapabilities(
    val supportsToolCalls: Boolean = false,
    val supportsStructuredOutput: Boolean = false,
    val supportsMultimodalInput: Boolean = false,
    val supportsStreaming: Boolean = true,
    val maxContextLength: Int? = null,
    val supportedFamilies: Set<String> = emptySet(),
    /** Input modalities this runtime can process. */
    val inputModalities: Set<Modality> = setOf(Modality.TEXT),
    /** Output modalities this runtime can produce. */
    val outputModalities: Set<Modality> = setOf(Modality.TEXT),
)
