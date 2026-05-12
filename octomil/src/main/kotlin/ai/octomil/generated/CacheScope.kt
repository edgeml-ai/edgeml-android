package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class CacheScope(val code: String) {
    REQUEST("request"),
    SESSION("session"),
    RUNTIME("runtime"),
    APP("app");

    companion object {
        fun fromCode(code: String): CacheScope? =
            entries.firstOrNull { it.code == code }
    }
}
