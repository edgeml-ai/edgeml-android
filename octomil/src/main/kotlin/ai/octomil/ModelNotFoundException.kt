package ai.octomil

/**
 * Thrown when [Octomil.load] cannot find a model by name in any resolution source.
 *
 * The [message] includes actionable guidance on how to make the model available.
 *
 * @property modelName The model name that was searched for.
 */
class ModelNotFoundException(
    val modelName: String,
    message: String = buildMessage(modelName),
) : Exception(message) {

    companion object {
        private fun buildMessage(name: String): String =
            "Model '$name' not found. Searched:\n" +
                "  1. Paired models: filesDir/octomil_models/$name/\n" +
                "  2. Assets: assets/$name.tflite\n" +
                "  3. Cache: cacheDir/octomil_models/$name*\n" +
                "To fix:\n" +
                "  - Run 'octomil deploy --phone' to pair and deploy the model\n" +
                "  - Bundle $name.tflite in your app's assets/ directory\n" +
                "  - Use OctomilClient to download the model from the server"
    }
}
