package ai.octomil.runtime.routing

import ai.octomil.runtime.planner.CandidateGate
import ai.octomil.runtime.planner.RuntimeCandidatePlan
import ai.octomil.runtime.planner.RuntimePlanResponse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestRouterTest {

    private val router = RequestRouter()

    // =========================================================================
    // Basic resolution — no plan → hosted gateway
    // =========================================================================

    @Test
    fun `no plan routes to hosted gateway`() {
        val result = router.resolve(
            RequestRoutingContext(model = "gemma-2b", capability = "chat"),
        )

        assertEquals("cloud", result.locality)
        assertEquals("hosted_gateway", result.mode)
        assertNotNull(result.routeMetadata)
        assertEquals("none", result.routeMetadata.planner.source)
        assertEquals("cloud", result.routeMetadata.execution?.locality)
        assertEquals("hosted_gateway", result.routeMetadata.execution?.mode)
    }

    // =========================================================================
    // Plan-backed resolution — selects first viable candidate
    // =========================================================================

    @Test
    fun `plan with local candidate selects local`() {
        val plan = testPlan(
            candidates = listOf(localCandidate(engine = "litert")),
        )

        val result = router.resolve(
            RequestRoutingContext(model = "gemma-2b", capability = "chat", cachedPlan = plan),
        )

        assertEquals("local", result.locality)
        assertEquals("sdk_runtime", result.mode)
        assertEquals("litert", result.routeMetadata.execution?.engine)
        assertEquals("cache", result.routeMetadata.planner.source)
    }

    @Test
    fun `plan with cloud candidate selects cloud`() {
        val plan = testPlan(
            candidates = listOf(cloudCandidate()),
        )

        val result = router.resolve(
            RequestRoutingContext(model = "gpt-4o", capability = "chat", cachedPlan = plan),
        )

        assertEquals("cloud", result.locality)
        assertEquals("hosted_gateway", result.mode)
    }

    // =========================================================================
    // Route metadata present on responses
    // =========================================================================

    @Test
    fun `route metadata is always populated`() {
        val result = router.resolve(
            RequestRoutingContext(model = "gemma-2b"),
        )

        val meta = result.routeMetadata
        assertNotNull(meta)
        assertNotNull(meta.execution)
        assertNotNull(meta.model)
        assertEquals("gemma-2b", meta.model.requested.ref)
        assertEquals("direct", meta.model.requested.kind)
        assertNotNull(meta.planner)
        assertNotNull(meta.fallback)
        assertNotNull(meta.reason)
    }

    @Test
    fun `route metadata records model ref kind for app refs`() {
        val result = router.resolve(
            RequestRoutingContext(model = "@app/my-app/chat"),
        )

        assertEquals("app", result.routeMetadata.model.requested.kind)
        assertEquals("@app/my-app/chat", result.routeMetadata.model.requested.ref)
    }

    @Test
    fun `route metadata records model ref kind for deployment refs`() {
        val result = router.resolve(
            RequestRoutingContext(model = "deploy_abc123"),
        )

        assertEquals("deployment", result.routeMetadata.model.requested.kind)
        assertEquals("deploy_abc123", result.routeMetadata.model.requested.ref)
    }

    @Test
    fun `route metadata records model ref kind for experiment refs`() {
        val result = router.resolve(
            RequestRoutingContext(model = "exp_v1/variant_a"),
        )

        assertEquals("deployment", result.routeMetadata.model.requested.kind)
        assertEquals("exp_v1/variant_a", result.routeMetadata.model.requested.ref)
    }

    @Test
    fun `route metadata records model ref kind for capability refs`() {
        val result = router.resolve(
            RequestRoutingContext(model = "@capability/embeddings"),
        )

        assertEquals("capability", result.routeMetadata.model.requested.kind)
        assertEquals("@capability/embeddings", result.routeMetadata.model.requested.ref)
    }

    // =========================================================================
    // Fallback scenarios
    // =========================================================================

    @Test
    fun `fallback from local to cloud when local fails gate`() {
        val plan = testPlan(
            candidates = listOf(
                localCandidate(engine = "litert", gates = listOf(
                    CandidateGate(code = "min_ram", required = true, thresholdNumber = 8_000_000_000.0),
                )),
                cloudCandidate(),
            ),
        )

        val result = router.resolve(
            context = RequestRoutingContext(
                model = "llama-8b",
                capability = "chat",
                cachedPlan = plan,
            ),
            gateEvaluator = AttemptGateEvaluator { gate, _, _ ->
                if (gate.code == "min_ram") {
                    GateResult(code = gate.code, status = "failed", observedNumber = 4_000_000_000.0, thresholdNumber = gate.thresholdNumber)
                } else {
                    GateResult(code = gate.code, status = "passed")
                }
            },
        )

        assertEquals("cloud", result.locality)
        assertEquals("hosted_gateway", result.mode)
        assertTrue(result.routeMetadata.fallback.used)
        assertNotNull(result.routeMetadata.fallback.trigger)
        assertEquals("gate_failed", result.routeMetadata.fallback.trigger?.code)
    }

    @Test
    fun `fallback from local to cloud when runtime unavailable`() {
        val plan = testPlan(
            candidates = listOf(
                localCandidate(engine = "litert"),
                cloudCandidate(),
            ),
        )

        val result = router.resolve(
            context = RequestRoutingContext(
                model = "gemma-2b",
                capability = "chat",
                cachedPlan = plan,
            ),
            runtimeChecker = AttemptRuntimeChecker { engine, locality ->
                if (locality == "local") RuntimeCheck(available = false, reasonCode = "not_installed")
                else RuntimeCheck(available = true)
            },
        )

        assertEquals("cloud", result.locality)
        assertTrue(result.routeMetadata.fallback.used)
    }

    @Test
    fun `local_only policy prevents fallback to cloud`() {
        val plan = testPlan(
            candidates = listOf(
                localCandidate(engine = "litert"),
                cloudCandidate(),
            ),
        )

        val result = router.resolve(
            context = RequestRoutingContext(
                model = "gemma-2b",
                capability = "chat",
                cachedPlan = plan,
                routingPolicy = "local_only",
            ),
            runtimeChecker = AttemptRuntimeChecker { _, locality ->
                if (locality == "local") RuntimeCheck(available = false, reasonCode = "not_installed")
                else RuntimeCheck(available = true)
            },
        )

        // Should NOT fall back to cloud
        assertEquals("local", result.locality)
        assertEquals("sdk_runtime", result.mode)
        assertFalse(result.routeMetadata.fallback.used)
    }

    // =========================================================================
    // Attempt stages: prepare → verify → gate → inference
    // =========================================================================

    @Test
    fun `attempt stages recorded in attempts list`() {
        val plan = testPlan(
            candidates = listOf(
                localCandidate(engine = "litert"),
                cloudCandidate(),
            ),
        )

        val result = router.resolve(
            context = RequestRoutingContext(
                model = "gemma-2b",
                capability = "chat",
                cachedPlan = plan,
            ),
            runtimeChecker = AttemptRuntimeChecker { _, locality ->
                if (locality == "local") RuntimeCheck(available = false, reasonCode = "engine_missing")
                else RuntimeCheck(available = true)
            },
        )

        // First attempt should show prepare-stage failure (runtime unavailable)
        val firstAttempt = result.attemptResult.attempts.firstOrNull()
        assertNotNull(firstAttempt)
        assertEquals("prepare", firstAttempt.stage)
        assertEquals("failed", firstAttempt.status)
    }

    // =========================================================================
    // Companion method tests
    // =========================================================================

    @Test
    fun `isFallbackAllowed returns false for local_only and private`() {
        assertFalse(RequestRouter.isFallbackAllowed("local_only"))
        assertFalse(RequestRouter.isFallbackAllowed("private"))
    }

    @Test
    fun `isFallbackAllowed returns true for other policies`() {
        assertTrue(RequestRouter.isFallbackAllowed("local_first"))
        assertTrue(RequestRouter.isFallbackAllowed("cloud_first"))
        assertTrue(RequestRouter.isFallbackAllowed("cloud_only"))
        assertTrue(RequestRouter.isFallbackAllowed("performance_first"))
        assertTrue(RequestRouter.isFallbackAllowed(null))
    }

    @Test
    fun `candidatesFromPlan merges primary and fallback candidates`() {
        val plan = RuntimePlanResponse(
            model = "test",
            capability = "chat",
            policy = "local_first",
            candidates = listOf(localCandidate()),
            fallbackCandidates = listOf(cloudCandidate()),
        )

        val candidates = RequestRouter.candidatesFromPlan(plan)
        assertEquals(2, candidates.size)
        assertEquals("local", candidates[0].locality)
        assertEquals("cloud", candidates[1].locality)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun testPlan(
        candidates: List<RuntimeCandidatePlan>,
        fallbackCandidates: List<RuntimeCandidatePlan> = emptyList(),
    ): RuntimePlanResponse = RuntimePlanResponse(
        model = "test",
        capability = "chat",
        policy = "local_first",
        candidates = candidates,
        fallbackCandidates = fallbackCandidates,
    )

    private fun localCandidate(
        engine: String = "litert",
        gates: List<CandidateGate> = emptyList(),
    ): RuntimeCandidatePlan = RuntimeCandidatePlan(
        locality = "local",
        priority = 0,
        confidence = 1.0,
        reason = "local candidate",
        engine = engine,
        gates = gates,
    )

    private fun cloudCandidate(): RuntimeCandidatePlan = RuntimeCandidatePlan(
        locality = "cloud",
        priority = 1,
        confidence = 0.9,
        reason = "cloud fallback",
        engine = "cloud",
    )
}
