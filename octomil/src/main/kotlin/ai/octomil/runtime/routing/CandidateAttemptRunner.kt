package ai.octomil.runtime.routing

import ai.octomil.runtime.planner.CandidateGate
import ai.octomil.runtime.planner.RuntimeCandidatePlan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Gate classification table — maps gate codes to their class, evaluation phase,
 * and blocking default. Mirrors the contract gate taxonomy.
 */
object GateClassification {
    data class GateInfo(val gateClass: String, val evaluationPhase: String, val blockingDefault: Boolean)

    val TABLE: Map<String, GateInfo> = mapOf(
        "artifact_verified" to GateInfo("readiness", "pre_inference", true),
        "runtime_available" to GateInfo("readiness", "pre_inference", true),
        "model_loads" to GateInfo("readiness", "pre_inference", true),
        "context_fits" to GateInfo("readiness", "pre_inference", true),
        "modality_supported" to GateInfo("readiness", "pre_inference", true),
        "tool_support" to GateInfo("readiness", "pre_inference", true),
        "min_tokens_per_second" to GateInfo("performance", "pre_inference", false),
        "max_ttft_ms" to GateInfo("performance", "during_inference", false),
        "max_error_rate" to GateInfo("performance", "pre_inference", false),
        "min_free_memory_bytes" to GateInfo("performance", "pre_inference", true),
        "min_free_storage_bytes" to GateInfo("performance", "pre_inference", true),
        "benchmark_fresh" to GateInfo("performance", "pre_inference", false),
        "schema_valid" to GateInfo("output_quality", "post_inference", true),
        "tool_call_valid" to GateInfo("output_quality", "post_inference", true),
        "safety_passed" to GateInfo("output_quality", "post_inference", true),
        "evaluator_score_min" to GateInfo("output_quality", "post_inference", false),
        "json_parseable" to GateInfo("output_quality", "post_inference", true),
        "max_refusal_rate" to GateInfo("output_quality", "post_inference", false),
    )

    fun classify(code: String): GateInfo = TABLE[code] ?: GateInfo("readiness", "pre_inference", true)
}

@Serializable
data class GateResult(
    @SerialName("code") val code: String,
    @SerialName("status") val status: String,
    @SerialName("observed_number") val observedNumber: Double? = null,
    @SerialName("threshold_number") val thresholdNumber: Double? = null,
    @SerialName("reason_code") val reasonCode: String? = null,
    @SerialName("gate_class") val gateClass: String? = null,
    @SerialName("evaluation_phase") val evaluationPhase: String? = null,
    @SerialName("required") val required: Boolean? = null,
    @SerialName("fallback_eligible") val fallbackEligible: Boolean? = null,
    @SerialName("observed_string") val observedString: String? = null,
    @SerialName("safe_metadata") val safeMetadata: Map<String, String>? = null,
)

@Serializable
data class AttemptArtifact(
    @SerialName("id") val id: String? = null,
    @SerialName("digest") val digest: String? = null,
    @SerialName("cache") val cache: Cache = Cache(),
) {
    @Serializable
    data class Cache(
        @SerialName("status") val status: String = "not_applicable",
        @SerialName("managed_by") val managedBy: String? = null,
    )
}

@Serializable
data class RouteAttempt(
    @SerialName("index") val index: Int,
    @SerialName("locality") val locality: String,
    @SerialName("mode") val mode: String,
    @SerialName("engine") val engine: String? = null,
    @SerialName("artifact") val artifact: AttemptArtifact? = null,
    @SerialName("status") val status: String,
    @SerialName("stage") val stage: String,
    @SerialName("gate_results") val gateResults: List<GateResult> = emptyList(),
    @SerialName("reason") val reason: AttemptReason,
)

@Serializable
data class AttemptReason(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String,
)

@Serializable
data class FallbackTrigger(
    @SerialName("code") val code: String,
    @SerialName("stage") val stage: String,
    @SerialName("message") val message: String,
    @SerialName("gate_code") val gateCode: String? = null,
    @SerialName("gate_class") val gateClass: String? = null,
    @SerialName("evaluation_phase") val evaluationPhase: String? = null,
    @SerialName("candidate_index") val candidateIndex: Int? = null,
    @SerialName("output_visible_before_failure") val outputVisibleBeforeFailure: Boolean? = null,
)

