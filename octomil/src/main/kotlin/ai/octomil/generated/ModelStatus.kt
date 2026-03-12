// Auto-generated from octomil-contracts. Do not edit.
package ai.octomil.generated

enum class ModelStatus(val code: String) {
    NOT_CACHED("not_cached"),
    DOWNLOADING("downloading"),
    READY("ready"),
    ERROR("error");

    companion object {
        fun fromCode(code: String): ModelStatus? =
            entries.firstOrNull { it.code == code }
    }
}
