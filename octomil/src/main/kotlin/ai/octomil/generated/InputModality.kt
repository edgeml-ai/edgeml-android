package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class InputModality(val code: String) {
    TEXT("text"),
    IMAGE("image"),
    AUDIO("audio"),
    VIDEO("video");

    companion object {
        fun fromCode(code: String): InputModality? =
            entries.firstOrNull { it.code == code }
    }
}
