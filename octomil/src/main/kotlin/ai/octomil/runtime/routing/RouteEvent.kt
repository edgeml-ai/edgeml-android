package ai.octomil.runtime.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Structured route event emitted after each inference request completes.
 *
 * Contains routing metadata suitable for telemetry upload. NEVER includes
 * user content (prompt, input, output, audio, file paths, etc.).
 *
 * All cross-SDK canonical correlation fields are present:
 * - [routeId], [requestId] — unique identifiers for correlation
 * - [appSlug], [deploymentId], [experimentId], [variantId] — deployment context
 * - [selectedLocality], [finalMode] — where and how inference ran
 * - [fallbackUsed], [fallbackTriggerCode], [fallbackTriggerStage] — fallback info
 * - [candidateAttempts] — how many candidates were evaluated
 */
@Serializable
data class RouteEvent(
    /** Unique route identifier (generated per routing decision). */
    @SerialName("route_id") val routeId: String = generateRouteId(),
    /** Unique request identifier for correlation. */
    @SerialName("request_id") val requestId: String,
    /** Planner plan ID (if a server plan was used). */
    @SerialName("plan_id") val planId: String? = null,
    /** Capability surface being routed (chat, embeddings, audio, responses). */
    @SerialName("capability") val capability: String,
    /** Routing policy applied (auto, local_only, cloud_only, private). */
    @SerialName("policy") val policy: String? = null,
    /** Source of the routing plan (server, local, none). */
    @SerialName("planner_source") val plannerSource: String? = null,
    /** The locality where inference was ultimately executed. */
    @SerialName("selected_locality") val selectedLocality: String,
    /** Backward-compatible route metadata locality alias. */
    @SerialName("final_locality") val finalLocality: String = selectedLocality,
    /** The execution mode used (sdk_runtime, hosted_gateway, external_endpoint). */
    @SerialName("final_mode") val finalMode: String,
    /** Engine used for inference (e.g. litert, llamacpp, cloud). */
    @SerialName("engine") val engine: String? = null,
    /** Whether fallback was triggered during routing. */
    @SerialName("fallback_used") val fallbackUsed: Boolean = false,
    /** The code that triggered fallback (if applicable). */
    @SerialName("fallback_trigger_code") val fallbackTriggerCode: String? = null,
    /** The stage at which fallback was triggered (prepare, gate, inference). */
    @SerialName("fallback_trigger_stage") val fallbackTriggerStage: String? = null,
    /** Number of candidates evaluated during routing. */
    @SerialName("candidate_attempts") val candidateAttempts: Int = 0,
    /** Model reference string as provided by the caller. */
    @SerialName("model_ref") val modelRef: String? = null,
    /** Kind of model reference (slug, deployment, app, etc.). */
    @SerialName("model_ref_kind") val modelRefKind: String? = null,
    /** App slug for @app references. */
    @SerialName("app_slug") val appSlug: String? = null,
    /** App ID for @app references. */
    @SerialName("app_id") val appId: String? = null,
    /** Deployment ID for correlation. */
    @SerialName("deployment_id") val deploymentId: String? = null,
    /** Experiment ID for correlation. */
    @SerialName("experiment_id") val experimentId: String? = null,
    /** Variant ID for experiment/deployment variants. */
    @SerialName("variant_id") val variantId: String? = null,
    /** Artifact ID of the model artifact used. */
    @SerialName("artifact_id") val artifactId: String? = null,
    /** Cache status for the route decision: "hit", "miss", or "not_applicable". */
    @SerialName("cache_status") val cacheStatus: String? = null,
) {
    companion object {
        /** Generate a unique route ID. */
        fun generateRouteId(): String {
            val timestamp = System.currentTimeMillis().toString(36)
            val random = (Math.random() * 1e10).toLong().toString(36)
            return "route_${timestamp}${random}"
        }

        /** Build a [RouteEvent] from a production routing decision. */
        fun from(
            decision: RoutingDecisionResult,
            requestId: String,
            capability: String,
        ): RouteEvent {
            val meta = decision.routeMetadata
            val execution = meta.execution
            val parsedRef = ModelRefParser.parse(meta.model.requested.ref)
            return RouteEvent(
                requestId = requestId,
                capability = capability,
                plannerSource = meta.planner.source,
                selectedLocality = execution?.locality ?: decision.locality,
                finalMode = execution?.mode ?: decision.mode,
                engine = execution?.engine ?: decision.engine,
                fallbackUsed = meta.fallback.used,
                fallbackTriggerCode = meta.fallback.trigger?.code,
                fallbackTriggerStage = meta.fallback.trigger?.stage,
                candidateAttempts = meta.attempts.size,
                modelRef = meta.model.requested.ref,
                modelRefKind = meta.model.requested.kind,
                appSlug = (parsedRef as? ParsedModelRef.AppRef)?.slug,
                deploymentId = (parsedRef as? ParsedModelRef.DeploymentRef)?.deploymentId,
                experimentId = (parsedRef as? ParsedModelRef.ExperimentRef)?.experimentId,
                variantId = meta.model.resolved?.variantId ?: (parsedRef as? ParsedModelRef.ExperimentRef)?.variantId,
                artifactId = meta.artifact?.id,
            )
        }
    }
}

