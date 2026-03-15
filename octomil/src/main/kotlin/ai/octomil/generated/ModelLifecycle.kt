package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ModelLifecycle(val code: String) {
    ACTIVE("active"),
    DEPRECATED("deprecated"),
    RETIRED("retired"),
    PREVIEW("preview");

    companion object {
        fun fromCode(code: String): ModelLifecycle? =
            entries.firstOrNull { it.code == code }
    }
}
