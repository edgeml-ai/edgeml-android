package ai.octomil.runtime.core

/**
 * Inference engine for model execution.
 */
enum class Engine {
    /** Auto-detect engine from model file extension. */
    AUTO,
    /** TensorFlow Lite engine. */
    TFLITE,
}
