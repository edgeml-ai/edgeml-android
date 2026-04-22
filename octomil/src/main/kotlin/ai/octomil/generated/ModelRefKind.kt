package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class ModelRefKind(val code: String) {
    MODEL("model"),
    APP("app"),
    CAPABILITY("capability"),
    DEPLOYMENT("deployment"),
    EXPERIMENT("experiment"),
    ALIAS("alias"),
    DEFAULT("default"),
    UNKNOWN("unknown");

    companion object {
        fun fromCode(code: String): ModelRefKind? =
            entries.firstOrNull { it.code == code }
    }
}
