package ai.octomil.text

/**
 * Global registry for prediction runtimes.
 *
 * Wired by [ai.octomil.Octomil.init] — not set by app code.
 */
internal object PredictionRuntimeRegistry {
    var factory: (() -> PredictionRuntime)? = null
}
