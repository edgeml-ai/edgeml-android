package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class RouteLocality(val code: String) {
    LOCAL("local"),
    CLOUD("cloud");

    companion object {
        fun fromCode(code: String): RouteLocality? =
            entries.firstOrNull { it.code == code }
    }
}
