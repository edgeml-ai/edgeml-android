package ai.octomil.responses.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Cloud-based model runtime that delegates inference to a remote server.
 *
 * Uses OkHttp to make SSE requests to a cloud endpoint for chat completions.
 * Requires `serverUrl` and `apiKey` to be configured before use.
 */
class CloudModelRuntime(
    private val serverUrl: String,
    private val apiKey: String,
) : ModelRuntime {
    override val capabilities = RuntimeCapabilities(
        supportsToolCalls = true,
        supportsStructuredOutput = true,
        supportsStreaming = true,
    )

    override suspend fun run(request: RuntimeRequest): RuntimeResponse {
        // Real implementation would use OkHttp to POST to serverUrl + "/v1/chat/completions"
        // Body: {"model":"...", "messages":[{"role":"user","content":"..."}], "max_tokens":..., "stream":false}
        // Parse JSON response into RuntimeResponse
        throw UnsupportedOperationException("Cloud runtime not yet configured. Set serverUrl and apiKey.")
    }

    override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = flow {
        // Real implementation would use OkHttp SSE to stream from serverUrl + "/v1/chat/completions"
        throw UnsupportedOperationException("Cloud runtime not yet configured. Set serverUrl and apiKey.")
    }

    override fun close() {}
}
