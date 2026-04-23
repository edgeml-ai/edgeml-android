package ai.octomil.runtime.planner

import ai.octomil.generated.PlannerSource
import ai.octomil.runtime.routing.FallbackTrigger
import ai.octomil.runtime.routing.RouteAttempt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Normalizes planner source strings to the canonical contract enum.
 *
 * Canonical values are backed by the contract-generated [PlannerSource] enum:
 * "server", "cache", "offline".
 *
 * Aliases:
 * - "server_plan" -> "server"
 * - "cached" -> "cache"
 * - "local_default", "fallback", "none", "local_benchmark", "" -> "offline"
 *
 * Unknown values collapse to "offline" so SDK output boundaries never emit a
 * contract-invalid planner source.
 */
object PlannerSourceNormalizer {
    /** Canonical planner source values (backed by generated [PlannerSource] enum). */
    val canonicalSources: Set<String> = setOf(
        PlannerSource.SERVER.code,
        PlannerSource.CACHE.code,
        PlannerSource.OFFLINE.code,
    )

    private val aliases: Map<String, String> = mapOf(
        "local_default" to PlannerSource.OFFLINE.code,
        "server_plan" to PlannerSource.SERVER.code,
        "cached" to PlannerSource.CACHE.code,
        "fallback" to PlannerSource.OFFLINE.code,
        "none" to PlannerSource.OFFLINE.code,
        "local_benchmark" to PlannerSource.OFFLINE.code,
    )

    /** Normalize a planner source string to its canonical value. */
    fun normalize(source: String): String {
        if (source.isEmpty()) return PlannerSource.OFFLINE.code
        if (canonicalSources.contains(source)) return source
        return aliases[source] ?: PlannerSource.OFFLINE.code
    }
}

/**
 * Contract-backed route metadata shape for the Android SDK.
 *
 * Mirrors the canonical JSON wire format defined in octomil-contracts.
 * All SDKs (Python, Node, iOS, Android, Browser) emit this identical nested
 * structure so callers can inspect how a route was resolved.
 *
 * Public locality values: "local" | "cloud". Never "on_device".
 * Execution modes: "sdk_runtime" (local), "hosted_gateway" (api.octomil.com),
 *                  "external_endpoint" (user-configured).
 */

@Serializable
data class RouteExecution(
    @SerialName("locality") val locality: String,   // "local" | "cloud"
    @SerialName("mode") val mode: String,           // "sdk_runtime" | "hosted_gateway" | "external_endpoint"
    @SerialName("engine") val engine: String? = null,
)

@Serializable
data class RouteModelRequested(
    @SerialName("ref") val ref: String,
    @SerialName("kind") val kind: String = "unknown",
    @SerialName("capability") val capability: String? = null,
)

@Serializable
data class RouteModelResolved(
    @SerialName("id") val id: String? = null,
    @SerialName("slug") val slug: String? = null,
    @SerialName("version_id") val versionId: String? = null,
    @SerialName("variant_id") val variantId: String? = null,
)

@Serializable
data class RouteModel(
    @SerialName("requested") val requested: RouteModelRequested,
    @SerialName("resolved") val resolved: RouteModelResolved? = null,
)

@Serializable
data class ArtifactCache(
    @SerialName("status") val status: String = "not_applicable",
    @SerialName("managed_by") val managedBy: String? = null,
)

@Serializable
data class RouteArtifact(
    @SerialName("id") val id: String? = null,
    @SerialName("version") val version: String? = null,
    @SerialName("format") val format: String? = null,
    @SerialName("digest") val digest: String? = null,
    @SerialName("cache") val cache: ArtifactCache = ArtifactCache(),
)

@Serializable
data class PlannerInfo(
    @SerialName("source") val source: String = "offline",
)

@Serializable
data class FallbackInfo(
    @SerialName("used") val used: Boolean = false,
    @SerialName("from_attempt") val fromAttempt: Int? = null,
    @SerialName("to_attempt") val toAttempt: Int? = null,
    @SerialName("trigger") val trigger: FallbackTrigger? = null,
)

@Serializable
data class RouteReason(
    @SerialName("code") val code: String = "",
    @SerialName("message") val message: String = "",
)

@Serializable
data class RouteMetadata(
    @SerialName("status") val status: String = "selected",
    @SerialName("execution") val execution: RouteExecution? = null,
    @SerialName("model") val model: RouteModel,
    @SerialName("artifact") val artifact: RouteArtifact? = null,
    @SerialName("planner") val planner: PlannerInfo = PlannerInfo(),
    @SerialName("fallback") val fallback: FallbackInfo = FallbackInfo(),
    @SerialName("attempts") val attempts: List<RouteAttempt> = emptyList(),
    @SerialName("reason") val reason: RouteReason = RouteReason(),
) {
    companion object {
        /**
         * Build [RouteMetadata] from a [RuntimeSelection].
         *
         * Maps the selection's internal fields to the contract-backed nested
         * shape. Locality is passed through as-is ("local" or "cloud") --
         * the old "on_device" mapping is removed per the contract spec.
         *
         * Execution mode is derived from locality:
         * - "local" -> "sdk_runtime"
         * - "cloud" -> "hosted_gateway"
         */
        fun fromSelection(
            selection: RuntimeSelection,
            modelRef: String = "",
            capability: String? = null,
        ): RouteMetadata {
            val locality = selection.locality  // "local" | "cloud" -- no remapping
            val mode = when (locality) {
                "local" -> "sdk_runtime"
                "cloud" -> "hosted_gateway"
                else -> "sdk_runtime"
            }

            val plannerSource = PlannerSourceNormalizer.normalize(selection.source)

            val fallbackUsed = selection.source == "fallback" ||
                selection.reason.startsWith("fallback:")

            val execution = RouteExecution(
                locality = locality,
                mode = mode,
                engine = selection.engine,
            )

            val routeModel = RouteModel(
                requested = RouteModelRequested(
                    ref = modelRef,
                    kind = "unknown",
                    capability = capability,
                ),
            )

            val routeArtifact = selection.artifact?.let { art ->
                RouteArtifact(
                    id = art.artifactId,
                    version = art.modelVersion,
                    format = art.format,
                    digest = art.digest,
                )
            }

            return RouteMetadata(
                status = "selected",
                execution = execution,
                model = routeModel,
                artifact = routeArtifact,
                planner = PlannerInfo(source = plannerSource),
                fallback = FallbackInfo(used = fallbackUsed),
                reason = RouteReason(
                    code = if (fallbackUsed) "fallback" else "ok",
                    message = selection.reason,
                ),
            )
        }
    }
}
