package ai.octomil.runtime.planner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Routing metadata from the runtime planner.
 *
 * Mirrors the Python SDK's `RouteMetadata` dataclass in
 * `octomil.execution.kernel`. Provides a shared shape across SDKs
 * so callers can inspect how a route was resolved.
 *
 * @property locality Where inference will run: "on_device" or "cloud".
 * @property engine Engine wire name (e.g. "llama.cpp", "tflite") or null.
 * @property plannerSource How the selection was made: "server", "cache", "offline".
 * @property fallbackUsed Whether the selected candidate came from the fallback list.
 * @property reason Human-readable explanation of the routing decision.
 */
@Serializable
data class RouteMetadata(
    @SerialName("locality") val locality: String = "",
    @SerialName("engine") val engine: String? = null,
    @SerialName("planner_source") val plannerSource: String = "",
    @SerialName("fallback_used") val fallbackUsed: Boolean = false,
    @SerialName("reason") val reason: String = "",
) {
    companion object {
        /**
         * Build [RouteMetadata] from a [RuntimeSelection].
         *
         * Maps the selection's internal locality values ("local" / "cloud")
         * to the cross-SDK convention ("on_device" / "cloud"), and translates
         * the source field to a planner source hint.
         */
        fun fromSelection(selection: RuntimeSelection): RouteMetadata {
            val locality = when (selection.locality) {
                "local" -> "on_device"
                else -> selection.locality
            }
            val plannerSource = when (selection.source) {
                "server_plan" -> "server"
                "cache" -> "cache"
                "local_default", "fallback" -> "offline"
                else -> selection.source
            }
            val fallbackUsed = selection.source == "fallback" ||
                selection.reason.startsWith("fallback:")

            return RouteMetadata(
                locality = locality,
                engine = selection.engine,
                plannerSource = plannerSource,
                fallbackUsed = fallbackUsed,
                reason = selection.reason,
            )
        }
    }
}
