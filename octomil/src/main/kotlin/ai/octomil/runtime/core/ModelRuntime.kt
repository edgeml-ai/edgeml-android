package ai.octomil.runtime.core

import kotlinx.coroutines.flow.Flow

/**
 * Any execution backend that satisfies [run]/[stream] — local or remote.
 *
 * A `ModelRuntime` abstracts a single inference backend behind a uniform
 * contract. Implementations may wrap on-device engines (TFLite, MediaPipe,
 * ONNX, llama.cpp), cloud endpoints, or hybrid routers that delegate to
 * either depending on policy and device state.
 *
 * The SDK never calls engine-specific APIs directly; all inference flows
 * through this interface so that runtimes can be swapped, composed (via
 * [RouterModelRuntime]), or decorated without touching call sites.
 *
 * ## Implementing a runtime
 *
 * ```kotlin
 * class MyLocalRuntime(private val engine: LLMEngine) : ModelRuntime {
 *     override val capabilities = RuntimeCapabilities(
 *         supportsToolCalls = false,
 *         supportsStreaming = true,
 *     )
 *     override suspend fun run(request: RuntimeRequest): RuntimeResponse { ... }
 *     override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> { ... }
 *     override fun close() { engine.release() }
 * }
 * ```
 *
 * Register via [ModelRuntimeRegistry] so [OctomilResponses][ai.octomil.responses.OctomilResponses]
 * can resolve it by model name at request time.
 */
interface ModelRuntime {
    val capabilities: RuntimeCapabilities
    suspend fun run(request: RuntimeRequest): RuntimeResponse
    fun stream(request: RuntimeRequest): Flow<RuntimeChunk>
    fun close()
}

typealias RuntimeFactory = (modelId: String) -> ModelRuntime?
