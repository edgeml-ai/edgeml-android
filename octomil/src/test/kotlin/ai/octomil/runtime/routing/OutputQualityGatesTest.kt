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

/**
 * Tests for the output quality gate taxonomy extension.
 *
 * Covers:
 * - GateClassification table correctness for all 18 gate codes
 * - Default classification for unknown codes
 * - Post-inference gate skipping during run()
 * - Quality gate failure triggering fallback (pre-output)
 * - Quality gate failure NOT triggering fallback (post-output visible)
 * - Advisory quality gate failures not disqualifying
 * - Required gate with no evaluator fails closed
 * - All gate results include gateClass and evaluationPhase
 */
class OutputQualityGatesTest {

    // -- GateClassification tests --

    @Test
    fun `classifyGate returns correct values for all 18 known codes`() {
        // Readiness gates
        val readinessCodes = listOf(
            "artifact_verified", "runtime_available", "model_loads",
            "context_fits", "modality_supported", "tool_support",
        )
        for (code in readinessCodes) {
            val info = GateClassification.classify(code)
            assertEquals("readiness", info.gateClass, "gateClass for $code")
            assertEquals("pre_inference", info.evaluationPhase, "evaluationPhase for $code")
            assertTrue(info.blockingDefault, "blockingDefault for $code")
        }

        // Performance gates
        val perfGates = mapOf(
            "min_tokens_per_second" to Triple("performance", "pre_inference", false),
            "max_ttft_ms" to Triple("performance", "during_inference", false),
            "max_error_rate" to Triple("performance", "pre_inference", false),
            "min_free_memory_bytes" to Triple("performance", "pre_inference", true),
            "min_free_storage_bytes" to Triple("performance", "pre_inference", true),
            "benchmark_fresh" to Triple("performance", "pre_inference", false),
        )
        for ((code, expected) in perfGates) {
            val info = GateClassification.classify(code)
            assertEquals(expected.first, info.gateClass, "gateClass for $code")
            assertEquals(expected.second, info.evaluationPhase, "evaluationPhase for $code")
            assertEquals(expected.third, info.blockingDefault, "blockingDefault for $code")
        }

        // Output quality gates
        val qualityGates = mapOf(
            "schema_valid" to true,
            "tool_call_valid" to true,
            "safety_passed" to true,
            "evaluator_score_min" to false,
            "json_parseable" to true,
            "max_refusal_rate" to false,
        )
        for ((code, blocking) in qualityGates) {
            val info = GateClassification.classify(code)
            assertEquals("output_quality", info.gateClass, "gateClass for $code")
            assertEquals("post_inference", info.evaluationPhase, "evaluationPhase for $code")
            assertEquals(blocking, info.blockingDefault, "blockingDefault for $code")
        }

        // Verify all 18 codes are in the table
        assertEquals(18, GateClassification.TABLE.size)
    }

    @Test
    fun `classifyGate returns readiness default for unknown codes`() {
        val unknown = GateClassification.classify("totally_unknown_gate")
        assertEquals("readiness", unknown.gateClass)
        assertEquals("pre_inference", unknown.evaluationPhase)
        assertTrue(unknown.blockingDefault)
    }

    // -- Pre-inference gate skipping --

    @Test
    fun `output quality gates skipped during run()`() {
        val candidate = RuntimeCandidatePlan(
            locality = "local",
            priority = 0,
            confidence = 1.0,
            reason = "local",
            engine = "registered",
            gates = listOf(
                CandidateGate(code = "runtime_available", required = true),
                CandidateGate(code = "schema_valid", required = true, evaluationPhase = "post_inference"),
                CandidateGate(code = "tool_call_valid", required = true),
                CandidateGate(code = "json_parseable", required = true),
            ),
        )

        val evaluatedCodes = mutableListOf<String>()
        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.run(
            candidates = listOf(candidate),
            gateEvaluator = AttemptGateEvaluator { gate, _, _ ->
                evaluatedCodes += gate.code
                GateResult(code = gate.code, status = "passed")
            },
        )

        assertNotNull(result.selectedAttempt)
        assertEquals("selected", result.selectedAttempt?.status)
        // All three output_quality gates should be skipped (schema_valid, tool_call_valid, json_parseable)
        assertFalse("schema_valid" in evaluatedCodes, "schema_valid should be skipped")
        assertFalse("tool_call_valid" in evaluatedCodes, "tool_call_valid should be skipped")
        assertFalse("json_parseable" in evaluatedCodes, "json_parseable should be skipped")
    }

