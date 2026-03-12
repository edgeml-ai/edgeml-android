package ai.octomil

import ai.octomil.chat.LLMRuntimeRegistry
import ai.octomil.chat.OctomilChat
import ai.octomil.inference.EngineRegistry
import ai.octomil.inference.Modality
import ai.octomil.models.CachedModel
import ai.octomil.training.TFLiteTrainer
import android.content.Context
import java.io.File

/**
 * Local-first entry point for Octomil inference.
 *
 * Load and run ML models without any server dependency:
 * ```kotlin
 * val model = Octomil.loadModel(context, "classifier.tflite")
 * val result = model.runInference(floatArrayOf(1f, 2f, 3f)).getOrThrow()
 * ```
 *
 * **Upgrade path:** When ready for federated learning, analytics, or model
 * management, create an `OctomilClient` with matching model ID and server credentials.
 * The [LocalModel.cachedModel] property bridges local and server workflows.
 */
object Octomil {

    /**
     * Load a model by name with automatic resolution.
     *
     * Searches for the model in this order:
     * 1. Paired models (deployed via `octomil deploy --phone`)
     * 2. Assets directory as `{name}.tflite`
     * 3. Model download cache
     *
     * ```kotlin
     * val model = Octomil.load(context, "mobilenet-v2")
     * val output = model.predict(floatArrayOf(1f, 2f, 3f)).getOrThrow()
     * model.close()
     * ```
     *
     * @param context Android context.
     * @param name Model name (e.g., "mobilenet-v2"). Not a file path.
     * @param options Hardware acceleration and threading options.
     * @param engine Inference engine hint. Defaults to [Engine.AUTO].
     * @return A [DeployedModel] ready for inference.
     * @throws ModelNotFoundException if the model cannot be found in any source.
     */
    suspend fun load(
        context: Context,
        name: String,
        options: LocalModelOptions = LocalModelOptions(),
        engine: Engine = Engine.AUTO,
    ): DeployedModel {
        val modelFile = ModelResolver.default().resolve(context, name)
            ?: throw ModelNotFoundException(name)
        return deploy(context, modelFile, engine, name, options, benchmark = true)
    }

    /**
     * Load a model by name with a custom [ModelResolver].
     *
     * ```kotlin
     * val resolver = ModelResolver.chain(
     *     ModelResolver.paired(),
     *     ModelResolver.assets(),
     *     MyCustomCdnResolver(),
     * )
     * val model = Octomil.load(context, "phi-4-mini", resolver = resolver)
     * ```
     *
     * @param context Android context.
     * @param name Model name.
     * @param resolver Custom model resolution strategy.
     * @param options Hardware acceleration and threading options.
     * @param engine Inference engine hint.
     * @return A [DeployedModel] ready for inference.
     * @throws ModelNotFoundException if the resolver returns null.
     */
    suspend fun load(
        context: Context,
        name: String,
        resolver: ModelResolver,
        options: LocalModelOptions = LocalModelOptions(),
        engine: Engine = Engine.AUTO,
    ): DeployedModel {
        val modelFile = resolver.resolve(context, name)
            ?: throw ModelNotFoundException(name)
        return deploy(context, modelFile, engine, name, options, benchmark = true)
    }

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
        benchmark: Boolean = true,
    ): DeployedModel {
        val resolvedName = name ?: modelPath.substringBeforeLast(".").substringAfterLast("/")
        val localModel = loadModel(context, modelPath, options)
        val deployed = DeployedModel(
            name = resolvedName,
            engine = resolveEngine(modelPath, engine),
            localModel = localModel,
        )
        if (benchmark) {
            deployed.warmupResult = localModel.warmup()
        }
        return deployed
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
        benchmark: Boolean = true,
    ): DeployedModel {
        val resolvedName = name ?: modelFile.nameWithoutExtension
        val localModel = loadModel(context, modelFile, options)
        val deployed = DeployedModel(
            name = resolvedName,
            engine = resolveEngine(modelFile.name, engine),
            localModel = localModel,
        )
        if (benchmark) {
            deployed.warmupResult = localModel.warmup()
        }
        return deployed
    }

    /**
     * Create an OpenAI-compatible chat interface for on-device LLM inference.
     *
     * Drop-in replacement for OpenAI/Groq client calls:
     * ```kotlin
     * val chat = Octomil.chat(context, "phi-4-mini")
     *
     * // Non-streaming (like openai.chat.completions.create):
     * val response = chat.create("What is machine learning?")
     * println(response.choices[0].message.content)
     *
     * // Streaming:
     * chat.stream("Explain neural networks").collect { chunk ->
     *     print(chunk.choices[0].delta.content.orEmpty())
     * }
     * ```
     *
     * @param context Android context.
     * @param name Model name (e.g., "phi-4-mini").
     * @return An [OctomilChat] ready for chat completions.
     * @throws ModelNotFoundException if the model cannot be found.
     */
    fun chat(
        context: Context,
        name: String,
    ): OctomilChat {
        val modelFile = ModelResolver.default().resolveSync(context, name)
        val runtime = modelFile?.let { file ->
            LLMRuntimeRegistry.factory?.invoke(file)
        }
        val engine = EngineRegistry.resolve(
            modality = Modality.TEXT,
            context = context,
            modelFile = modelFile,
        )
        return OctomilChat(modelName = name, engine = engine, runtime = runtime)
    }

    /**
     * Create a chat interface with a custom [ModelResolver].
     */
    fun chat(
        context: Context,
        name: String,
        resolver: ModelResolver,
    ): OctomilChat {
        val modelFile = resolver.resolveSync(context, name)
        val runtime = modelFile?.let { file ->
            LLMRuntimeRegistry.factory?.invoke(file)
        }
        val engine = EngineRegistry.resolve(
            modality = Modality.TEXT,
            context = context,
            modelFile = modelFile,
        )
        return OctomilChat(modelName = name, engine = engine, runtime = runtime)
    }

    private fun resolveEngine(filename: String, engine: Engine): Engine {
        if (engine != Engine.AUTO) return engine
        return EngineRegistry.engineFromFilename(filename) ?: Engine.TFLITE
    }

    /**
     * Copy an asset file to the app's cache directory if it doesn't already exist.
     */
    internal fun copyAssetToCache(context: Context, assetFileName: String): File {
        val cacheDir = File(context.cacheDir, "octomil_local_models").apply { mkdirs() }
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
