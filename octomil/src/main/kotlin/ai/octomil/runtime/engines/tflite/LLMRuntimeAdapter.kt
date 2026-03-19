package ai.octomil.runtime.engines.tflite

import ai.octomil.chat.GenerateConfig
import ai.octomil.chat.LLMRuntime
import ai.octomil.runtime.core.ChatMLRenderer
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
        supportsMultimodalInput = false,
        supportsStreaming = true,
    ),
) : ModelRuntime {

    override suspend fun run(request: RuntimeRequest): RuntimeResponse {
        val config = request.toGenerateConfig()
        val prompt = ChatMLRenderer.render(request)
        val tokens = mutableListOf<String>()
        var tokenCount = 0

        llmRuntime.generate(prompt, config).collect { token ->
            tokens.add(token)
            tokenCount++
        }

        val text = tokens.joinToString("")
        return RuntimeResponse(
            text = text,
            finishReason = "stop",
            usage = RuntimeUsage(
                promptTokens = estimateTokens(prompt),
                completionTokens = tokenCount,
                totalTokens = estimateTokens(prompt) + tokenCount,
            ),
        )
    }

    override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> {
        val config = request.toGenerateConfig()
        val prompt = ChatMLRenderer.render(request)
        return llmRuntime.generate(prompt, config).map { token ->
            RuntimeChunk(text = token)
        }
    }

    override fun close() {
        llmRuntime.close()
    }

    private fun RuntimeRequest.toGenerateConfig() = GenerateConfig(
        maxTokens = generationConfig.maxTokens,
        temperature = generationConfig.temperature,
        topP = generationConfig.topP,
        stop = generationConfig.stop,
    )

    private fun estimateTokens(text: String): Int =
        text.split("\\s+".toRegex()).size
}
