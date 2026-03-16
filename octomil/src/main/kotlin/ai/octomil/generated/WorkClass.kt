package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class WorkClass(val code: String) {
    CRITICAL_FOREGROUND("critical_foreground"),
    BACKGROUND_IMPORTANT("background_important"),
    BACKGROUND_BEST_EFFORT("background_best_effort");

    companion object {
        fun fromCode(code: String): WorkClass? =
            entries.firstOrNull { it.code == code }
    }
}
