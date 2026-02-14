package ai.edgeml

import ai.edgeml.models.CachedModel
import ai.edgeml.training.TFLiteTrainer
import android.content.Context
import java.io.File

/**
 * Local-first entry point for EdgeML inference.
 *
 * Load and run ML models without any server dependency:
 * ```kotlin
 * val model = EdgeML.loadModel(context, "classifier.tflite")
 * val result = model.runInference(floatArrayOf(1f, 2f, 3f)).getOrThrow()
 * ```
 *
 * **Upgrade path:** When ready for federated learning, analytics, or model
 * management, create an `EdgeMLClient` with matching model ID and server credentials.
 * The [LocalModel.cachedModel] property bridges local and server workflows.
 */
object EdgeML {

    /**
     * Load a TFLite model from the app's assets directory.
     *
     * The asset file is copied to the app's cache directory on first load.
     *
     * @param context Android context for asset access and cache directory.
     * @param assetFileName Name of the model file in the `assets/` directory (e.g., `"classifier.tflite"`).
     * @param options Hardware acceleration and threading options.
     * @return A [LocalModel] ready for inference.
     * @throws IllegalArgumentException if the asset cannot be found or copied.
     */
    suspend fun loadModel(
        context: Context,
        assetFileName: String,
        options: LocalModelOptions = LocalModelOptions(),
    ): LocalModel {
        val file = copyAssetToCache(context, assetFileName)
        return loadModel(context, file, options)
    }

    /**
     * Load a TFLite model from a file on disk.
     *
     * @param context Android context for TFLite initialization.
     * @param modelFile Path to the `.tflite` model file.
     * @param options Hardware acceleration and threading options.
     * @return A [LocalModel] ready for inference.
     * @throws IllegalArgumentException if the model file does not exist.
     * @throws Exception if model loading or warmup fails.
     */
    suspend fun loadModel(
        context: Context,
        modelFile: File,
        options: LocalModelOptions = LocalModelOptions(),
    ): LocalModel {
        require(modelFile.exists()) { "Model file not found: ${modelFile.absolutePath}" }

        val name = modelFile.nameWithoutExtension
        val config = options.toInternalConfig()

        val cached = CachedModel(
            modelId = name,
            version = "local",
            filePath = modelFile.absolutePath,
            checksum = "",
            sizeBytes = modelFile.length(),
            format = "tensorflow_lite",
            downloadedAt = System.currentTimeMillis(),
        )

        val trainer = TFLiteTrainer(context, config)
        trainer.loadModel(cached).getOrThrow()
        trainer.warmup()

        return LocalModel(cached, trainer, modelFile)
    }

    /**
     * Copy an asset file to the app's cache directory if it doesn't already exist.
     */
    private fun copyAssetToCache(context: Context, assetFileName: String): File {
        val cacheDir = File(context.cacheDir, "edgeml_local_models").apply { mkdirs() }
        val outFile = File(cacheDir, assetFileName)

        if (!outFile.exists()) {
            context.assets.open(assetFileName).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        return outFile
    }
}
