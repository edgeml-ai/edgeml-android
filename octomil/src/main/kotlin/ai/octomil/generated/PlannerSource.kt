package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class PlannerSource(val code: String) {
    SERVER("server"),
    CACHE("cache"),
    OFFLINE("offline");

    companion object {
        fun fromCode(code: String): PlannerSource? =
            entries.firstOrNull { it.code == code }
    }
}
