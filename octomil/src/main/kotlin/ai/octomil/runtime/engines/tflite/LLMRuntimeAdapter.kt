package ai.octomil.runtime.engines.tflite

import ai.octomil.chat.GenerateConfig
import ai.octomil.chat.LLMRuntime
import ai.octomil.runtime.core.ModelRuntime
import ai.octomil.runtime.core.RuntimeCapabilities
import ai.octomil.runtime.core.RuntimeChunk
import ai.octomil.runtime.core.RuntimeRequest
import ai.octomil.runtime.core.RuntimeResponse
import ai.octomil.runtime.core.RuntimeUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class LLMRuntimeAdapter(
    private val llmRuntime: LLMRuntime,
    override val capabilities: RuntimeCapabilities = RuntimeCapabilities(
        supportsToolCalls = false,
        supportsStructuredOutput = false,
        supportsMultimodalInput = llmRuntime.supportsVision() || llmRuntime.supportsAudio(),
        supportsStreaming = true,
    ),
) : ModelRuntime {

    override suspend fun run(request: RuntimeRequest): RuntimeResponse {
        val config = request.toGenerateConfig()
        val tokens = mutableListOf<String>()
        var tokenCount = 0

        val flow = if (request.mediaData != null) {
            llmRuntime.generateMultimodal(request.prompt, request.mediaData, config)
        } else {
            llmRuntime.generate(request.prompt, config)
        }

        flow.collect { token ->
            tokens.add(token)
            tokenCount++
        }

        val text = tokens.joinToString("")
        return RuntimeResponse(
            text = text,
            finishReason = "stop",
            usage = RuntimeUsage(
                promptTokens = estimateTokens(request.prompt),
                completionTokens = tokenCount,
                totalTokens = estimateTokens(request.prompt) + tokenCount,
            ),
        )
    }

    override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> {
        val config = request.toGenerateConfig()
        val flow = if (request.mediaData != null) {
            llmRuntime.generateMultimodal(request.prompt, request.mediaData, config)
        } else {
            llmRuntime.generate(request.prompt, config)
        }
        return flow.map { token -> RuntimeChunk(text = token) }
    }

    override fun close() {
        llmRuntime.close()
    }

    private fun RuntimeRequest.toGenerateConfig() = GenerateConfig(
        maxTokens = maxTokens,
        temperature = temperature,
        topP = topP,
        stop = stop,
    )

    private fun estimateTokens(text: String): Int =
        text.split("\\s+".toRegex()).size
}
