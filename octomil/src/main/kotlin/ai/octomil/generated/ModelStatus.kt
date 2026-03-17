package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ModelStatus(val code: String) {
    NOT_CACHED("not_cached"),
    QUEUED("queued"),
    DOWNLOADING("downloading"),
    READY("ready"),
    FAILED("failed");

    companion object {
        fun fromCode(code: String): ModelStatus? =
            entries.firstOrNull { it.code == code }
    }
}
