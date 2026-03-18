package ai.octomil.runtime.engines

import ai.octomil.chat.LLMRuntime
import ai.octomil.chat.LLMRuntimeRegistry
import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.ArtifactResourceKind
import ai.octomil.manifest.ManifestResource
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
 * [ModelRuntime] backed by local model files resolved via resource bindings.
 *
 * When constructed with [resourceBindings], file resolution uses the explicit
 * kind-to-path mapping from the manifest rather than filename heuristics.
 * This supports multi-resource packages (weights + projector + sidecars).
 *
 * When constructed with just a [modelFile] (legacy path), delegates engine
 * selection to [LLMRuntimeRegistry].
 */
class LocalFileModelRuntime(
    private val modelFile: File,
    private val resourceBindings: Map<ArtifactResourceKind, File> = emptyMap(),
    private val engineConfig: Map<String, String> = emptyMap(),
) : ModelRuntime {

    /**
     * Construct from manifest resources, resolving URIs to local files
     * within the given [packageDir].
     */
    constructor(
        packageDir: File,
        resources: List<ManifestResource>,
        engineConfig: Map<String, String> = emptyMap(),
    ) : this(
        modelFile = resolveWeightsFile(packageDir, resources),
        resourceBindings = resolveBindings(packageDir, resources),
        engineConfig = engineConfig,
    )

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

    /** Get the resolved file for a specific resource kind, or null. */
    fun fileForKind(kind: ArtifactResourceKind): File? =
        resourceBindings[kind]

    /** The engine configuration passed through from the manifest. */
    fun engineConfig(): Map<String, String> = engineConfig

    companion object {
        private fun resolveWeightsFile(
            packageDir: File,
            resources: List<ManifestResource>,
        ): File {
            val weightsResource = resources.firstOrNull { it.kind == ArtifactResourceKind.WEIGHTS }
                ?: throw OctomilException(
                    OctomilErrorCode.MODEL_NOT_FOUND,
                    "No weights resource in package resources",
                )
            val path = weightsResource.path ?: weightsResource.uri.substringAfterLast("/")
            return File(packageDir, path)
        }

        private fun resolveBindings(
            packageDir: File,
            resources: List<ManifestResource>,
        ): Map<ArtifactResourceKind, File> {
            return resources
                .sortedBy { it.loadOrder ?: Int.MAX_VALUE }
                .associate { resource ->
                    val path = resource.path ?: resource.uri.substringAfterLast("/")
                    resource.kind to File(packageDir, path)
                }
        }
    }
}
