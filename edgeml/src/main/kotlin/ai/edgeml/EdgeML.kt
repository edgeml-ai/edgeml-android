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
     * Deploy a model for inference with automatic engine detection.
     *
     * @param context Android context.
     * @param modelPath Asset filename (e.g., "mobilenet.tflite") or absolute file path.
     * @param engine Inference engine to use. Defaults to [Engine.AUTO].
     * @param name Human-readable model name. Defaults to filename without extension.
     * @param options Hardware acceleration options.
     * @return A [DeployedModel] ready for inference.
     */
    suspend fun deploy(
        context: Context,
        modelPath: String,
        engine: Engine = Engine.AUTO,
        name: String? = null,
        options: LocalModelOptions = LocalModelOptions(),
    ): DeployedModel {
        val resolvedName = name ?: modelPath.substringBeforeLast(".").substringAfterLast("/")
        val localModel = loadModel(context, modelPath, options)
        return DeployedModel(
            name = resolvedName,
            engine = resolveEngine(modelPath, engine),
            localModel = localModel,
        )
    }

    /**
     * Deploy a model for inference with automatic engine detection.
     *
     * @param context Android context.
     * @param modelFile Path to the model file on disk.
     * @param engine Inference engine to use. Defaults to [Engine.AUTO].
     * @param name Human-readable model name. Defaults to filename without extension.
     * @param options Hardware acceleration options.
     * @return A [DeployedModel] ready for inference.
     */
    suspend fun deploy(
        context: Context,
        modelFile: File,
        engine: Engine = Engine.AUTO,
        name: String? = null,
        options: LocalModelOptions = LocalModelOptions(),
    ): DeployedModel {
        val resolvedName = name ?: modelFile.nameWithoutExtension
        val localModel = loadModel(context, modelFile, options)
        return DeployedModel(
            name = resolvedName,
            engine = resolveEngine(modelFile.name, engine),
            localModel = localModel,
        )
    }

    private fun resolveEngine(filename: String, engine: Engine): Engine {
        if (engine != Engine.AUTO) return engine
        return when {
            filename.endsWith(".tflite", ignoreCase = true) -> Engine.TFLITE
            else -> Engine.TFLITE  // Default to TFLite on Android
        }
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
