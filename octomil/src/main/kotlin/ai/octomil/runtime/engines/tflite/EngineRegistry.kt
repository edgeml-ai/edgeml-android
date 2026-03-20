package ai.octomil.runtime.engines.tflite

import ai.octomil.generated.Modality
import ai.octomil.runtime.core.Engine
import android.content.Context
import androidx.annotation.VisibleForTesting
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Composite key for engine lookup: modality + optional engine hint.
 * A null [engine] means "default for this modality".
 */
internal data class EngineKey(val modality: Modality, val engine: Engine?)

/**
 * Factory that creates a [StreamingInferenceEngine] given a context and optional model file.
 */
typealias EngineFactory = (context: Context, modelFile: File?) -> StreamingInferenceEngine

/**
 * Thrown when [EngineRegistry.resolve] cannot find a factory for the requested combination.
 *
 * Extends [ai.octomil.errors.OctomilException] with [ai.octomil.errors.OctomilErrorCode.RUNTIME_UNAVAILABLE].
 */
class EngineResolutionException(message: String) : ai.octomil.errors.OctomilException(
    ai.octomil.errors.OctomilErrorCode.RUNTIME_UNAVAILABLE, message,
)

/**
 * Thread-safe singleton registry mapping (Modality, Engine?) to engine factories.
 *
 * Resolution chain: exact (modality, engine) match -> modality default (modality, null) -> throw.
 */
object EngineRegistry {

    private val factories = ConcurrentHashMap<EngineKey, EngineFactory>()
    private val plugins = CopyOnWriteArrayList<EnginePlugin>()

    init {
        registerDefaults()
    }

    /**
     * Register a factory for a given modality and optional engine hint.
     * Pass [engine] = null to register the default factory for a modality.
     */
    fun register(modality: Modality, engine: Engine? = null, factory: EngineFactory) {
        factories[EngineKey(modality, engine)] = factory
    }

    /**
     * Resolve an engine for the given modality and engine hint.
     *
     * Resolution order:
     * 1. Exact match on (modality, engine)
     * 2. Modality default (modality, null)
     * 3. Throw [EngineResolutionException]
     */
    fun resolve(
        modality: Modality,
        engine: Engine? = null,
        context: Context,
        modelFile: File? = null,
    ): StreamingInferenceEngine {
        val exactKey = EngineKey(modality, engine)
        factories[exactKey]?.let { return it(context, modelFile) }

        if (engine != null) {
            val defaultKey = EngineKey(modality, null)
            factories[defaultKey]?.let { return it(context, modelFile) }
        }

        throw EngineResolutionException(
            "No engine registered for modality=$modality, engine=$engine"
        )
    }

    /**
     * Detect the engine type from a model filename extension.
     *
     * - `.tflite` -> [Engine.TFLITE]
     * - `.gguf`   -> [Engine.LLAMA_CPP]
     * - anything else -> `null`
     */
    fun engineFromFilename(filename: String): Engine? =
        when {
            filename.endsWith(".tflite", ignoreCase = true) -> Engine.TFLITE
            filename.endsWith(".gguf", ignoreCase = true) -> Engine.LLAMA_CPP
            else -> null
        }

    /**
     * Clear all registrations and re-register defaults. Intended for testing.
     */
    fun reset() {
        factories.clear()
        plugins.clear()
        registerDefaults()
    }

    /**
     * Clear all registrations without re-registering defaults. For testing only.
     */
    @VisibleForTesting
    fun clearAll() {
        factories.clear()
        plugins.clear()
    }

    /**
     * Register an [EnginePlugin] for detection and benchmarking.
     */
    fun registerPlugin(plugin: EnginePlugin) {
        plugins.add(plugin)
    }

    /**
     * Detect which registered plugins are available on this device.
     *
     * @return One [DetectionResult] per registered plugin, sorted by plugin priority (ascending).
     */
    fun detectAll(modality: Modality, context: Context): List<DetectionResult> {
        return plugins
            .sortedBy { it.priority }
            .map { plugin ->
                val available = try {
                    plugin.detect(context)
                } catch (_: Exception) {
                    false
                }
                val info = try {
                    plugin.detectInfo(context)
                } catch (_: Exception) {
                    ""
                }
                DetectionResult(
                    engine = plugin.name,
                    available = available,
                    info = info,
                )
            }
    }

    /**
     * Benchmark all available plugins and return results ranked by tokens per second (descending).
     */
    suspend fun benchmarkAll(
        modality: Modality,
        context: Context,
        modelName: String,
        nTokens: Int = 32,
    ): List<RankedEngine> {
        return plugins
            .sortedBy { it.priority }
            .filter { plugin ->
                try {
                    plugin.detect(context) && plugin.supportsModel(modelName)
                } catch (_: Exception) {
                    false
                }
            }
            .map { plugin ->
                val result = try {
                    plugin.benchmark(context, modelName, nTokens)
                } catch (e: Exception) {
                    BenchmarkResult(
                        engineName = plugin.name,
                        tokensPerSecond = 0.0,
                        ttftMs = 0.0,
                        memoryMb = 0.0,
                        error = e.message ?: "unknown error",
                    )
                }
                RankedEngine(engine = plugin.name, result = result)
            }
            .sortedByDescending { it.result.tokensPerSecond }
    }

    /**
     * Select the best engine from a ranked list: the first one with a successful result.
     */
    fun selectBest(ranked: List<RankedEngine>): RankedEngine? {
        return ranked.firstOrNull { it.result.ok }
    }

    private fun registerDefaults() {
        // TFLite engines registered both as modality default AND with explicit .TFLITE key
        // so RuntimeSelector can target TFLite by name for overrides/benchmarks.
        register(Modality.TEXT) { ctx, _ -> LLMEngine(ctx) }
        register(Modality.TEXT, Engine.TFLITE) { ctx, _ -> LLMEngine(ctx) }
        register(Modality.IMAGE) { ctx, _ -> ImageEngine(ctx) }
        register(Modality.IMAGE, Engine.TFLITE) { ctx, _ -> ImageEngine(ctx) }
        register(Modality.AUDIO) { ctx, _ -> AudioEngine(ctx) }
        register(Modality.AUDIO, Engine.TFLITE) { ctx, _ -> AudioEngine(ctx) }
        register(Modality.VIDEO) { ctx, _ -> VideoEngine(ctx) }
        register(Modality.VIDEO, Engine.TFLITE) { ctx, _ -> VideoEngine(ctx) }
    }
}
