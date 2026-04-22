package ai.octomil.runtime.routing

import ai.octomil.runtime.planner.RouteMetadata
import ai.octomil.runtime.planner.RouteExecution
import ai.octomil.runtime.planner.RouteModel
import ai.octomil.runtime.planner.RouteModelRequested
import ai.octomil.runtime.planner.PlannerInfo
import ai.octomil.runtime.planner.FallbackInfo
import ai.octomil.runtime.planner.RouteReason
import ai.octomil.runtime.planner.RuntimeCandidatePlan
import ai.octomil.runtime.planner.RuntimePlanResponse
import java.util.UUID

// =============================================================================
// RequestRoutingContext
// =============================================================================

/**
 * All inputs needed to resolve a routing decision for a single request.
 *
 * The router uses these to parse the model reference, evaluate plan
 * candidates, and build route metadata for the response and telemetry.
 */
data class RequestRoutingContext(
    /** Model identifier or reference string. */
    val model: String,
    /** Capability being requested: "chat", "embeddings", "audio", "text". */
    val capability: String = "chat",
    /** Whether the caller wants streaming output. */
    val streaming: Boolean = false,
    /** Cached plan from the planner store, if available. */
    val cachedPlan: RuntimePlanResponse? = null,
    /** Per-request routing policy name (e.g. "local_first", "cloud_only"). */
    val routingPolicy: String? = null,
)

// =============================================================================
// RoutingDecisionResult
// =============================================================================

/**
 * The resolved routing decision for a single request.
 *
 * Contains enough information for the caller to dispatch inference
 * to the correct runtime and attach route metadata to the response.
 */
data class RoutingDecisionResult(
    /** Where inference should run: "local" or "cloud". */
    val locality: String,
    /** Execution mode: "sdk_runtime" or "hosted_gateway". */
    val mode: String,
    /** Engine to use, if local. */
    val engine: String? = null,
    /** Privacy-safe route metadata attached to the response. */
    val routeMetadata: RouteMetadata,
    /** Full attempt loop result for telemetry. */
    val attemptResult: AttemptLoopResult<Unit>,
)

// =============================================================================
// RequestRouter
// =============================================================================

/**
 * Resolves routing decisions for public-path inference requests.
 *
 * This is the integration point between the planner/attempt runner
 * infrastructure and the public request APIs (Responses, Chat).
 *
 * Resolution flow:
 * 1. Parse the model reference
 * 2. If a cached plan exists, build candidates from it
 * 3. Run the candidate attempt loop (with runtime/gate checks)
 * 4. If no plan and no candidates, fall back to direct hosted gateway
 * 5. Build RouteMetadata and RoutingDecisionResult
 *
 * The router itself does NOT perform inference. It resolves *where*
 * inference should run and returns the decision for the caller to act on.
 */
class RequestRouter {

