package ai.octomil.responses.runtime

data class RuntimeRequest(
    val prompt: String,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val stop: List<String>? = null,
    val toolDefinitions: List<RuntimeToolDef>? = null,
    val jsonSchema: String? = null,
)

data class RuntimeToolDef(
    val name: String,
    val description: String,
    val parametersSchema: String? = null,
)
