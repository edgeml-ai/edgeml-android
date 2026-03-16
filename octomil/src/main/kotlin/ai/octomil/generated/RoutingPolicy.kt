package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class RoutingPolicy(val code: String) {
    LOCAL_ONLY("local_only"),
    LOCAL_FIRST("local_first"),
    CLOUD_ONLY("cloud_only"),
    AUTO("auto");

    companion object {
        fun fromCode(code: String): RoutingPolicy? =
            entries.firstOrNull { it.code == code }
    }
}