    /**
     * Resolve a routing decision for the given context.
     *
     * @param context All inputs for the routing decision.
     * @param runtimeChecker Optional checker for engine availability.
     * @param gateEvaluator Optional evaluator for per-request gates.
     * @return A [RoutingDecisionResult] with locality, mode, engine, and metadata.
     */
    fun resolve(
        context: RequestRoutingContext,
        runtimeChecker: AttemptRuntimeChecker = AttemptRuntimeChecker { _, _ -> RuntimeCheck(true) },
        gateEvaluator: AttemptGateEvaluator = AttemptGateEvaluator { gate, _, _ ->
            if (gate.required) GateResult(code = gate.code, status = "unknown")
            else GateResult(code = gate.code, status = "not_required")
        },
    ): RoutingDecisionResult {
        val routeId = UUID.randomUUID().toString()
        val parsedRef = ModelRefParser.parse(context.model)
        val policyString = context.routingPolicy

        // Determine fallback policy from routing policy.
        val fallbackAllowed = isFallbackAllowed(policyString)

        // Build candidates from plan, if available.
        val plan = context.cachedPlan
        if (plan != null) {
            val candidates = candidatesFromPlan(plan)
            if (candidates.isEmpty()) {
                return directHostedFallback(
                    routeId = routeId,
                    parsedRef = parsedRef,
                    policy = policyString,
                    plannerSource = "cache",
                )
            }

            val runner = CandidateAttemptRunner(
                fallbackAllowed = fallbackAllowed,
                streaming = context.streaming,
            )

            val loopResult = runner.run(
                candidates = candidates,
                runtimeChecker = runtimeChecker,
                gateEvaluator = gateEvaluator,
            )

            val selected = loopResult.selectedAttempt
            if (selected != null) {
                val metadata = buildRouteMetadata(
                    routeId = routeId,
                    parsedRef = parsedRef,
                    plannerSource = "cache",
                    policy = plan.policy,
                    selected = selected,
                    loopResult = loopResult,
                )
                return RoutingDecisionResult(
                    locality = selected.locality,
                    mode = selected.mode,
                    engine = selected.engine,
                    routeMetadata = metadata,
                    attemptResult = loopResult,
                )
            }

            // Plan had candidates but none passed — fall back to hosted if allowed.
            if (fallbackAllowed) {
                return directHostedFallback(
                    routeId = routeId,
                    parsedRef = parsedRef,
                    policy = policyString,
                    plannerSource = "cache",
                    attemptResult = loopResult,
                )
            }

            // No fallback allowed — return a failed decision with the attempt loop intact.
            val failedMeta = buildRouteMetadata(
                routeId = routeId,
                parsedRef = parsedRef,
                plannerSource = "cache",
                policy = policyString,
                selected = null,
                loopResult = loopResult,
                overrideLocality = "local",
                overrideMode = "sdk_runtime",
            )
            return RoutingDecisionResult(
                locality = "local",
                mode = "sdk_runtime",
                routeMetadata = failedMeta,
                attemptResult = loopResult,
            )
        }

        // No plan available — fall back to direct hosted gateway.
        return directHostedFallback(
            routeId = routeId,
            parsedRef = parsedRef,
            policy = policyString,
            plannerSource = "offline",
        )
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private fun buildRouteMetadata(
        routeId: String,
        parsedRef: ParsedModelRef,
        plannerSource: String,
        policy: String?,
        selected: RouteAttempt?,
        loopResult: AttemptLoopResult<Unit>,
        overrideLocality: String? = null,
        overrideMode: String? = null,
    ): RouteMetadata {
        val locality = overrideLocality ?: selected?.locality ?: "cloud"
        val mode = overrideMode ?: selected?.mode ?: "hosted_gateway"
        val engine = selected?.engine

        return RouteMetadata(
            status = if (selected != null) "selected" else "failed",
            execution = RouteExecution(
                locality = locality,
                mode = mode,
                engine = engine,
            ),
            model = RouteModel(
                requested = RouteModelRequested(
                    ref = parsedRef.ref,
                    kind = parsedRef.kind,
                ),
            ),
            artifact = selected?.artifact?.let {
                ai.octomil.runtime.planner.RouteArtifact(
                    id = it.id,
                    digest = it.digest,
                    cache = ai.octomil.runtime.planner.ArtifactCache(
                        status = it.cache.status,
                        managedBy = it.cache.managedBy,
                    ),
                )
            },
            planner = PlannerInfo(source = plannerSource),
            fallback = FallbackInfo(
                used = loopResult.fallbackUsed,
                fromAttempt = loopResult.fromAttempt,
                toAttempt = loopResult.toAttempt,
                trigger = loopResult.fallbackTrigger,
            ),
            attempts = loopResult.attempts,
            reason = RouteReason(
                code = when {
                    selected != null && loopResult.fallbackUsed -> "fallback"
                    selected != null -> "ok"
                    else -> "no_candidate"
                },
                message = selected?.reason?.message ?: "no viable candidate",
            ),
        )
    }

    private fun directHostedFallback(
        routeId: String,
        parsedRef: ParsedModelRef,
        policy: String?,
        plannerSource: String,
        attemptResult: AttemptLoopResult<Unit>? = null,
    ): RoutingDecisionResult {
        val metadata = RouteMetadata(
            status = "selected",
            execution = RouteExecution(
                locality = "cloud",
                mode = "hosted_gateway",
            ),
            model = RouteModel(
                requested = RouteModelRequested(
                    ref = parsedRef.ref,
                    kind = parsedRef.kind,
                ),
            ),
            planner = PlannerInfo(source = plannerSource),
            fallback = FallbackInfo(
                used = attemptResult?.fallbackUsed ?: false,
                fromAttempt = attemptResult?.fromAttempt,
                toAttempt = attemptResult?.toAttempt,
                trigger = attemptResult?.fallbackTrigger,
            ),
            attempts = attemptResult?.attempts ?: emptyList(),
            reason = RouteReason(
                code = if (attemptResult?.fallbackUsed == true) "fallback" else "no_plan",
                message = if (attemptResult?.fallbackUsed == true) "all candidates failed"
                else "no plan available; using hosted gateway",
            ),
        )

        return RoutingDecisionResult(
            locality = "cloud",
            mode = "hosted_gateway",
            routeMetadata = metadata,
            attemptResult = attemptResult ?: AttemptLoopResult(),
        )
    }

    companion object {
        /**
         * Build candidate list from a plan response.
         *
         * Primary candidates first, then fallback candidates.
         */
        fun candidatesFromPlan(plan: RuntimePlanResponse): List<RuntimeCandidatePlan> {
            return plan.candidates + plan.fallbackCandidates
        }

        /**
         * Whether the given routing policy allows fallback to cloud.
         */
        fun isFallbackAllowed(policy: String?): Boolean {
            if (policy == null) return true
            return when (policy) {
                "local_only", "private" -> false
                else -> true
            }
        }
    }
}
