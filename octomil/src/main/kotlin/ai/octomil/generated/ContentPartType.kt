package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ContentPartType(val code: String) {
    INPUT_TEXT("input_text"),
    INPUT_IMAGE("input_image"),
    INPUT_AUDIO("input_audio"),
    INPUT_VIDEO("input_video");

    companion object {
        fun fromCode(code: String): ContentPartType? =
            entries.firstOrNull { it.code == code }
    }
}
