package ai.octomil.runtime.routing

import ai.octomil.responses.OutputItem
import ai.octomil.responses.Response
import ai.octomil.runtime.core.RuntimeResponse
import ai.octomil.runtime.core.RuntimeToolCall
import ai.octomil.runtime.planner.CandidateGate
import kotlinx.serialization.json.Json

private val qualityJson = Json { ignoreUnknownKeys = true }

fun defaultOutputQualityEvaluators(): List<OutputQualityGateEvaluator> = listOf(
    JsonParseableOutputQualityEvaluator("json_parseable"),
    JsonParseableOutputQualityEvaluator("schema_valid"),
    ToolCallValidOutputQualityEvaluator(),
    RegexOutputQualityEvaluator(),
    SafetyPassedOutputQualityEvaluator(),
    RefusalOutputQualityEvaluator(),
)

private class JsonParseableOutputQualityEvaluator(
    override val name: String,
) : OutputQualityGateEvaluator {
    override suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult {
        val text = extractText(response) ?: return GateEvaluationResult(
            passed = false,
            reasonCode = "no_text_content",
            safeMetadata = mapOf("evaluator_name" to name),
        )
        return try {
            qualityJson.parseToJsonElement(text)
            GateEvaluationResult(passed = true, safeMetadata = mapOf("evaluator_name" to name))
        } catch (_: Throwable) {
            GateEvaluationResult(
                passed = false,
                reasonCode = "json_parse_error",
                safeMetadata = mapOf("evaluator_name" to name),
            )
        }
    }
}

private class ToolCallValidOutputQualityEvaluator : OutputQualityGateEvaluator {
    override val name = "tool_call_valid"

    override suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult {
        val calls = extractToolCalls(response)
        if (calls.isEmpty()) {
            return GateEvaluationResult(
                passed = false,
                reasonCode = "no_tool_calls",
                safeMetadata = mapOf("evaluator_name" to name),
            )
        }
        for (call in calls) {
            if (call.name.isBlank()) {
                return GateEvaluationResult(
                    passed = false,
                    reasonCode = "tool_call_missing_name",
                    safeMetadata = mapOf("evaluator_name" to name),
                )
            }
            try {
                qualityJson.parseToJsonElement(call.arguments)
            } catch (_: Throwable) {
                return GateEvaluationResult(
                    passed = false,
                    reasonCode = "tool_call_invalid_arguments",
                    safeMetadata = mapOf("evaluator_name" to name),
                )
            }
        }
        return GateEvaluationResult(passed = true, safeMetadata = mapOf("evaluator_name" to name))
    }
}

private class RegexOutputQualityEvaluator : OutputQualityGateEvaluator {
    override val name = "evaluator_score_min"

    override suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult {
        val pattern = gate.thresholdString
        if (pattern.isNullOrBlank()) {
            return GateEvaluationResult(
                passed = false,
                reasonCode = "no_pattern_configured",
                safeMetadata = mapOf("evaluator_name" to name),
            )
        }
        val text = extractText(response) ?: return GateEvaluationResult(
            passed = false,
            reasonCode = "no_text_content",
            safeMetadata = mapOf("evaluator_name" to name),
        )
        return try {
            val matched = Regex(pattern).containsMatchIn(text)
            GateEvaluationResult(
                passed = matched,
                score = if (matched) 1.0 else 0.0,
                reasonCode = if (matched) null else "pattern_not_matched",
                safeMetadata = mapOf("evaluator_name" to name),
            )
        } catch (_: Throwable) {
            GateEvaluationResult(
                passed = false,
                reasonCode = "invalid_regex_pattern",
                safeMetadata = mapOf("evaluator_name" to name),
            )
        }
    }
}

private class SafetyPassedOutputQualityEvaluator : OutputQualityGateEvaluator {
    override val name = "safety_passed"

    override suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult =
        GateEvaluationResult(
            passed = false,
            reasonCode = "no_safety_checker_configured",
            safeMetadata = mapOf("evaluator_name" to name),
        )
}

private class RefusalOutputQualityEvaluator : OutputQualityGateEvaluator {
    override val name = "max_refusal_rate"

    override suspend fun evaluate(gate: CandidateGate, response: Any): GateEvaluationResult {
        val text = extractText(response)?.lowercase() ?: return GateEvaluationResult(
            passed = false,
            reasonCode = "no_text_content",
            safeMetadata = mapOf("evaluator_name" to name),
        )
        val refused = listOf("i can't", "i cannot", "i'm unable", "i am unable", "sorry, but")
            .any { text.contains(it) }
        return GateEvaluationResult(
            passed = !refused,
            score = if (refused) 0.0 else 1.0,
            reasonCode = if (refused) "refusal_detected" else null,
            safeMetadata = mapOf("evaluator_name" to name),
        )
    }
}

private fun extractText(response: Any): String? = when (response) {
    is String -> response
    is RuntimeResponse -> response.text
    is Response -> response.outputText
    else -> null
}

private fun extractToolCalls(response: Any): List<RuntimeToolCall> = when (response) {
    is RuntimeResponse -> response.toolCalls.orEmpty()
    is Response -> response.output.filterIsInstance<OutputItem.ToolCallItem>()
        .map { RuntimeToolCall(it.toolCall.id, it.toolCall.name, it.toolCall.arguments) }
    else -> emptyList()
}
