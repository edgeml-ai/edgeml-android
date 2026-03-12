package ai.octomil.responses.runtime

import kotlinx.coroutines.flow.Flow

interface ModelRuntime {
    val capabilities: RuntimeCapabilities
    suspend fun run(request: RuntimeRequest): RuntimeResponse
    fun stream(request: RuntimeRequest): Flow<RuntimeChunk>
    fun close()
}

typealias RuntimeFactory = (modelId: String) -> ModelRuntime?
