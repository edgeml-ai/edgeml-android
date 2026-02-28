package ai.octomil.inference

import android.content.Context

/**
 * Plugin interface for inference engines.
 *
 * Implementations provide device detection, model compatibility checks,
 * benchmarking, and engine instantiation for a specific runtime backend.
 */
interface EnginePlugin {
    /** Unique identifier for this engine (e.g. "tflite", "nnapi"). */
    val name: String

    /** Human-readable name shown in dashboards. Defaults to [name]. */
    val displayName: String get() = name

    /** Lower values are tried first during auto-selection. Default 100. */
    val priority: Int get() = 100

    /** Return true if this engine's runtime is available on the current device. */
    fun detect(context: Context): Boolean

    /** Optional human-readable info about detection (e.g. delegate version). */
    fun detectInfo(context: Context): String = ""

    /** Return true if this engine can run the given model. */
    fun supportsModel(modelName: String): Boolean

    /** Run a quick benchmark and return the result. */
    fun benchmark(context: Context, modelName: String, nTokens: Int = 32): BenchmarkResult

    /** Create a streaming inference engine for the given model. */
    fun createEngine(context: Context, modelName: String): StreamingInferenceEngine
}
