package ai.octomil.runtime.engines

import ai.octomil.chat.LLMRuntime
import ai.octomil.chat.LLMRuntimeRegistry
import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.runtime.core.ModelRuntime
import ai.octomil.runtime.core.RuntimeCapabilities
import ai.octomil.runtime.core.RuntimeChunk
import ai.octomil.runtime.core.RuntimeRequest
import ai.octomil.runtime.core.RuntimeResponse
import ai.octomil.runtime.core.RuntimeUsage
import ai.octomil.runtime.engines.tflite.LLMRuntimeAdapter
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * [ModelRuntime] backed by a local model file.
 *
 * Delegates engine selection to [LLMRuntimeRegistry] — this class does not
 * know or care whether the file is GGUF, TFLite, ONNX, or anything else.
 * The registry's factory inspects the file and returns the appropriate
 * [LLMRuntime] implementation.
 *
 * If no factory is registered (or it returns null for this file), all calls
 * throw [OctomilException] with [OctomilErrorCode.RUNTIME_UNAVAILABLE].
 */
class LocalFileModelRuntime(
    private val modelFile: File,
) : ModelRuntime {

    private val delegate: ModelRuntime by lazy {
        val llm = LLMRuntimeRegistry.factory?.invoke(modelFile)
            ?: throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "No LLMRuntime factory registered for file: ${modelFile.name}",
            )
        LLMRuntimeAdapter(llm)
    }

    override val capabilities: RuntimeCapabilities
        get() = delegate.capabilities

    override suspend fun run(request: RuntimeRequest): RuntimeResponse =
        delegate.run(request)

    override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> =
        delegate.stream(request)

    override fun close() {
        delegate.close()
    }
}