/**
 * Keys that must NEVER appear in a RouteEvent or any telemetry payload.
 *
 * Prevents prompt/input/output/audio/file_path leakage into telemetry.
 * Cross-SDK canonical constant.
 */
val FORBIDDEN_TELEMETRY_KEYS: Set<String> = setOf(
    "prompt",
    "input",
    "output",
    "completion",
    "audio",
    "audio_bytes",
    "file_path",
    "text",
    "content",
    "messages",
    "system_prompt",
    "documents",
    "image",
    "image_url",
    "embedding",
    "embeddings",
)

/**
 * Strip any forbidden telemetry keys from a [JsonPrimitive] attribute map.
 *
 * Returns a new map with forbidden keys removed. Use before uploading
 * custom metadata alongside route events.
 */
fun stripForbiddenKeys(attributes: Map<String, JsonPrimitive>): Map<String, JsonPrimitive> =
    attributes.filterKeys { it !in FORBIDDEN_TELEMETRY_KEYS }

/**
 * Strip any forbidden telemetry keys from a generic string-keyed map.
 *
 * Returns a new map with forbidden keys removed.
 */
fun <V> stripForbiddenKeysGeneric(attributes: Map<String, V>): Map<String, V> =
    scrubForbiddenTelemetryValue(attributes) as Map<String, V>

/**
 * Validates that an attribute map does not contain any forbidden telemetry keys.
 *
 * @throws IllegalArgumentException if a forbidden key is found.
 */
fun validateRouteEventAttributes(attributes: Map<String, *>) {
    findForbiddenTelemetryKeys(attributes).firstOrNull()?.let { key ->
        error(
            "RouteEvent contains forbidden telemetry key: \"$key\". Route events must never include user content."
        )
    }
}

private fun findForbiddenTelemetryKeys(value: Any?, path: String = ""): List<String> {
    return when (value) {
        is Map<*, *> -> value.entries.flatMap { entry ->
            val key = entry.key as? String ?: return@flatMap emptyList<String>()
            val fullPath = if (path.isEmpty()) key else "$path.$key"
            if (key in FORBIDDEN_TELEMETRY_KEYS) {
                listOf(fullPath)
            } else {
                findForbiddenTelemetryKeys(entry.value, fullPath)
            }
        }
        is List<*> -> value.flatMapIndexed { index, item ->
            findForbiddenTelemetryKeys(item, "$path[$index]")
        }
        else -> emptyList()
    }
}

private fun scrubForbiddenTelemetryValue(value: Any?): Any? {
    return when (value) {
        is Map<*, *> -> buildMap<String, Any?> {
            for ((key, child) in value) {
                val stringKey = key as? String ?: continue
                if (stringKey in FORBIDDEN_TELEMETRY_KEYS) {
                    continue
                }
                put(stringKey, scrubForbiddenTelemetryValue(child))
            }
        }
        is List<*> -> value.map { scrubForbiddenTelemetryValue(it) }
        else -> value
    }
}

/**
 * Build a [RouteEvent] from an [AttemptLoopResult] and request metadata.
 *
 * Automatically extracts engine, artifact, fallback info from the attempt
 * loop result. Validates the event before returning.
 */
fun buildRouteEvent(
    requestId: String,
    capability: String,
    attemptResult: AttemptLoopResult<*>,
    policy: String? = null,
    plannerSource: String? = null,
    planId: String? = null,
    modelRef: String? = null,
    modelRefKind: String? = null,
    appSlug: String? = null,
    appId: String? = null,
    deploymentId: String? = null,
    experimentId: String? = null,
    variantId: String? = null,
): RouteEvent {
    val selected = attemptResult.selectedAttempt
    return RouteEvent(
        requestId = requestId,
        planId = planId,
        capability = capability,
        policy = policy,
        plannerSource = plannerSource,
        selectedLocality = selected?.locality ?: "unknown",
        finalMode = selected?.mode ?: "unknown",
        engine = selected?.engine,
        fallbackUsed = attemptResult.fallbackUsed,
        fallbackTriggerCode = attemptResult.fallbackTrigger?.code,
        fallbackTriggerStage = attemptResult.fallbackTrigger?.stage,
        candidateAttempts = attemptResult.attempts.size,
        modelRef = modelRef,
        modelRefKind = modelRefKind,
        appSlug = appSlug,
        appId = appId,
        deploymentId = deploymentId,
        experimentId = experimentId,
        variantId = variantId,
        artifactId = selected?.artifact?.id,
    )
}
