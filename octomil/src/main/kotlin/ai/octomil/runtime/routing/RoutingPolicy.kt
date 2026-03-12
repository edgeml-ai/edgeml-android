package ai.octomil.runtime.routing

/**
 * ADVANCED — MAY: Policy governing runtime selection in [RouterModelRuntime].
 */
sealed interface RoutingPolicy {
    data class Auto(
        val preferLocal: Boolean = true,
        val maxLatencyMs: Int? = null,
        val fallback: String = "cloud",
    ) : RoutingPolicy

    data object LocalOnly : RoutingPolicy
    data object CloudOnly : RoutingPolicy

    companion object {
        fun fromMetadata(metadata: Map<String, String>?): RoutingPolicy? {
            if (metadata == null) return null
            return when (metadata["routing.policy"]) {
                "local_only" -> LocalOnly
                "cloud_only" -> CloudOnly
                "auto" -> Auto(
                    preferLocal = metadata["routing.prefer_local"] != "false",
                    maxLatencyMs = metadata["routing.max_latency_ms"]?.toIntOrNull(),
                    fallback = metadata["routing.fallback"] ?: "cloud",
                )
                else -> null
            }
        }
    }
}
