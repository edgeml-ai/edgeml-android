package ai.octomil.runtime.core

import ai.octomil.generated.MessageRole

/** A single content part within a runtime message. Media parts hold raw decoded bytes. */
sealed interface RuntimeContentPart {
    data class Text(val text: String) : RuntimeContentPart
    data class Image(val data: ByteArray, val mediaType: String) : RuntimeContentPart
    data class Audio(val data: ByteArray, val mediaType: String) : RuntimeContentPart
    data class Video(val data: ByteArray, val mediaType: String) : RuntimeContentPart
}

/** A message in a runtime conversation. */
data class RuntimeMessage(
    val role: MessageRole,
    val parts: List<RuntimeContentPart>,
)

/** Generation parameters. */
data class GenerationConfig(
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val stop: List<String>? = null,
)

/** Request sent to a ModelRuntime. */
data class RuntimeRequest(
    val messages: List<RuntimeMessage>,
    val generationConfig: GenerationConfig = GenerationConfig(),
    val toolDefinitions: List<RuntimeToolDef>? = null,
    val jsonSchema: String? = null,
)

data class RuntimeToolDef(
    val name: String,
    val description: String,
    val parametersSchema: String? = null,
)
