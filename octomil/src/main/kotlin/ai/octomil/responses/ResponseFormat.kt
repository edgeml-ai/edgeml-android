package ai.octomil.responses

sealed interface ResponseFormat {
    data object Text : ResponseFormat
    data object JsonObject : ResponseFormat
    data class JsonSchema(val schema: String) : ResponseFormat
}
