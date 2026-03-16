package ai.octomil

import ai.octomil.audio.OctomilAudio
import ai.octomil.chat.LLMRuntimeRegistry
import ai.octomil.chat.OctomilChat
import ai.octomil.generated.DeliveryMode
import ai.octomil.manifest.AppManifest
import ai.octomil.manifest.ModelCatalogService
import ai.octomil.models.CachedModel
import ai.octomil.responses.OctomilResponses
import ai.octomil.runtime.core.Engine
import ai.octomil.runtime.core.ModelRuntimeRegistry
import ai.octomil.runtime.engines.tflite.EngineRegistry
import ai.octomil.runtime.engines.tflite.LLMRuntimeAdapter
import ai.octomil.runtime.engines.tflite.Modality
import ai.octomil.sdk.DeviceContext
import ai.octomil.sdk.MonitoringConfig
import ai.octomil.storage.SecureStorage
import ai.octomil.text.OctomilText
import ai.octomil.training.TFLiteTrainer
import ai.octomil.workflows.WorkflowRunner
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import kotlin.math.min
import kotlin.random.Random

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

    private var appContext: Context? = null
    private var _catalog: ModelCatalogService? = null
    private var _deviceContext: DeviceContext? = null
    private var _manifest: AppManifest? = null
    private var _monitoring: MonitoringConfig = MonitoringConfig()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Device context for registration state and auth headers. Null if not configured. */
    val deviceContext: DeviceContext? get() = _deviceContext

    /** Model catalog bootstrapped from an [AppManifest], or null if not configured. */
    val catalog: ModelCatalogService? get() = _catalog

    /** Audio API (transcription). Requires [configure] with a TRANSCRIPTION model. */
    val audio: OctomilAudio = OctomilAudio { _catalog }

    /** Text prediction API. Requires [configure] with a KEYBOARD_PREDICTION model. */
    val text: OctomilText = OctomilText { _catalog }

    /**
     * Configure the SDK with a declarative [AppManifest].
     *
     * Calls [init] internally, then bootstraps all manifest entries so that
     * capability-based runtime resolution is available immediately.
     *
     * ```kotlin
     * val manifest = AppManifest(
     *     models = listOf(
     *         AppModelEntry(
     *             id = "phi-4-mini",
     *             capability = ModelCapability.CHAT,
     *             delivery = DeliveryMode.MANAGED,
     *             routingPolicy = RoutingPolicy.LOCAL_FIRST,
     *         ),
     *     ),
     * )
     * Octomil.configure(context, manifest)
     * ```
     *
     * @param context Android context (application context is retained).
     * @param manifest Declarative model manifest.
     * @param serverUrl Base URL for cloud/managed models.
     * @param apiKey API key for cloud/managed models.
     */
    suspend fun configure(
        context: Context,
        manifest: AppManifest,
        serverUrl: String = "https://api.octomil.com",
        apiKey: String? = null,
        auth: ai.octomil.sdk.AuthConfig? = null,
        monitoring: MonitoringConfig = MonitoringConfig(),
    ) {
        init(context)
        _manifest = manifest
        _monitoring = monitoring

        // Seed the persistent install ID early so TelemetryQueue picks it up
        ai.octomil.utils.InstallId.getOrCreate(context.applicationContext)

        // Populate DeviceContext immediately with a random UUID installation ID
        val storage = SecureStorage.getInstance(context.applicationContext)
        val installationId = DeviceContext.getOrCreateInstallationId(storage)
        val orgId = when (auth) {
            is ai.octomil.sdk.AuthConfig.PublishableKey -> null // resolved server-side
            is ai.octomil.sdk.AuthConfig.BootstrapToken -> null
            is ai.octomil.sdk.AuthConfig.Anonymous -> null
            null -> null
        }
        val appId = when (auth) {
            is ai.octomil.sdk.AuthConfig.Anonymous -> auth.appId
            else -> null
        }
        val deviceCtx = DeviceContext(
            installationId = installationId,
            orgId = orgId,
            appId = appId,
        )
        // Restore cached token from secure storage; registrationState stays PENDING
        DeviceContext.restoreCachedToken(deviceCtx, storage)
        _deviceContext = deviceCtx

        val catalog = ModelCatalogService(
            manifest = manifest,
            context = context.applicationContext,
            serverUrl = serverUrl,
            apiKey = apiKey,
        )
        catalog.bootstrap()
        _catalog = catalog

        // Launch background registration if the gate is open
        if (shouldAutoRegister(auth, manifest, monitoring)) {
            scope.launch {
                backgroundRegister(serverUrl, auth!!)
            }
        }
    }

    /**
     * Initialize the Octomil SDK with an Android context.
     *
     * Wires the LLM runtime registry into the model runtime registry so that
     * `Octomil.responses` can resolve on-device LLM runtimes automatically.
     *
     * Call this once in `Application.onCreate()`:
     * ```kotlin
     * Octomil.init(this)
     * ```
     *
     * @param context Android context (application context is retained).
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        ModelRuntimeRegistry.defaultFactory = factory@{ modelId ->
            val ctx = appContext ?: return@factory null
            val file = ModelResolver.default().resolveSync(ctx, modelId) ?: return@factory null
            val llm = LLMRuntimeRegistry.factory?.invoke(file) ?: return@factory null
            LLMRuntimeAdapter(llm)
        }
    }

    /**
     * Response API for structured on-device inference.
     *
     * ```kotlin
     * val response = Octomil.responses.create(
     *     ResponseRequest(
     *         model = "phi-4-mini",
     *         input = listOf(InputItem.text("What is machine learning?")),
     *     )
     * )
     * ```
     */
    val responses: OctomilResponses by lazy {
        OctomilResponses(catalogProvider = { _catalog })
    }

    /**
     * Workflow runner for multi-step orchestrated inference pipelines.
     *
     * ```kotlin
     * val result = Octomil.workflows.run(
     *     Workflow(name = "summarize-then-translate", steps = listOf(
     *         WorkflowStep.Inference(model = "phi-4-mini", instructions = "Summarize this text"),
     *         WorkflowStep.Inference(model = "phi-4-mini", instructions = "Translate to Spanish"),
     *     )),
     *     input = "Long article text here...",
     * )
     * ```
     */
    val workflows: WorkflowRunner by lazy { WorkflowRunner(responses) }

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
        val responses = buildResponses(context, name, ModelResolver.default())
        return OctomilChat(modelName = name, responses = responses)
    }

    /**
     * Create a chat interface with a custom [ModelResolver].
     */
    fun chat(
        context: Context,
        name: String,
        resolver: ModelResolver,
    ): OctomilChat {
        val responses = buildResponses(context, name, resolver)
        return OctomilChat(modelName = name, responses = responses)
    }

    /**
     * Build an [OctomilResponses] for the given model, registering a runtime
     * from [LLMRuntimeRegistry] or the engine registry if needed.
     */
    private fun buildResponses(
        context: Context,
        name: String,
        resolver: ModelResolver,
    ): OctomilResponses {
        val modelFile = resolver.resolveSync(context, name)
        val llmRuntime = modelFile?.let { file ->
            LLMRuntimeRegistry.factory?.invoke(file)
        }
        if (llmRuntime != null) {
            val adapter = LLMRuntimeAdapter(llmRuntime)
            ModelRuntimeRegistry.register(name) { adapter }
        }
        return OctomilResponses()
    }

    /**
     * Gate: auto-register only when auth is provided AND the manifest has
     * managed/cloud models or monitoring is enabled.
     */
    private fun shouldAutoRegister(
        auth: ai.octomil.sdk.AuthConfig?,
        manifest: AppManifest,
        monitoring: MonitoringConfig,
    ): Boolean {
        if (auth == null) return false
        val hasManagedOrCloud = manifest.models.any {
            it.delivery == DeliveryMode.MANAGED || it.delivery == DeliveryMode.CLOUD
        }
        return hasManagedOrCloud || monitoring.enabled
    }

    /**
     * Background device registration with exponential backoff (1s, 2s, 4s... max 5min) + jitter.
     * Never throws — failures update [DeviceContext.registrationState] to FAILED.
     */
    private suspend fun backgroundRegister(
        serverUrl: String,
        auth: ai.octomil.sdk.AuthConfig,
    ) {
        val ctx = _deviceContext ?: return
        val maxBackoffMs = 5 * 60 * 1000L // 5 minutes
        var backoffMs = 1000L

        for (attempt in 1..10) {
            try {
                // TODO: Call actual registration API endpoint when available.
                // For now, this is a placeholder that will be wired to the server API.
                Timber.d("Background registration attempt $attempt for installation ${ctx.installationId}")

                // If we reach here without exception, registration succeeded
                // The actual API call will populate serverDeviceId and token via ctx.updateRegistered()
                break
            } catch (e: Exception) {
                Timber.w(e, "Background registration attempt $attempt failed")
                if (attempt == 10) {
                    ctx.markFailed()
                    Timber.w("Background registration exhausted retries — marking FAILED")
                    return
                }
                val jitter = Random.nextLong(0, backoffMs / 4)
                delay(backoffMs + jitter)
                backoffMs = min(backoffMs * 2, maxBackoffMs)
            }
        }
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