    // -- Quality gate failure before output triggers fallback --

    @Test
    fun `quality gate failure before return triggers fallback`() = runTest {
        val evaluator = object : OutputQualityGateEvaluator {
            override val name = "schema_valid"
            override suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult =
                GateEvaluationResult(passed = false, reasonCode = "invalid_schema")
        }

        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.runWithInference(
            candidates = listOf(
                localCandidateWithQualityGates("schema_valid", required = true),
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

    // -- Quality gate failure after first token does NOT fallback --

    @Test
    fun `quality gate failure after first token does NOT fallback`() = runTest {
        var emitted = false
        val evaluator = object : OutputQualityGateEvaluator {
            override val name = "schema_valid"
            override suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult =
                GateEvaluationResult(passed = false, reasonCode = "invalid_schema")
        }

        val runner = CandidateAttemptRunner(fallbackAllowed = true, streaming = true)
        val result = runner.runWithInference(
            candidates = listOf(
                localCandidateWithQualityGates("schema_valid", required = true),
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

        // Returns local value despite quality gate failure because output was already visible
        assertEquals("local-partial-response", result.value)
        assertEquals("local", result.selectedAttempt?.locality)
        assertEquals("failed", result.selectedAttempt?.status)
        assertEquals("output_quality", result.selectedAttempt?.stage)
        assertEquals("quality_gate_failed_output_visible", result.selectedAttempt?.reason?.code)
    }

    // -- Advisory quality gate failure does not disqualify --

    @Test
    fun `advisory quality gate failure does not disqualify`() = runTest {
        val evaluator = object : OutputQualityGateEvaluator {
            override val name = "evaluator_score_min"
            override suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult =
                GateEvaluationResult(passed = false, score = 0.3, reasonCode = "score_below_threshold")
        }

        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.runWithInference(
            candidates = listOf(
                localCandidateWithQualityGates("evaluator_score_min", required = false, thresholdNumber = 0.7),
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

    // -- Required gate with no evaluator fails closed --

    @Test
    fun `required gate with no evaluator fails closed`() = runTest {
        // No evaluators registered — required gate should fail closed
        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.runWithInference(
            candidates = listOf(
                localCandidateWithQualityGates("schema_valid", required = true),
                cloudCandidate(),
            ),
            outputQualityEvaluators = emptyList(),
        ) { candidate, _ ->
            if (candidate.locality == "local") "local-response" else "cloud-ok"
        }

        // Should fallback to cloud because required gate failed closed
        assertEquals("cloud-ok", result.value)
        assertTrue(result.fallbackUsed)

        // The local attempt should have a failed gate result with evaluator_missing
        val localAttempt = result.attempts.find { it.locality == "local" }
        assertNotNull(localAttempt)
        assertEquals("failed", localAttempt.status)
        assertEquals("output_quality", localAttempt.stage)

        val failedGate = localAttempt.gateResults.find { it.code == "schema_valid" }
        assertNotNull(failedGate)
        assertEquals("failed", failedGate.status)
        assertEquals("evaluator_missing", failedGate.reasonCode)
    }

    @Test
    fun `advisory gate with no evaluator records skipped and continues`() = runTest {
        // No evaluators registered — advisory gate should be skipped
        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.runWithInference(
            candidates = listOf(
                localCandidateWithQualityGates("evaluator_score_min", required = false, thresholdNumber = 0.7),
            ),
            outputQualityEvaluators = emptyList(),
        ) { _, _ ->
            "local-ok"
        }

        // Should succeed — advisory gate is skipped, not failed
        assertEquals("local-ok", result.value)
        assertFalse(result.fallbackUsed)
        assertEquals("local", result.selectedAttempt?.locality)

        val skippedGate = result.selectedAttempt?.gateResults?.find { it.code == "evaluator_score_min" }
        assertNotNull(skippedGate)
        assertEquals("skipped", skippedGate.status)
        assertEquals("no_evaluator", skippedGate.reasonCode)
    }

    // -- All gate results include gateClass and evaluationPhase --

    @Test
    fun `all gate results include gateClass and evaluationPhase`() = runTest {
        val evaluator = object : OutputQualityGateEvaluator {
            override val name = "schema_valid"
            override suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult =
                GateEvaluationResult(passed = true)
        }

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
                CandidateGate(
                    code = "schema_valid",
                    required = true,
                    gateClass = "output_quality",
                    evaluationPhase = "post_inference",
                ),
            ),
        )

        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.runWithInference(
            candidates = listOf(candidate),
            gateEvaluator = AttemptGateEvaluator { gate, _, _ ->
                GateResult(code = gate.code, status = "passed")
            },
            outputQualityEvaluators = listOf(evaluator),
        ) { _, _ -> "ok" }

        assertNotNull(result.selectedAttempt)
        for (gr in result.selectedAttempt!!.gateResults) {
            assertNotNull(gr.gateClass, "gateClass should be set for ${gr.code}")
            assertNotNull(gr.evaluationPhase, "evaluationPhase should be set for ${gr.code}")
        }

        // Verify specific classifications
        val runtimeGate = result.selectedAttempt!!.gateResults.find { it.code == "runtime_available" }
        assertEquals("readiness", runtimeGate?.gateClass)
        assertEquals("pre_inference", runtimeGate?.evaluationPhase)

        val perfGate = result.selectedAttempt!!.gateResults.find { it.code == "min_tokens_per_second" }
        assertEquals("performance", perfGate?.gateClass)
        assertEquals("pre_inference", perfGate?.evaluationPhase)

        val qualityGate = result.selectedAttempt!!.gateResults.find { it.code == "schema_valid" }
        assertEquals("output_quality", qualityGate?.gateClass)
        assertEquals("post_inference", qualityGate?.evaluationPhase)
    }

    // -- RouteEvent telemetry fields --

    @Test
    fun `buildRouteEvent includes gateFailureCount from attempt results`() = runTest {
        val evaluator = object : OutputQualityGateEvaluator {
            override val name = "schema_valid"
            override suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult =
                GateEvaluationResult(passed = false, reasonCode = "invalid_schema")
        }

        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.runWithInference(
            candidates = listOf(
                localCandidateWithQualityGates("schema_valid", required = true),
                cloudCandidate(),
            ),
            outputQualityEvaluators = listOf(evaluator),
        ) { candidate, _ ->
            if (candidate.locality == "local") "local-response" else "cloud-ok"
        }

        val event = buildRouteEvent(
            requestId = "req-1",
            capability = "chat",
            attemptResult = result,
        )

        assertNotNull(event.gateFailureCount)
        assertTrue(event.gateFailureCount!! > 0)
        assertEquals(false, event.outputVisibleBeforeFailure)
    }

    @Test
    fun `buildRouteEvent has null gateFailureCount when no failures`() = runTest {
        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.runWithInference(
            candidates = listOf(localCandidate()),
        ) { _, _ -> "ok" }

        val event = buildRouteEvent(
            requestId = "req-2",
            capability = "chat",
            attemptResult = result,
        )

        assertNull(event.gateFailureCount)
        assertNull(event.outputVisibleBeforeFailure)
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

    private fun localCandidateWithQualityGates(
        gateCode: String,
        required: Boolean,
        thresholdNumber: Double? = null,
    ): RuntimeCandidatePlan =
        RuntimeCandidatePlan(
            locality = "local",
            priority = 0,
            confidence = 1.0,
            reason = "local",
            engine = "registered",
            gates = listOf(
                CandidateGate(
                    code = gateCode,
                    required = required,
                    thresholdNumber = thresholdNumber,
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
