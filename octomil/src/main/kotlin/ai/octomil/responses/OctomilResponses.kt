package ai.octomil.responses

import ai.octomil.responses.runtime.ModelRuntime
import ai.octomil.responses.runtime.ModelRuntimeRegistry
import ai.octomil.responses.runtime.RuntimeChunk
import ai.octomil.responses.runtime.RuntimeRequest
import ai.octomil.responses.runtime.RuntimeResponse
import ai.octomil.responses.runtime.RuntimeToolCall
import ai.octomil.responses.runtime.RuntimeToolDef
import ai.octomil.responses.runtime.RuntimeUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

class OctomilResponses(
    private val runtimeResolver: ((String) -> ModelRuntime?)? = null,
) {
    suspend fun create(request: ResponseRequest): Response {
        val runtime = resolveRuntime(request.model)
        val runtimeRequest = buildRuntimeRequest(request)
        val runtimeResponse = runtime.run(runtimeRequest)
        return buildResponse(request.model, runtimeResponse)
    }

    fun stream(request: ResponseRequest): Flow<ResponseStreamEvent> = flow {
        val runtime = resolveRuntime(request.model)
        val runtimeRequest = buildRuntimeRequest(request)
        val responseId = generateId()
        val textParts = mutableListOf<String>()
        val toolCallBuffers = mutableMapOf<Int, ToolCallBuffer>()
        var lastUsage: RuntimeUsage? = null

        runtime.stream(runtimeRequest).collect { chunk ->
            chunk.text?.let { text ->
                textParts.add(text)
                emit(ResponseStreamEvent.TextDelta(text))
            }

            chunk.toolCallDelta?.let { delta ->
                val buffer = toolCallBuffers.getOrPut(delta.index) { ToolCallBuffer() }
                if (delta.id != null) buffer.id = delta.id
                if (delta.name != null) buffer.name = delta.name
                if (delta.argumentsDelta != null) buffer.arguments.append(delta.argumentsDelta)

                emit(
                    ResponseStreamEvent.ToolCallDelta(
                        index = delta.index,
                        id = delta.id,
                        name = delta.name,
                        argumentsDelta = delta.argumentsDelta,
                    )
                )
            }

            if (chunk.usage != null) lastUsage = chunk.usage
        }

        val output = mutableListOf<OutputItem>()
        val fullText = textParts.joinToString("")
        if (fullText.isNotEmpty()) {
            output.add(OutputItem.Text(fullText))
        }
        for ((_, buffer) in toolCallBuffers.entries.sortedBy { it.key }) {
            output.add(
                OutputItem.ToolCallItem(
                    ResponseToolCall(
                        id = buffer.id ?: generateId(),
                        name = buffer.name ?: "",
                        arguments = buffer.arguments.toString(),
                    )
                )
            )
        }

        val finishReason = if (toolCallBuffers.isNotEmpty()) "tool_calls" else "stop"
        val usage = lastUsage?.let {
            ResponseUsage(it.promptTokens, it.completionTokens, it.totalTokens)
        }

        emit(
            ResponseStreamEvent.Done(
                Response(
                    id = responseId,
                    model = request.model,
                    output = output,
                    finishReason = finishReason,
                    usage = usage,
                )
            )
        )
    }

    private fun resolveRuntime(model: String): ModelRuntime {
        val runtime = runtimeResolver?.invoke(model)
            ?: ModelRuntimeRegistry.resolve(model)
            ?: throw IllegalStateException("No ModelRuntime registered for model: $model")
        return runtime
    }

    private fun buildRuntimeRequest(request: ResponseRequest): RuntimeRequest {
        val prompt = PromptFormatter.format(request.input, request.tools, request.toolChoice)
        val toolDefs = if (request.tools.isNotEmpty()) {
            request.tools.map { tool ->
                RuntimeToolDef(
                    name = tool.function.name,
                    description = tool.function.description,
                    parametersSchema = tool.function.parameters?.toString(),
                )
            }
        } else {
            null
        }

        val jsonSchema = when (val fmt = request.responseFormat) {
            is ResponseFormat.JsonSchema -> fmt.schema
            is ResponseFormat.JsonObject -> "{}"
            is ResponseFormat.Text -> null
        }

        return RuntimeRequest(
            prompt = prompt,
            maxTokens = request.maxOutputTokens ?: 512,
            temperature = request.temperature ?: 0.7f,
            topP = request.topP ?: 1.0f,
            stop = request.stop,
            toolDefinitions = toolDefs,
            jsonSchema = jsonSchema,
        )
    }

    private fun buildResponse(model: String, runtimeResponse: RuntimeResponse): Response {
        val output = mutableListOf<OutputItem>()

        if (runtimeResponse.text.isNotEmpty()) {
            output.add(OutputItem.Text(runtimeResponse.text))
        }

        runtimeResponse.toolCalls?.forEach { call ->
            output.add(
                OutputItem.ToolCallItem(
                    ResponseToolCall(
                        id = call.id,
                        name = call.name,
                        arguments = call.arguments,
                    )
                )
            )
        }

        val finishReason = if (runtimeResponse.toolCalls?.isNotEmpty() == true) {
            "tool_calls"
        } else {
            runtimeResponse.finishReason
        }

        val usage = runtimeResponse.usage?.let {
            ResponseUsage(it.promptTokens, it.completionTokens, it.totalTokens)
        }

        return Response(
            id = generateId(),
            model = model,
            output = output,
            finishReason = finishReason,
            usage = usage,
        )
    }

    private fun generateId(): String =
        "resp_${UUID.randomUUID().toString().replace("-", "").take(16)}"

    private class ToolCallBuffer {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}
