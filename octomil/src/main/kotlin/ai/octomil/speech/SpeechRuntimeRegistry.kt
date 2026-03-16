package ai.octomil.speech

import java.io.File

/**
 * Global registry for speech runtime factories.
 *
 * Apps or SDK modules register their STT runtime at initialization.
 * The Octomil SDK uses it when creating audio sessions.
 *
 * ```kotlin
 * // In Octomil.init():
 * SpeechRuntimeRegistry.factory = { modelDir ->
 *     SherpaStreamingRuntime(modelDir)
 * }
 * ```
 */
object SpeechRuntimeRegistry {
    /**
     * Factory that creates a [SpeechRuntime] for a given model directory.
     *
     * The directory contains engine-specific model files (encoder, decoder,
     * joiner, tokens, etc.). The factory inspects the contents and builds
     * the appropriate runtime.
     *
     * Set this before calling [ai.octomil.Octomil]`.audio`. If null,
     * audio operations will throw [IllegalStateException].
     */
    var factory: ((modelDir: File) -> SpeechRuntime)? = null
}
