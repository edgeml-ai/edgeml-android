package ai.octomil.runtime.core

import ai.octomil.generated.Modality
import ai.octomil.runtime.engines.tflite.EngineRegistry
import ai.octomil.runtime.engines.tflite.StreamingInferenceEngine
import android.content.Context
import java.io.File

/**
 * Selects the best engine for a given model, considering overrides, benchmarks, and defaults.
 *
 * Resolution order:
 * 1. Server override for this model ID
 * 2. Server override for `"*"` (global)
 * 3. Local override for this model ID
 * 4. Local override for `"*"` (global)
 * 5. Persisted benchmark winner for this model + device
 * 6. Filename-inferred engine (file extension)
 * 7. Registry default for modality
 *
 * Thread-safe: all mutable state is guarded by `synchronized`.
 */
object RuntimeSelector {

    /** Server-pushed overrides. Key = model ID or `"*"` for global fallback. */
    private val serverOverrides = mutableMapOf<String, Engine>()

    /** Local configuration overrides. Key = model ID or `"*"` for global fallback. */
    private val localOverrides = mutableMapOf<String, Engine>()

    // MARK: - Configuration

    /**
     * Apply server-side engine overrides (from ControlSync).
     *
     * @param overrides Map of model ID (or `"*"`) to [Engine].
     */
    @Synchronized
    fun setServerOverrides(overrides: Map<String, Engine>) {
        serverOverrides.clear()
        serverOverrides.putAll(overrides)
    }

    /**
     * Apply local engine overrides (from OctomilConfig).
     *
     * @param overrides Map of model ID (or `"*"`) to [Engine].
     */
    @Synchronized
    fun setLocalOverrides(overrides: Map<String, Engine>) {
        localOverrides.clear()
        localOverrides.putAll(overrides)
    }

    // MARK: - Selection

    /**
     * Select the best engine for a given model.
     *
     * @param modelId The model identifier (e.g. "whisper-tiny", "llama-3.2-1b").
     * @param modality The output modality.
     * @param context Android context for engine construction.
     * @param modelFile Model file, used for filename-based engine inference and factory construction.
     * @param modelVersion Immutable artifact version from catalog, if known.
     * @param artifactDigest Pre-computed SHA-256 hex digest of the artifact, if known.
     * @param benchmarkStore The benchmark store to query. Defaults to [BenchmarkStore.instance].
     * @param registry The engine registry to resolve against. Defaults to [EngineRegistry].
     * @return A configured [StreamingInferenceEngine].
     * @throws ai.octomil.runtime.engines.tflite.EngineResolutionException if no matching factory is found.
     */
    fun selectEngine(
        modelId: String,
        modality: Modality,
        context: Context,
        modelFile: File? = null,
        modelVersion: String? = null,
        artifactDigest: String? = null,
        benchmarkStore: BenchmarkStore = BenchmarkStore.instance,
        registry: EngineRegistry = EngineRegistry,
    ): StreamingInferenceEngine {
        // 1-2. Server overrides (model-specific, then global)
        val serverEngine = synchronized(this) {
            serverOverrides[modelId] ?: serverOverrides["*"]
        }
        if (serverEngine != null) {
            return registry.resolve(modality, serverEngine, context, modelFile)
        }

        // 3-4. Local overrides (model-specific, then global)
        val localEngine = synchronized(this) {
            localOverrides[modelId] ?: localOverrides["*"]
        }
        if (localEngine != null) {
            return registry.resolve(modality, localEngine, context, modelFile)
        }

        // 5. Persisted benchmark winner
        val benchmarkEngine = benchmarkStore.winner(
            modelId = modelId,
            modelFilePath = modelFile?.absolutePath,
            modelVersion = modelVersion,
            artifactDigest = artifactDigest,
        )
        if (benchmarkEngine != null) {
            return registry.resolve(modality, benchmarkEngine, context, modelFile)
        }

        // 6-7. Filename inference + registry default
        val inferred = modelFile?.name?.let { EngineRegistry.engineFromFilename(it) }
        return registry.resolve(modality, inferred, context, modelFile)
    }

    /**
     * Clear all overrides. Intended for testing.
     */
    @Synchronized
    internal fun reset() {
        serverOverrides.clear()
        localOverrides.clear()
    }
}