data class AttemptLoopResult<T>(
    val selectedAttempt: RouteAttempt? = null,
    val attempts: List<RouteAttempt> = emptyList(),
    val fallbackUsed: Boolean = false,
    val fallbackTrigger: FallbackTrigger? = null,
    val fromAttempt: Int? = null,
    val toAttempt: Int? = null,
    val value: T? = null,
    val error: Throwable? = null,
)

fun interface AttemptRuntimeChecker {
    fun check(engine: String?, locality: String): RuntimeCheck
}

data class RuntimeCheck(
    val available: Boolean,
    val reasonCode: String? = null,
)

fun interface AttemptGateEvaluator {
    fun evaluate(gate: CandidateGate, engine: String?, locality: String): GateResult
}

data class GateEvaluationResult(
    val passed: Boolean,
    val score: Double? = null,
    val reasonCode: String? = null,
    val safeMetadata: Map<String, String>? = null,
)

interface OutputQualityGateEvaluator {
    val name: String
    suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult
}

class CandidateAttemptRunner(
    private val fallbackAllowed: Boolean = true,
    private val streaming: Boolean = false,
) {
    fun shouldFallbackAfterInferenceError(firstOutputEmitted: Boolean = false): Boolean =
        fallbackAllowed && !(streaming && firstOutputEmitted)

    private fun enrichGateResult(result: GateResult, gate: CandidateGate): GateResult {
        val info = GateClassification.classify(gate.code)
        return result.copy(
            gateClass = result.gateClass ?: gate.gateClass ?: info.gateClass,
            evaluationPhase = result.evaluationPhase ?: gate.evaluationPhase ?: info.evaluationPhase,
            required = result.required ?: gate.required,
        )
    }

    private fun enrichGateResultByCode(result: GateResult): GateResult {
        val info = GateClassification.classify(result.code)
        return result.copy(
            gateClass = result.gateClass ?: info.gateClass,
            evaluationPhase = result.evaluationPhase ?: info.evaluationPhase,
        )
    }

    private fun isPostInferenceGate(gate: CandidateGate): Boolean {
        val phase = gate.evaluationPhase
            ?: GateClassification.classify(gate.code).evaluationPhase
        return phase == "post_inference"
    }

    fun run(
        candidates: List<RuntimeCandidatePlan>,
        runtimeChecker: AttemptRuntimeChecker = AttemptRuntimeChecker { _, _ -> RuntimeCheck(true) },
        gateEvaluator: AttemptGateEvaluator = AttemptGateEvaluator { gate, _, _ ->
            if (gate.required) {
                GateResult(code = gate.code, status = "unknown")
            } else {
                GateResult(code = gate.code, status = "not_required")
            }
        },
    ): AttemptLoopResult<Unit> {
        val attempts = mutableListOf<RouteAttempt>()
        var fallbackTrigger: FallbackTrigger? = null
        var fromAttempt: Int? = null

        for ((index, candidate) in candidates.withIndex()) {
            val engine = candidate.engine
            val locality = candidate.locality
            val mode = modeForLocality(locality)
            val gateResults = mutableListOf<GateResult>()
            val runtimeCheck = runtimeChecker.check(engine, locality)
            val artifact = candidate.artifact?.let {
                AttemptArtifact(
                    id = it.artifactId,
                    digest = it.digest,
                    cache = AttemptArtifact.Cache(status = "not_applicable"),
                )
            }

            if (!runtimeCheck.available) {
                gateResults += enrichGateResultByCode(
                    GateResult(
                        code = "runtime_available",
                        status = "failed",
                        reasonCode = runtimeCheck.reasonCode ?: "runtime_unavailable",
                    )
                )
                val attempt = RouteAttempt(
                    index = index,
                    locality = locality,
                    mode = mode,
                    engine = engine,
                    artifact = artifact,
                    status = "failed",
                    stage = "prepare",
                    gateResults = gateResults,
                    reason = AttemptReason(
                        code = "runtime_unavailable",
                        message = "${engine ?: locality} runtime unavailable",
                    ),
                )
                attempts += attempt
                if (fallbackTrigger == null) {
                    fallbackTrigger = FallbackTrigger(attempt.reason.code, attempt.stage, attempt.reason.message)
                    fromAttempt = index
                }
                if (!fallbackAllowed) break
                continue
            }

            gateResults += enrichGateResultByCode(
                GateResult(code = "runtime_available", status = "passed")
            )

            // Evaluate only pre-inference and during-inference gates; skip post_inference (output_quality)
            val preInferenceGates = candidate.gates
                .filterNot { it.code == "runtime_available" }
                .filterNot { isPostInferenceGate(it) }

            val failedGate = preInferenceGates
                .firstNotNullOfOrNull { gate ->
                    val result = enrichGateResult(gateEvaluator.evaluate(gate, engine, locality), gate)
                    gateResults += result
                    result.takeIf { gate.required && it.status == "failed" }
                }

            if (failedGate != null) {
                val attempt = RouteAttempt(
                    index = index,
                    locality = locality,
                    mode = mode,
                    engine = engine,
                    artifact = artifact,
                    status = "failed",
                    stage = "gate",
                    gateResults = gateResults,
                    reason = AttemptReason(code = "gate_failed", message = "${failedGate.code} gate failed"),
                )
                attempts += attempt
                if (fallbackTrigger == null) {
                    val info = GateClassification.classify(failedGate.code)
                    fallbackTrigger = FallbackTrigger(
                        code = attempt.reason.code,
                        stage = attempt.stage,
                        message = attempt.reason.message,
                        gateCode = failedGate.code,
                        gateClass = info.gateClass,
                        evaluationPhase = info.evaluationPhase,
                        candidateIndex = index,
                    )
                    fromAttempt = index
                }
                if (!fallbackAllowed) break
                continue
            }

            val selected = RouteAttempt(
                index = index,
                locality = locality,
                mode = mode,
                engine = engine,
                artifact = artifact,
                status = "selected",
                stage = "inference",
                gateResults = gateResults,
                reason = AttemptReason(code = "selected", message = "candidate selected"),
            )
            attempts += selected
            val usedFallback = fallbackTrigger != null
            return AttemptLoopResult(
                selectedAttempt = selected,
                attempts = attempts,
                fallbackUsed = usedFallback,
                fallbackTrigger = if (usedFallback) fallbackTrigger else null,
                fromAttempt = if (usedFallback) fromAttempt else null,
                toAttempt = if (usedFallback) index else null,
            )
        }

        return AttemptLoopResult(attempts = attempts)
    }

    suspend fun <T> runWithInference(
        candidates: List<RuntimeCandidatePlan>,
        runtimeChecker: AttemptRuntimeChecker = AttemptRuntimeChecker { _, _ -> RuntimeCheck(true) },
        gateEvaluator: AttemptGateEvaluator = AttemptGateEvaluator { gate, _, _ ->
            if (gate.required) {
                GateResult(code = gate.code, status = "unknown")
            } else {
                GateResult(code = gate.code, status = "not_required")
            }
        },
        outputQualityEvaluators: List<OutputQualityGateEvaluator> = emptyList(),
        firstOutputEmitted: () -> Boolean = { false },
        executeCandidate: suspend (RuntimeCandidatePlan, RouteAttempt) -> T,
    ): AttemptLoopResult<T> {
        val attempts = mutableListOf<RouteAttempt>()
        var fallbackTrigger: FallbackTrigger? = null
        var fromAttempt: Int? = null
        var lastError: Throwable? = null

        for ((index, candidate) in candidates.withIndex()) {
            val readiness = CandidateAttemptRunner(fallbackAllowed = false, streaming = streaming)
                .run(listOf(candidate), runtimeChecker, gateEvaluator)
            val selected = readiness.selectedAttempt
            if (selected == null) {
                val failed = readiness.attempts.firstOrNull()?.copy(index = index)
                if (failed != null) {
                    attempts += failed
                    if (fallbackTrigger == null) {
                        fallbackTrigger = FallbackTrigger(failed.reason.code, failed.stage, failed.reason.message)
                        fromAttempt = index
                    }
                }
                if (!fallbackAllowed) break
                continue
            }

            val indexedSelected = selected.copy(index = index)
            try {
                val value = executeCandidate(candidate, indexedSelected)

                // Evaluate post-inference output_quality gates
                val postInferenceGates = candidate.gates.filter { isPostInferenceGate(it) }
                val postGateResults = mutableListOf<GateResult>()
                var qualityFailure: GateResult? = null
                var qualityFailedGate: CandidateGate? = null

                for (gate in postInferenceGates) {
                    val evaluator = outputQualityEvaluators.firstOrNull { it.name == gate.code }
                    if (evaluator == null) {
                        if (gate.required) {
                            // Fail closed: required gate with no evaluator
                            val failResult = enrichGateResult(
                                GateResult(code = gate.code, status = "failed", reasonCode = "evaluator_missing"),
                                gate,
                            )
                            postGateResults += failResult
                            if (qualityFailure == null) {
                                qualityFailure = failResult
                                qualityFailedGate = gate
                            }
                        } else {
                            // Advisory gate with no evaluator — record unknown, continue
                            postGateResults += enrichGateResult(
                                GateResult(code = gate.code, status = "skipped", reasonCode = "no_evaluator"),
                                gate,
                            )
                        }
                        continue
                    }

                    val evalResult = evaluator.evaluate(gate, value as Any)
                    val gateResult = enrichGateResult(
                        GateResult(
                            code = gate.code,
                            status = if (evalResult.passed) "passed" else "failed",
                            observedNumber = evalResult.score,
                            thresholdNumber = gate.thresholdNumber,
                            reasonCode = evalResult.reasonCode,
                            safeMetadata = evalResult.safeMetadata,
                        ),
                        gate,
                    )
                    postGateResults += gateResult

                    if (!evalResult.passed && gate.required && qualityFailure == null) {
                        qualityFailure = gateResult
                        qualityFailedGate = gate
                    }
                }

                val allGateResults = indexedSelected.gateResults + postGateResults

                if (qualityFailure != null && qualityFailedGate != null) {
                    val emitted = firstOutputEmitted()
                    val outputVisible = streaming && emitted

                    if (outputVisible) {
                        // Output already visible to user — record failure but do NOT fallback
                        val failedAttempt = indexedSelected.copy(
                            status = "failed",
                            stage = "output_quality",
                            gateResults = allGateResults,
                            reason = AttemptReason(
                                code = "quality_gate_failed_output_visible",
                                message = "${qualityFailure.code} gate failed after output visible",
                            ),
                        )
                        attempts += failedAttempt
                        return AttemptLoopResult(
                            selectedAttempt = failedAttempt,
                            attempts = attempts,
                            fallbackUsed = fallbackTrigger != null,
                            fallbackTrigger = fallbackTrigger,
                            fromAttempt = if (fallbackTrigger != null) fromAttempt else null,
                            toAttempt = if (fallbackTrigger != null) index else null,
                            value = value,
                        )
                    }

                    // Output NOT visible — trigger fallback
                    val failedAttempt = indexedSelected.copy(
                        status = "failed",
                        stage = "output_quality",
                        gateResults = allGateResults,
                        reason = AttemptReason(
                            code = "quality_gate_failed",
                            message = "${qualityFailure.code} gate failed",
                        ),
                    )
                    attempts += failedAttempt
                    val info = GateClassification.classify(qualityFailure.code)
                    if (fallbackTrigger == null) {
                        fallbackTrigger = FallbackTrigger(
                            code = "quality_gate_failed",
                            stage = "output_quality",
                            message = "${qualityFailure.code} gate failed",
                            gateCode = qualityFailure.code,
                            gateClass = info.gateClass,
                            evaluationPhase = info.evaluationPhase,
                            candidateIndex = index,
                            outputVisibleBeforeFailure = false,
                        )
                        fromAttempt = index
                    }
                    if (!fallbackAllowed || index >= candidates.lastIndex) break
                    continue
                }

                // All quality gates passed (or advisory-only failures)
                val finalAttempt = indexedSelected.copy(gateResults = allGateResults)
                attempts += finalAttempt
                val usedFallback = fallbackTrigger != null
                return AttemptLoopResult(
                    selectedAttempt = finalAttempt,
                    attempts = attempts,
                    fallbackUsed = usedFallback,
                    fallbackTrigger = if (usedFallback) fallbackTrigger else null,
                    fromAttempt = if (usedFallback) fromAttempt else null,
                    toAttempt = if (usedFallback) index else null,
                    value = value,
                )
            } catch (error: Throwable) {
                lastError = error
                val emitted = firstOutputEmitted()
                val code = when {
                    streaming && emitted -> "inference_error_after_first_output"
                    streaming -> "inference_error_before_first_output"
                    else -> "inference_error"
                }
                val failed = indexedSelected.copy(
                    status = "failed",
                    stage = "inference",
                    reason = AttemptReason(code = code, message = error.message ?: error.toString()),
                )
                attempts += failed
                if (fallbackTrigger == null) {
                    fallbackTrigger = FallbackTrigger(code, "inference", failed.reason.message)
                    fromAttempt = index
                }
                if (index >= candidates.lastIndex || !shouldFallbackAfterInferenceError(emitted)) break
            }
        }

        return AttemptLoopResult(attempts = attempts, error = lastError)
    }

    private fun modeForLocality(locality: String): String =
        if (locality == "cloud") "hosted_gateway" else "sdk_runtime"
}
