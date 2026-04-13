package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ModelCapability(val code: String) {
    CHAT("chat"),
    TRANSCRIPTION("transcription"),
    TEXT_COMPLETION("text_completion"),
    KEYBOARD_PREDICTION("keyboard_prediction"),
    EMBEDDING("embedding"),
    CLASSIFICATION("classification"),
    REASONING("reasoning"),
    VISION("vision");

    companion object {
        fun fromCode(code: String): ModelCapability? =
            entries.firstOrNull { it.code == code }
    }
}
