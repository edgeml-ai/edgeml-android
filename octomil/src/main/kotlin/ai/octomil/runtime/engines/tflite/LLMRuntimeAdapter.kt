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
import ai.octomil.runtime.planner.RuntimeEngineIds
import ai.octomil.runtime.planner.RuntimePlanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

internal class LLMRuntimeAdapter(
    private val llmRuntime: LLMRuntime,
    override val capabilities: RuntimeCapabilities = RuntimeCapabilities(
        supportsToolCalls = false,
        supportsStructuredOutput = false,
        supportsMultimodalInput = false,
        supportsStreaming = true,
    ),
    /** Optional planner for recording real benchmark metrics after execution. */
    internal var planner: RuntimePlanner? = null,
    /** Model name for benchmark recording. Typically set by the owning runtime. */
    internal var modelName: String? = null,
    /**
     * Engine identifier for benchmark recording.
     * Defaults to "tflite" but overridden to "llama.cpp" when backed by LlamaCppRuntime.
     */
    internal var engineId: String = "tflite",
) : ModelRuntime {

    override suspend fun run(request: RuntimeRequest): RuntimeResponse {
        val config = request.toGenerateConfig()
        val prompt = ChatMLRenderer.render(request)
        val tokens = mutableListOf<String>()
        var tokenCount = 0

        val startNanos = System.nanoTime()
        var firstTokenNanos = 0L

        llmRuntime.generate(prompt, config).collect { token ->
            if (tokenCount == 0) {
                firstTokenNanos = System.nanoTime()
            }
            tokens.add(token)
            tokenCount++
        }

        val endNanos = System.nanoTime()
        val text = tokens.joinToString("")

        // Record real benchmark from this execution if planner is wired.
        recordExecutionBenchmark(
            tokenCount = tokenCount,
            startNanos = startNanos,
            firstTokenNanos = firstTokenNanos,
            endNanos = endNanos,
        )

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

    /**
     * Record a real execution benchmark in the planner cache.
     *
     * Only called after a complete non-streaming inference run. No prompts,
     * responses, file paths, or user data are included -- only hardware
     * performance metrics.
     */
    private fun recordExecutionBenchmark(
        tokenCount: Int,
        startNanos: Long,
        firstTokenNanos: Long,
        endNanos: Long,
    ) {
        val p = planner ?: return
        val model = modelName ?: return
        if (tokenCount <= 0) return

        try {
            val totalMs = (endNanos - startNanos) / 1_000_000.0
            val ttftMs = if (firstTokenNanos > 0) {
                (firstTokenNanos - startNanos) / 1_000_000.0
            } else {
                0.0
            }
            val tokensPerSecond = if (totalMs > 0) {
                tokenCount * 1000.0 / totalMs
            } else {
                0.0
            }
            val memoryMb = (Runtime.getRuntime().totalMemory() -
                Runtime.getRuntime().freeMemory()) / (1024.0 * 1024.0)

            p.recordBenchmark(
                model = model,
                capability = "text",
                engine = RuntimeEngineIds.canonical(engineId),
                tokensPerSecond = tokensPerSecond,
                ttftMs = ttftMs,
                memoryMb = memoryMb,
                success = true,
            )
        } catch (e: Exception) {
            Timber.d(e, "Failed to record execution benchmark (non-fatal)")
        }
    }

    companion object {
        /**
         * Create an adapter from a [BenchmarkResult] for recording into the
         * planner's benchmark cache. Converts BenchmarkResult metrics into a
         * [RuntimePlanner.recordBenchmark] call.
         *
         * This bridges the existing pairing benchmark flow with the planner
         * cache, ensuring real benchmark data from warmup is available for
         * subsequent plan resolution.
         *
         * No prompts, responses, file paths, or user data are included.
         */
        fun recordBenchmarkResult(
            planner: RuntimePlanner,
            result: BenchmarkResult,
            modelName: String,
            capability: String = "text",
        ) {
            if (!result.ok) return

            try {
                planner.recordBenchmark(
                    model = modelName,
                    capability = capability,
                    engine = RuntimeEngineIds.canonical(result.engineName),
                    tokensPerSecond = result.tokensPerSecond,
                    ttftMs = result.ttftMs,
                    memoryMb = result.memoryMb,
                    success = true,
                )
            } catch (e: Exception) {
                Timber.d(e, "Failed to record benchmark result (non-fatal)")
            }
        }
    }
}
