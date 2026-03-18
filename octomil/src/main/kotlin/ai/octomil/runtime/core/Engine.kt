package ai.octomil.runtime.core

/**
 * Inference engine for model execution.
 *
 * Each variant maps to a concrete runtime backend. The [wireValue]
 * is the canonical string used in server overrides and persistence.
 */
enum class Engine(val wireValue: String) {
    /** Auto-detect engine from model file extension. */
    AUTO("auto"),
    /** TensorFlow Lite engine. */
    TFLITE("tflite"),
    /** llama.cpp engine (GGUF models). */
    LLAMA_CPP("llama_cpp");

    companion object {
        /**
         * Parse a wire-format string into an [Engine].
         * Returns null for unrecognised values.
         */
        fun fromWireValue(value: String): Engine? =
            entries.firstOrNull { it.wireValue == value }
    }
}
