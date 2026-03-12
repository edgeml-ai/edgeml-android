package ai.octomil.workflows

import ai.octomil.chat.Tool
import ai.octomil.responses.Response

data class Workflow(
    val name: String,
    val steps: List<WorkflowStep>,
)

sealed interface WorkflowStep {
    data class Inference(
        val model: String,
        val instructions: String? = null,
        val maxOutputTokens: Int? = null,
    ) : WorkflowStep

    data class ToolRound(
        val tools: List<Tool>,
        val model: String,
        val maxIterations: Int = 5,
    ) : WorkflowStep

    data class Transform(
        val name: String,
        val transform: suspend (String) -> String,
    ) : WorkflowStep
}

data class WorkflowResult(
    val outputs: List<Response>,
    val totalLatencyMs: Long,
)
