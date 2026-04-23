package ai.octomil.runtime.routing

import ai.octomil.runtime.planner.CandidateGate
import ai.octomil.runtime.planner.RuntimeCandidatePlan
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CandidateAttemptRunnerTest {
    @Test
    fun `runWithInference falls back after non-streaming inference error`() = runTest {
        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.runWithInference(
            candidates = listOf(localCandidate(), cloudCandidate()),
        ) { candidate, _ ->
            if (candidate.locality == "local") error("model load failed")
            "cloud-ok"
        }

        assertEquals("cloud-ok", result.value)
        assertTrue(result.fallbackUsed)
        assertEquals("inference_error", result.fallbackTrigger?.code)
        assertEquals("failed", result.attempts.first().status)
        assertEquals("cloud", result.selectedAttempt?.locality)
    }

    @Test
    fun `streaming inference does not fall back after first output`() = runTest {
        var emitted = false
        val runner = CandidateAttemptRunner(fallbackAllowed = true, streaming = true)
        val result = runner.runWithInference(
            candidates = listOf(localCandidate(), cloudCandidate()),
            firstOutputEmitted = { emitted },
        ) { _, _ ->
            emitted = true
            error("stream interrupted")
        }

        assertNull(result.selectedAttempt)
        assertFalse(result.fallbackUsed)
        assertEquals(1, result.attempts.size)
        assertEquals("inference_error_after_first_output", result.attempts.first().reason.code)
    }

    // -- Output Quality Gate Tests --

    @Test
    fun `output quality gate failure before output triggers fallback`() = runTest {
        val evaluator = object : OutputQualityGateEvaluator {
            override val name = "schema_valid"
            override suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult =
                GateEvaluationResult(passed = false, reasonCode = "invalid_schema")
        }

        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.runWithInference(
            candidates = listOf(
                localCandidateWithQualityGates(),
                cloudCandidate(),
            ),
            outputQualityEvaluators = listOf(evaluator),
        ) { candidate, _ ->
            if (candidate.locality == "local") "local-response" else "cloud-ok"
        }

        assertEquals("cloud-ok", result.value)
        assertTrue(result.fallbackUsed)
        assertEquals("quality_gate_failed", result.fallbackTrigger?.code)
        assertEquals("output_quality", result.fallbackTrigger?.stage)
        assertEquals("schema_valid", result.fallbackTrigger?.gateCode)
        assertEquals("output_quality", result.fallbackTrigger?.gateClass)
        assertEquals("post_inference", result.fallbackTrigger?.evaluationPhase)
        assertEquals(false, result.fallbackTrigger?.outputVisibleBeforeFailure)
        assertEquals("cloud", result.selectedAttempt?.locality)
    }

    @Test
    fun `output quality gate failure after first token does NOT fallback`() = runTest {
        var emitted = false
        val evaluator = object : OutputQualityGateEvaluator {
            override val name = "schema_valid"
            override suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult =
                GateEvaluationResult(passed = false, reasonCode = "invalid_schema")
        }

        val runner = CandidateAttemptRunner(fallbackAllowed = true, streaming = true)
        val result = runner.runWithInference(
            candidates = listOf(
                localCandidateWithQualityGates(),
                cloudCandidate(),
            ),
            outputQualityEvaluators = listOf(evaluator),
            firstOutputEmitted = { emitted },
        ) { candidate, _ ->
            if (candidate.locality == "local") {
                emitted = true
                "local-partial-response"
            } else {
                "cloud-ok"
            }
        }

        // Returns the local value despite quality gate failure because output was already visible
        assertEquals("local-partial-response", result.value)
        assertEquals("local", result.selectedAttempt?.locality)
        assertEquals("failed", result.selectedAttempt?.status)
        assertEquals("output_quality", result.selectedAttempt?.stage)
        assertEquals("quality_gate_failed_output_visible", result.selectedAttempt?.reason?.code)
    }

    @Test
    fun `advisory quality gate failure does not disqualify candidate`() = runTest {
        val evaluator = object : OutputQualityGateEvaluator {
            override val name = "evaluator_score_min"
            override suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult =
                GateEvaluationResult(passed = false, score = 0.3, reasonCode = "score_below_threshold")
        }

        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.runWithInference(
            candidates = listOf(
                localCandidateWithAdvisoryQualityGate(),
                cloudCandidate(),
            ),
            outputQualityEvaluators = listOf(evaluator),
        ) { candidate, _ ->
            if (candidate.locality == "local") "local-ok" else "cloud-ok"
        }

        // Advisory gate failure should NOT trigger fallback
        assertEquals("local-ok", result.value)
        assertFalse(result.fallbackUsed)
        assertEquals("local", result.selectedAttempt?.locality)

        // The gate result should still be recorded
        val qualityGateResult = result.selectedAttempt?.gateResults?.find { it.code == "evaluator_score_min" }
        assertNotNull(qualityGateResult)
        assertEquals("failed", qualityGateResult.status)
        assertEquals(0.3, qualityGateResult.observedNumber)
    }

    @Test
    fun `required quality gate with no evaluator fails closed and triggers fallback`() = runTest {
        // No evaluators registered — required gate should fail closed
        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.runWithInference(
            candidates = listOf(
                localCandidateWithQualityGates(),
                cloudCandidate(),
            ),
            outputQualityEvaluators = emptyList(),
        ) { candidate, _ ->
            if (candidate.locality == "local") "local-response" else "cloud-ok"
        }

        assertEquals("cloud-ok", result.value)
        assertTrue(result.fallbackUsed)
        assertEquals("quality_gate_failed", result.fallbackTrigger?.code)
        val failedGateResult = result.attempts.first().gateResults.find { it.code == "schema_valid" }
        assertNotNull(failedGateResult)
        assertEquals("failed", failedGateResult.status)
        assertEquals("evaluator_missing", failedGateResult.reasonCode)
    }

    @Test
    fun `all gate results include gateClass and evaluationPhase`() {
        val candidate = RuntimeCandidatePlan(
            locality = "local",
            priority = 0,
            confidence = 1.0,
            reason = "local",
            engine = "registered",
            gates = listOf(
                CandidateGate(code = "runtime_available", required = true),
                CandidateGate(code = "context_fits", required = true),
                CandidateGate(code = "min_tokens_per_second", required = false, thresholdNumber = 10.0),
            ),
        )

        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.run(
            candidates = listOf(candidate),
            gateEvaluator = AttemptGateEvaluator { gate, _, _ ->
                GateResult(code = gate.code, status = "passed")
            },
        )

        val selected = assertNotNull(result.selectedAttempt)
        for (gr in selected.gateResults) {
            assertNotNull(gr.gateClass, "gateClass should be set for ${gr.code}")
            assertNotNull(gr.evaluationPhase, "evaluationPhase should be set for ${gr.code}")
        }

        val runtimeGate = selected.gateResults.find { it.code == "runtime_available" }
        assertEquals("readiness", runtimeGate?.gateClass)
        assertEquals("pre_inference", runtimeGate?.evaluationPhase)

        val perfGate = selected.gateResults.find { it.code == "min_tokens_per_second" }
        assertEquals("performance", perfGate?.gateClass)
        assertEquals("pre_inference", perfGate?.evaluationPhase)
    }

    @Test
    fun `post_inference gates are skipped during pre-inference evaluation`() {
        val candidate = RuntimeCandidatePlan(
            locality = "local",
            priority = 0,
            confidence = 1.0,
            reason = "local",
            engine = "registered",
            gates = listOf(
                CandidateGate(code = "runtime_available", required = true),
                CandidateGate(code = "schema_valid", required = true, evaluationPhase = "post_inference"),
                CandidateGate(code = "json_parseable", required = true),
            ),
        )

        val evaluatedGates = mutableListOf<String>()
        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.run(
            candidates = listOf(candidate),
            gateEvaluator = AttemptGateEvaluator { gate, _, _ ->
                evaluatedGates += gate.code
                GateResult(code = gate.code, status = "passed")
            },
        )

        // Should be selected (post_inference gates skipped)
        val selected = assertNotNull(result.selectedAttempt)
        assertEquals("selected", selected.status)
        // schema_valid and json_parseable are both post_inference; neither should be evaluated
        assertFalse("schema_valid" in evaluatedGates)
        assertFalse("json_parseable" in evaluatedGates)
    }

    @Test
    fun `GateClassification classify returns correct info for known codes`() {
        val schemaInfo = GateClassification.classify("schema_valid")
        assertEquals("output_quality", schemaInfo.gateClass)
        assertEquals("post_inference", schemaInfo.evaluationPhase)
        assertTrue(schemaInfo.blockingDefault)

        val perfInfo = GateClassification.classify("min_tokens_per_second")
        assertEquals("performance", perfInfo.gateClass)
        assertEquals("pre_inference", perfInfo.evaluationPhase)
        assertFalse(perfInfo.blockingDefault)

        val readinessInfo = GateClassification.classify("model_loads")
        assertEquals("readiness", readinessInfo.gateClass)
        assertEquals("pre_inference", readinessInfo.evaluationPhase)
        assertTrue(readinessInfo.blockingDefault)
    }

    @Test
    fun `GateClassification classify returns default for unknown codes`() {
        val unknown = GateClassification.classify("unknown_gate_code")
        assertEquals("readiness", unknown.gateClass)
        assertEquals("pre_inference", unknown.evaluationPhase)
        assertTrue(unknown.blockingDefault)
    }

    // -- Helpers --

    private fun localCandidate(): RuntimeCandidatePlan =
        RuntimeCandidatePlan(
            locality = "local",
            priority = 0,
            confidence = 1.0,
            reason = "local",
            engine = "registered",
        )

    private fun localCandidateWithQualityGates(): RuntimeCandidatePlan =
        RuntimeCandidatePlan(
            locality = "local",
            priority = 0,
            confidence = 1.0,
            reason = "local",
            engine = "registered",
            gates = listOf(
                CandidateGate(
                    code = "schema_valid",
                    required = true,
                    gateClass = "output_quality",
                    evaluationPhase = "post_inference",
                ),
            ),
        )

    private fun localCandidateWithAdvisoryQualityGate(): RuntimeCandidatePlan =
        RuntimeCandidatePlan(
            locality = "local",
            priority = 0,
            confidence = 1.0,
            reason = "local",
            engine = "registered",
            gates = listOf(
                CandidateGate(
                    code = "evaluator_score_min",
                    required = false,
                    thresholdNumber = 0.7,
                    gateClass = "output_quality",
                    evaluationPhase = "post_inference",
                ),
            ),
        )

    private fun cloudCandidate(): RuntimeCandidatePlan =
        RuntimeCandidatePlan(
            locality = "cloud",
            priority = 1,
            confidence = 1.0,
            reason = "cloud",
            engine = "cloud",
        )
}
