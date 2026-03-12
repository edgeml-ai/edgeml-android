package ai.octomil.responses.tools

import ai.octomil.responses.InputItem
import ai.octomil.responses.OctomilResponses
import ai.octomil.responses.OutputItem
import ai.octomil.responses.Response
import ai.octomil.responses.ResponseRequest

class ToolRunner(
    private val responses: OctomilResponses,
    private val executor: ToolExecutor,
    private val maxIterations: Int = 10,
) {
    suspend fun run(request: ResponseRequest): Response {
        var currentInput = request.input.toMutableList()
        var iteration = 0

        while (iteration < maxIterations) {
            val currentRequest = request.copy(input = currentInput)
            val response = responses.create(currentRequest)

            val toolCalls = response.output
                .filterIsInstance<OutputItem.ToolCallItem>()
                .map { it.toolCall }

            if (toolCalls.isEmpty()) {
                return response
            }

            // Add assistant message with tool calls
            currentInput.add(
                InputItem.Assistant(toolCalls = toolCalls)
            )

            // Execute each tool call and add results
            for (call in toolCalls) {
                val result = try {
                    executor.execute(call)
                } catch (e: Exception) {
                    ToolResult(
                        toolCallId = call.id,
                        content = "Error: ${e.message}",
                        isError = true,
                    )
                }
                currentInput.add(
                    InputItem.ToolResult(
                        toolCallId = result.toolCallId,
                        content = result.content,
                    )
                )
            }

            iteration++
        }

        // Max iterations reached -- return the last response
        val finalRequest = request.copy(input = currentInput, tools = emptyList())
        return responses.create(finalRequest)
    }
}
