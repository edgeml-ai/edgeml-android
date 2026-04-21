package ai.octomil.runtime.routing

import ai.octomil.runtime.planner.CandidateGate
import ai.octomil.runtime.planner.RuntimeCandidatePlan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GateResult(
    @SerialName("code") val code: String,
    @SerialName("status") val status: String,
    @SerialName("observed_number") val observedNumber: Double? = null,
    @SerialName("threshold_number") val thresholdNumber: Double? = null,
    @SerialName("reason_code") val reasonCode: String? = null,
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

class CandidateAttemptRunner(
    private val fallbackAllowed: Boolean = true,
    private val streaming: Boolean = false,
) {
    fun shouldFallbackAfterInferenceError(firstOutputEmitted: Boolean = false): Boolean =
        fallbackAllowed && !(streaming && firstOutputEmitted)

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
                gateResults += GateResult(
                    code = "runtime_available",
                    status = "failed",
                    reasonCode = runtimeCheck.reasonCode ?: "runtime_unavailable",
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

            gateResults += GateResult(code = "runtime_available", status = "passed")
            val failedGate = candidate.gates
                .filterNot { it.code == "runtime_available" }
                .firstNotNullOfOrNull { gate ->
                    val result = gateEvaluator.evaluate(gate, engine, locality)
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
                    fallbackTrigger = FallbackTrigger(attempt.reason.code, attempt.stage, attempt.reason.message)
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
                attempts += indexedSelected
                val usedFallback = fallbackTrigger != null
                return AttemptLoopResult(
                    selectedAttempt = indexedSelected,
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
