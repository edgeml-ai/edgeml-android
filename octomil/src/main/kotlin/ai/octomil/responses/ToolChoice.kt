package ai.octomil.responses

sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Required : ToolChoice
    data class Specific(val name: String) : ToolChoice
}
