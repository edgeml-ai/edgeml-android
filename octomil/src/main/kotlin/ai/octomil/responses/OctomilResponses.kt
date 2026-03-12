package ai.octomil.responses

import ai.octomil.runtime.core.ModelRuntime
import ai.octomil.runtime.core.ModelRuntimeRegistry
import ai.octomil.runtime.core.RuntimeChunk
import ai.octomil.runtime.core.RuntimeRequest
import ai.octomil.runtime.core.RuntimeResponse
import ai.octomil.runtime.core.RuntimeToolCall
import ai.octomil.runtime.core.RuntimeToolDef
import ai.octomil.runtime.core.RuntimeUsage
import ai.octomil.wrapper.TelemetryQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

class OctomilResponses(
    private val runtimeResolver: ((String) -> ModelRuntime?)? = null,
) {
    private val responseCache = LinkedHashMap<String, Response>(100, 0.75f, true)

    suspend fun create(request: ResponseRequest): Response {
        val runtime = resolveRuntime(request.model)
        val effectiveRequest = applyConversationChaining(request)
        val runtimeRequest = buildRuntimeRequest(effectiveRequest)
        val runtimeResponse = runtime.run(runtimeRequest)
        val response = buildResponse(request.model, runtimeResponse)
        responseCache[response.id] = response
        return response
    }

    fun stream(request: ResponseRequest): Flow<ResponseStreamEvent> = flow {
        val runtime = resolveRuntime(request.model)
        val effectiveRequest = applyConversationChaining(request)
        val runtimeRequest = buildRuntimeRequest(effectiveRequest)
        val responseId = generateId()
        val textParts = mutableListOf<String>()
        val toolCallBuffers = mutableMapOf<Int, ToolCallBuffer>()
        var lastUsage: RuntimeUsage? = null
        var chunkIndex = 0

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

            // Emit inference.chunk_produced telemetry for every chunk
            try {
                TelemetryQueue.shared?.reportInferenceChunkProduced(
                    modelId = request.model,
                    chunkIndex = chunkIndex,
                )
            } catch (_: Exception) {
                // Telemetry must never crash the streaming flow
            }
            chunkIndex++

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

        val response = Response(
            id = responseId,
            model = request.model,
            output = output,
            finishReason = finishReason,
            usage = usage,
        )
        responseCache[response.id] = response
        emit(ResponseStreamEvent.Done(response))
    }

    private fun resolveRuntime(model: String): ModelRuntime {
        val runtime = runtimeResolver?.invoke(model)
            ?: ModelRuntimeRegistry.resolve(model)
            ?: throw IllegalStateException("No ModelRuntime registered for model: $model")
        return runtime
    }

    private fun applyConversationChaining(request: ResponseRequest): ResponseRequest {
        if (request.previousResponseId == null) return request
        val previous = responseCache[request.previousResponseId] ?: return request

        val assistantText = previous.output
            .filterIsInstance<OutputItem.Text>()
            .joinToString("") { it.text }
        val assistantToolCalls = previous.output
            .filterIsInstance<OutputItem.ToolCallItem>()
            .map { it.toolCall }

        val assistantItem = InputItem.Assistant(
            content = if (assistantText.isNotEmpty()) listOf(ContentPart.Text(assistantText)) else null,
            toolCalls = assistantToolCalls.ifEmpty { null },
        )

        return request.copy(input = listOf(assistantItem) + request.input)
    }

    private fun buildRuntimeRequest(request: ResponseRequest): RuntimeRequest {
        val effectiveInput = if (request.instructions != null) {
            listOf(InputItem.system(request.instructions)) + request.input
        } else {
            request.input
        }
        val prompt = PromptFormatter.format(effectiveInput, request.tools, request.toolChoice)
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
