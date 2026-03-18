package ai.octomil

import ai.octomil.speech.OctomilAudio
import ai.octomil.chat.LLMRuntimeRegistry
import ai.octomil.chat.OctomilChat
import ai.octomil.generated.DeliveryMode
import ai.octomil.manifest.AppManifest
import ai.octomil.manifest.ModelCatalogService
import ai.octomil.models.CachedModel
import ai.octomil.responses.OctomilResponses
import ai.octomil.runtime.core.Engine
import ai.octomil.runtime.core.ModelRuntimeRegistry
import ai.octomil.runtime.engines.llama.LlamaCppRuntime
import ai.octomil.runtime.engines.tflite.EngineRegistry
import ai.octomil.runtime.engines.tflite.LLMRuntimeAdapter
import ai.octomil.api.OctomilApiFactory
import ai.octomil.api.dto.DeviceCapabilities
import ai.octomil.api.dto.DeviceRegistrationRequest
import ai.octomil.api.dto.HeartbeatRequest
import ai.octomil.control.ControlPlaneClient
import ai.octomil.sdk.DeviceContext
import ai.octomil.sdk.MonitoringConfig
import ai.octomil.speech.SherpaStreamingRuntime
import ai.octomil.speech.SpeechRuntimeRegistry
import ai.octomil.storage.SecureStorage
import ai.octomil.text.OctomilText
import ai.octomil.training.TFLiteTrainer
import ai.octomil.utils.BatteryUtils
import ai.octomil.utils.DeviceUtils
import ai.octomil.utils.NetworkUtils
import ai.octomil.workflows.WorkflowRunner
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    private var heartbeatJob: Job? = null
    private var _control: ControlPlaneClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Device context for registration state and auth headers. Null if not configured. */
    val deviceContext: DeviceContext? get() = _deviceContext

    /** Control plane client for heartbeat, desired-state, and observed-state. Null before registration. */
    val control: ControlPlaneClient? get() = _control

    /** Model catalog bootstrapped from an [AppManifest], or null if not configured. */
    val catalog: ModelCatalogService? get() = _catalog

    /** Text prediction API. Requires [configure] with a KEYBOARD_PREDICTION model. */
    val text: OctomilText = OctomilText(
        catalogProvider = { _catalog },
        contextProvider = { appContext },
    )

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
     *             inputModalities = listOf(Modality.TEXT),
     *             outputModalities = listOf(Modality.TEXT),
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

        // Wire LLM runtime — llama.cpp for GGUF models.
        // SDK unconditionally owns runtime wiring.
        LLMRuntimeRegistry.factory = { modelFile ->
            val mmproj = modelFile.parentFile?.listFiles()?.firstOrNull { f ->
                f.name.contains("mmproj", ignoreCase = true) && f.extension == "gguf"
            }
            LlamaCppRuntime(modelFile, mmproj, context.applicationContext)
        }

        ModelRuntimeRegistry.defaultFactory = factory@{ modelId ->
            val ctx = appContext ?: return@factory null
            val file = ModelResolver.default().resolveSync(ctx, modelId) ?: return@factory null
            val llm = LLMRuntimeRegistry.factory?.invoke(file) ?: return@factory null
            LLMRuntimeAdapter(llm)
        }

        // Wire speech runtime — sherpa-onnx streaming recognizer with punctuation
        val punctDir = extractPunctAssets(context.applicationContext)
        SpeechRuntimeRegistry.factory = { modelDir -> SherpaStreamingRuntime(modelDir, punctDir) }

        // Prediction runtime: no longer wired here. OctomilTextPredictions uses
        // LLMRuntime.supportsPrediction() + handle-based API via LLMRuntimeRegistry.
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
     * Audio API for on-device speech transcription.
     *
     * ```kotlin
     * val session = Octomil.audio.streamingSession("sherpa-zipformer-en-20m")
     * session.feed(samples)           // 16kHz mono float [-1,1]
     * session.transcript.collect {}   // live StateFlow<String>
     * val final = session.finalize()  // drain + return final text
     * session.release()
     * ```
     */
    val audio: OctomilAudio by lazy {
        OctomilAudio(contextProvider = { appContext })
    }

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
     * When [pairingCode] and [api] are both provided, benchmark results from warmup
     * are automatically submitted to the server. This replaces the old pattern of
     * benchmarking during the pairing flow.
     *
     * @param context Android context.
     * @param modelPath Asset filename (e.g., "mobilenet.tflite") or absolute file path.
     * @param engine Inference engine to use. Defaults to [Engine.AUTO].
     * @param name Human-readable model name. Defaults to filename without extension.
     * @param options Hardware acceleration options.
     * @param benchmark Whether to run warmup benchmarks. Defaults to true.
     * @param pairingCode Optional pairing code for benchmark submission.
     * @param api Optional API client for benchmark submission.
     * @return A [DeployedModel] ready for inference.
     */
    suspend fun deploy(
        context: Context,
        modelPath: String,
        engine: Engine = Engine.AUTO,
        name: String? = null,
        options: LocalModelOptions = LocalModelOptions(),
        benchmark: Boolean = true,
        pairingCode: String? = null,
        api: ai.octomil.api.OctomilApi? = null,
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
            submitBenchmarkIfNeeded(context, resolvedName, deployed.warmupResult, pairingCode, api)
        }
        return deployed
    }

    /**
     * Deploy a model for inference with automatic engine detection.
     *
     * When [pairingCode] and [api] are both provided, benchmark results from warmup
     * are automatically submitted to the server. This replaces the old pattern of
     * benchmarking during the pairing flow.
     *
     * @param context Android context.
     * @param modelFile Path to the model file on disk.
     * @param engine Inference engine to use. Defaults to [Engine.AUTO].
     * @param name Human-readable model name. Defaults to filename without extension.
     * @param options Hardware acceleration options.
     * @param benchmark Whether to run warmup benchmarks. Defaults to true.
     * @param pairingCode Optional pairing code for benchmark submission.
     * @param api Optional API client for benchmark submission.
     * @return A [DeployedModel] ready for inference.
     */
    suspend fun deploy(
        context: Context,
        modelFile: File,
        engine: Engine = Engine.AUTO,
        name: String? = null,
        options: LocalModelOptions = LocalModelOptions(),
        benchmark: Boolean = true,
        pairingCode: String? = null,
        api: ai.octomil.api.OctomilApi? = null,
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
            submitBenchmarkIfNeeded(context, resolvedName, deployed.warmupResult, pairingCode, api)
        }
        return deployed
    }

    /**
     * Submit benchmark results to the server if a pairing code and API client are available.
     *
     * Converts the [WarmupResult] from the engine layer into a [BenchmarkReport] and
     * submits it via the pairing benchmark endpoint. Submission is non-fatal: errors
     * are logged but do not propagate.
     */
    private suspend fun submitBenchmarkIfNeeded(
        context: Context,
        modelName: String,
        warmupResult: ai.octomil.training.WarmupResult?,
        pairingCode: String?,
        api: ai.octomil.api.OctomilApi?,
    ) {
        if (pairingCode == null || api == null || warmupResult == null) return

        try {
            val deviceInfo = ai.octomil.pairing.DeviceCapabilities.collect(context)
            val report = ai.octomil.pairing.BenchmarkReport(
                modelName = modelName,
                deviceName = deviceInfo.deviceName,
                chipFamily = deviceInfo.chipFamily ?: android.os.Build.HARDWARE,
                ramGb = deviceInfo.ramGb ?: 0.0,
                osVersion = deviceInfo.osVersion ?: android.os.Build.VERSION.RELEASE,
                ttftMs = warmupResult.coldInferenceMs,
                tpotMs = warmupResult.warmInferenceMs,
                tokensPerSecond = if (warmupResult.warmInferenceMs > 0) {
                    1000.0 / warmupResult.warmInferenceMs
                } else {
                    0.0
                },
                p50LatencyMs = warmupResult.warmInferenceMs,
                p95LatencyMs = warmupResult.warmInferenceMs,
                p99LatencyMs = warmupResult.warmInferenceMs,
                memoryPeakBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                inferenceCount = 2, // cold + warm from warmup
                modelLoadTimeMs = 0.0, // not tracked separately in warmup
                coldInferenceMs = warmupResult.coldInferenceMs,
                warmInferenceMs = warmupResult.warmInferenceMs,
                activeDelegate = warmupResult.activeDelegate,
                disabledDelegates = warmupResult.disabledDelegates.ifEmpty { null },
            )

            Timber.d("Submitting benchmark for pairing code: %s", pairingCode)
            val response = api.submitBenchmark(pairingCode, report)
            if (!response.isSuccessful) {
                Timber.w("Failed to submit benchmark: HTTP %d", response.code())
            } else {
                Timber.i("Benchmark submitted successfully for pairing code: %s", pairingCode)
            }
        } catch (e: Exception) {
            Timber.w(e, "Non-fatal: failed to submit benchmark for pairing code: %s", pairingCode)
        }
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
        val context = appContext ?: return
        val maxBackoffMs = 5 * 60 * 1000L // 5 minutes
        var backoffMs = 1000L

        // Extract bearer token from AuthConfig
        val bearerToken = when (auth) {
            is ai.octomil.sdk.AuthConfig.PublishableKey -> auth.key
            is ai.octomil.sdk.AuthConfig.BootstrapToken -> auth.token
            is ai.octomil.sdk.AuthConfig.Anonymous -> return // no registration for anonymous
        }

        val api = OctomilApiFactory.createForRegistration(serverUrl, bearerToken)

        for (attempt in 1..10) {
            try {
                Timber.d("Background registration attempt $attempt for installation ${ctx.installationId}")

                val request = DeviceRegistrationRequest(
                    deviceIdentifier = ctx.installationId,
                    orgId = ctx.orgId ?: "",
                    osVersion = DeviceUtils.getOsVersion(),
                    sdkVersion = BuildConfig.OCTOMIL_VERSION,
                    manufacturer = DeviceUtils.getManufacturer(),
                    model = DeviceUtils.getModel(),
                    locale = DeviceUtils.getLocale(),
                    region = DeviceUtils.getRegion(),
                    timezone = java.util.TimeZone.getDefault().id,
                    capabilities = DeviceUtils.getDeviceCapabilities(context),
                )

                val response = api.registerDevice(request)
                if (!response.isSuccessful) {
                    throw RuntimeException("Registration HTTP ${response.code()}")
                }

                val body = response.body()
                    ?: throw RuntimeException("Registration response body is null")

                val serverDeviceId = body.id
                val accessToken = body.accessToken ?: body.apiToken
                    ?: throw RuntimeException("No access token in registration response")

                // Parse expires_at or default to 24h from now
                val expiresAt = body.expiresAt?.let { parseIso8601(it) }
                    ?: (System.currentTimeMillis() + 24 * 60 * 60 * 1000L)

                // Update device context
                ctx.updateRegistered(serverDeviceId, accessToken, expiresAt)

                // Persist token to secure storage
                val storage = SecureStorage.getInstance(context)
                storage.setServerDeviceId(serverDeviceId)
                storage.setApiToken(accessToken)
                storage.putString(DeviceContext.CACHED_TOKEN_KEY, accessToken)
                storage.putLong(DeviceContext.CACHED_TOKEN_EXPIRES_KEY, expiresAt)
                storage.setDeviceRegistered(true)

                Timber.i("Device registered: id=$serverDeviceId")

                // Start heartbeat loop
                startHeartbeatLoop(serverUrl, serverDeviceId, accessToken)

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

    /**
     * Start a periodic heartbeat loop after successful registration.
     */
    private fun startHeartbeatLoop(
        serverUrl: String,
        deviceId: String,
        accessToken: String,
    ) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            val api = OctomilApiFactory.createForRegistration(serverUrl, accessToken)
            val controlClient = ControlPlaneClient(
                api = api,
                orgId = _deviceContext?.orgId ?: "",
                deviceId = deviceId,
            )
            _control = controlClient

            val intervalMs = _monitoring.heartbeatIntervalSeconds * 1000L
            while (isActive) {
                delay(intervalMs)
                sendHeartbeat(controlClient, deviceId)
            }
        }
    }

    /**
     * Send a single heartbeat. Fire-and-forget: swallows all exceptions.
     */
    private suspend fun sendHeartbeat(controlClient: ControlPlaneClient, deviceId: String) {
        try {
            val context = appContext ?: return
            val request = HeartbeatRequest(
                sdkVersion = BuildConfig.OCTOMIL_VERSION,
                osVersion = DeviceUtils.getOsVersion(),
                batteryLevel = BatteryUtils.getBatteryLevel(context),
                isCharging = BatteryUtils.isCharging(context),
                availableStorageMb = DeviceUtils.getAvailableStorageMb(),
                availableMemoryMb = DeviceUtils.getAvailableMemoryMb(context),
                networkType = if (NetworkUtils.isWifiConnected(context)) "wifi" else "cellular",
            )
            controlClient.heartbeat(deviceId, request)
        } catch (e: Exception) {
            Timber.d(e, "Heartbeat failed (non-blocking)")
        }
    }

    private fun parseIso8601(dateStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            // Strip fractional seconds and trailing Z for parsing
            val normalized = dateStr.replace(Regex("\\.[0-9]+"), "").replace("Z", "")
            sdf.parse(normalized)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
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

    /**
     * Extract punctuation model assets (punct/model.int8.onnx, punct/bpe.vocab)
     * to cache. Returns the cache directory, or null if assets are missing.
     */
    private fun extractPunctAssets(context: Context): File? {
        val punctCacheDir = File(context.cacheDir, "octomil_punct").apply { mkdirs() }
        val files = listOf("model.int8.onnx", "bpe.vocab")
        return try {
            for (name in files) {
                val outFile = File(punctCacheDir, name)
                if (!outFile.exists()) {
                    context.assets.open("punct/$name").use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            punctCacheDir
        } catch (_: Exception) {
            // Punctuation assets not bundled — gracefully degrade
            null
        }
    }
}
