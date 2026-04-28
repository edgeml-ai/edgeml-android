package ai.octomil.tts

/**
 * Process-wide registry for [TtsRuntimeFactory]. Mirrors the shape
 * of [ai.octomil.speech.SpeechRuntimeRegistry] and the iOS
 * `TtsRuntimeRegistry`. The optional sherpa-onnx Kokoro/VITS
 * artifact registers a factory at startup; the public TTS facade
 * (`Octomil.audio.speech.create`) reads through this registry so
 * the `main` source set has no compile-time dependency on the
 * sherpa-onnx native bindings.
 */
object TtsRuntimeRegistry {
    @Volatile
    var factory: TtsRuntimeFactory? = null
        private set

    /** Register the canonical TTS factory. Last write wins. */
    fun register(factory: TtsRuntimeFactory) {
        this.factory = factory
    }

    /** Test/teardown hook: drop the registered factory. */
    fun reset() {
        this.factory = null
    }
}
