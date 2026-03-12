package ai.octomil.workflows

import ai.octomil.responses.InputItem
import ai.octomil.responses.OctomilResponses
import ai.octomil.responses.OutputItem
import ai.octomil.responses.ResponseRequest
import ai.octomil.responses.tools.ToolExecutor
import ai.octomil.responses.tools.ToolRunner

/**
 * ADVANCED — MAY: Executes a [Workflow] by running each step in sequence,
 * piping text output from one step to the next.
 */
class WorkflowRunner(
    private val responses: OctomilResponses,
    private val executor: ToolExecutor? = null,
) {
    suspend fun run(workflow: Workflow, input: String): WorkflowResult {
        val startTime = System.currentTimeMillis()
        var currentText = input
        val outputs = mutableListOf<ai.octomil.responses.Response>()

        for (step in workflow.steps) {
            when (step) {
                is WorkflowStep.Inference -> {
                    val request = ResponseRequest(
                        model = step.model,
                        input = listOf(InputItem.text(currentText)),
                        instructions = step.instructions,
                        maxOutputTokens = step.maxOutputTokens,
                    )
                    val response = responses.create(request)
                    outputs.add(response)
                    currentText = response.output
                        .filterIsInstance<OutputItem.Text>()
                        .joinToString("") { it.text }
                }
                is WorkflowStep.ToolRound -> {
                    requireNotNull(executor) { "ToolExecutor required for ToolRound steps" }
                    val runner = ToolRunner(responses, executor, step.maxIterations)
                    val request = ResponseRequest(
                        model = step.model,
                        input = listOf(InputItem.text(currentText)),
                        tools = step.tools,
                    )
                    val response = runner.run(request)
                    outputs.add(response)
                    currentText = response.output
                        .filterIsInstance<OutputItem.Text>()
                        .joinToString("") { it.text }
                }
                is WorkflowStep.Transform -> {
                    currentText = step.transform(currentText)
                }
            }
        }

        val totalLatencyMs = System.currentTimeMillis() - startTime
        return WorkflowResult(outputs = outputs, totalLatencyMs = totalLatencyMs)
    }
}
