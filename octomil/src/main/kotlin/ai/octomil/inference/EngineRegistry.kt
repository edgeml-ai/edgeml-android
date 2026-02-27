package ai.octomil.inference

import ai.octomil.Engine
import android.content.Context
import androidx.annotation.VisibleForTesting
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Composite key for engine lookup: modality + optional engine hint.
 * A null [engine] means "default for this modality".
 */
data class EngineKey(val modality: Modality, val engine: Engine?)

/**
 * Factory that creates a [StreamingInferenceEngine] given a context and optional model file.
 */
typealias EngineFactory = (context: Context, modelFile: File?) -> StreamingInferenceEngine

/**
 * Thrown when [EngineRegistry.resolve] cannot find a factory for the requested combination.
 */
class EngineResolutionException(message: String) : RuntimeException(message)

/**
 * Thread-safe singleton registry mapping (Modality, Engine?) to engine factories.
 *
 * Resolution chain: exact (modality, engine) match -> modality default (modality, null) -> throw.
 */
object EngineRegistry {

    private val factories = ConcurrentHashMap<EngineKey, EngineFactory>()

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
     * @return [Engine.TFLITE] for `.tflite`, null for unrecognised extensions.
     */
    fun engineFromFilename(filename: String): Engine? =
        when {
            filename.endsWith(".tflite", ignoreCase = true) -> Engine.TFLITE
            else -> null
        }

    /**
     * Clear all registrations and re-register defaults. Intended for testing.
     */
    fun reset() {
        factories.clear()
        registerDefaults()
    }

    /**
     * Clear all registrations without re-registering defaults. For testing only.
     */
    @VisibleForTesting
    fun clearAll() {
        factories.clear()
    }

    private fun registerDefaults() {
        register(Modality.TEXT) { ctx, _ -> LLMEngine(ctx) }
        register(Modality.IMAGE) { ctx, _ -> ImageEngine(ctx) }
        register(Modality.AUDIO) { ctx, _ -> AudioEngine(ctx) }
        register(Modality.VIDEO) { ctx, _ -> VideoEngine(ctx) }
    }
}
