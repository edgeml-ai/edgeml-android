package ai.octomil.audio

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RoutingPolicy
import ai.octomil.prepare.PrepareCandidate
import ai.octomil.prepare.PrepareArtifactPlan
import ai.octomil.prepare.PrepareManager
import ai.octomil.prepare.PrepareMode
import ai.octomil.prepare.PrepareOutcome
import ai.octomil.prepare.StaticRecipeRegistry
import ai.octomil.runtime.routing.ModelRefParser
import ai.octomil.runtime.routing.ParsedModelRef
import ai.octomil.tts.TtsResult
import ai.octomil.tts.TtsRuntime
import ai.octomil.tts.TtsRuntimeRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Public TTS surface — `client.audio.speech`.
 *
 * Mirrors the iOS `Octomil.audio.speech` and Python
 * `client.audio.speech` namespaces. The lifecycle is:
 *
 *  1. `prepare(model)` — download + verify + materialize the
 *     on-disk artifact via [PrepareManager] / `Materializer`.
 *     Idempotent on cache hit.
 *  2. `warmup(model)` — prepare the artifact AND load the
 *     [TtsRuntime] backend handle, retaining it for reuse by
 *     subsequent [create] calls.
 *  3. `create(model, input, voice, speed, app, policy)` —
 *     synthesize speech using the warmed handle (when present);
 *     otherwise lazy-prepare + load just-in-time.
 *
 * This object enforces the `private` / `local_only` routing
 * invariant: those policies never fall back to a cloud / hosted
 * pathway. Out of scope today, hosted TTS is exposed separately
 * via [ai.octomil.hosted.OctomilHosted].
 *
 * The TTS engine itself is an optional dependency — when the
 * `octomil-runtime-sherpa-android` artifact is not on the
 * classpath, [TtsRuntimeRegistry.factory] is `null` and `create`
 * surfaces a deterministic [OctomilException] instead of crashing.
 */
class AudioSpeech internal constructor(
    private val prepareManager: PrepareManager = PrepareManager(),
    private val factoryProvider: () -> ai.octomil.tts.TtsRuntimeFactory? = { TtsRuntimeRegistry.factory },
) {
    private val warmedMutex = Mutex()

    /** Active warmed runtime, keyed by resolved model id. */
    private var warmedModel: String? = null
    private var warmedRuntime: TtsRuntime? = null

    /**
     * Materialize the model's on-disk artifact without loading the
     * runtime. Returns the [PrepareOutcome] so callers can inspect
     * `cached`, `artifactDir`, etc.
     */
    suspend fun prepare(
        model: String,
        app: String? = null,
        policy: RoutingPolicy? = null,
    ): PrepareOutcome = withContext(Dispatchers.IO) {
        val resolution = resolveCandidate(model, app, policy)
        prepareManager.prepare(resolution.candidate, mode = PrepareMode.EXPLICIT)
    }

    /**
     * Prepare AND load the runtime handle so the next [create]
     * call reuses the warmed backend.
     *
     * Subsequent warmups for the *same* resolved model are no-ops
     * (the existing handle is reused). Switching to a different
     * model releases the previous handle.
     */
    suspend fun warmup(
        model: String,
        app: String? = null,
        policy: RoutingPolicy? = null,
    ): WarmupOutcome = withContext(Dispatchers.IO) {
        val resolution = resolveCandidate(model, app, policy)
        val outcome = prepareManager.prepare(resolution.candidate, mode = PrepareMode.EXPLICIT)
        val factory = factoryProvider() ?: throw OctomilException(
            OctomilErrorCode.RUNTIME_UNAVAILABLE,
            "No TTS runtime factory registered. Add the optional " +
                "octomil-runtime-sherpa-android dependency.",
        )
        warmedMutex.withLock {
            val canonicalId = resolution.canonicalModelId
            val existing = warmedRuntime
            if (existing != null && warmedModel == canonicalId) {
                return@withLock WarmupOutcome(
                    artifactDir = outcome.artifactDir,
                    cached = outcome.cached,
                    runtimeReused = true,
                    model = canonicalId,
                )
            }
            existing?.runCatching { release() }
            val runtime = factory.create(outcome.artifactDir, canonicalId)
            warmedRuntime = runtime
            warmedModel = canonicalId
            WarmupOutcome(
                artifactDir = outcome.artifactDir,
                cached = outcome.cached,
                runtimeReused = false,
                model = canonicalId,
            )
        }
    }

    /**
     * Synthesize speech. Reuses the warmed runtime when the
     * resolved model matches; otherwise lazy-prepares and loads
     * a fresh handle (released after the call).
     */
    suspend fun create(
        model: String,
        input: String,
        voice: String? = null,
        speed: Float = 1.0f,
        app: String? = null,
        policy: RoutingPolicy? = null,
    ): TtsResult = withContext(Dispatchers.IO) {
        val resolution = resolveCandidate(model, app, policy)
        val canonicalId = resolution.canonicalModelId

        val warmed = warmedMutex.withLock {
            if (warmedModel == canonicalId) warmedRuntime else null
        }
        if (warmed != null) {
            return@withContext warmed.synthesize(input, voice = voice, speed = speed)
        }

        val outcome = prepareManager.prepare(resolution.candidate, mode = PrepareMode.LAZY)
        val factory = factoryProvider() ?: throw OctomilException(
            OctomilErrorCode.RUNTIME_UNAVAILABLE,
            "No TTS runtime factory registered. Add the optional " +
                "octomil-runtime-sherpa-android dependency.",
        )
        val runtime = factory.create(outcome.artifactDir, canonicalId)
        try {
            runtime.synthesize(input, voice = voice, speed = speed)
        } finally {
            runtime.release()
        }
    }

    /** Release any warmed runtime handle. Safe to call repeatedly. */
    suspend fun release() {
        warmedMutex.withLock {
            warmedRuntime?.runCatching { release() }
            warmedRuntime = null
            warmedModel = null
        }
    }

    // -----------------------------------------------------------------
    // Internal: resolve the model ref + app + policy into a
    // PrepareCandidate that PrepareManager can materialize.
    // -----------------------------------------------------------------

    internal fun resolveCandidate(
        model: String,
        app: String?,
        policy: RoutingPolicy?,
    ): SpeechResolution {
        require(model.isNotBlank()) { "model must be a non-empty string." }
        val parsed = ModelRefParser.parse(model)
        val (canonicalModelId, parsedAppSlug) = when (parsed) {
            is ParsedModelRef.AppRef -> parsed.capability to parsed.slug
            is ParsedModelRef.ModelRef -> parsed.model to null
            is ParsedModelRef.UnknownRef -> parsed.raw to null
            is ParsedModelRef.AliasRef -> parsed.alias to null
            is ParsedModelRef.CapabilityRef -> parsed.capability to null
            is ParsedModelRef.DeploymentRef -> parsed.deploymentId to null
            is ParsedModelRef.ExperimentRef -> parsed.experimentId to null
            is ParsedModelRef.DefaultRef -> "" to null
        }
        if (canonicalModelId.isBlank()) {
            throw OctomilException(
                OctomilErrorCode.INVALID_INPUT,
                "Could not resolve model id from \"$model\".",
            )
        }
        // Identity gate: when both an `@app/` ref and an explicit
        // `app=` arg are supplied, they MUST agree. A mismatch would
        // silently let the wrong identity pick up cached artifacts,
        // so refuse loudly. Mirrors iOS / Python.
        if (parsedAppSlug != null && app != null && parsedAppSlug != app) {
            throw OctomilException(
                OctomilErrorCode.INVALID_INPUT,
                "@app/ ref \"$parsedAppSlug\" does not match app= argument \"$app\". " +
                    "App identity must be unambiguous.",
            )
        }
        val effectiveApp = parsedAppSlug ?: app

        // Route to the static recipe table so the same model id
        // resolves to identical artifacts as Python / Node / iOS.
        val recipeId = canonicalModelId
        val recipe = StaticRecipeRegistry.recipe(recipeId)
            ?: throw OctomilException(
                OctomilErrorCode.MODEL_NOT_FOUND,
                "No on-device TTS recipe registered for \"$canonicalModelId\".",
            )
        val artifactId = if (effectiveApp != null) {
            // App-scoped artifact id — keeps an app's prepared
            // bytes separate from the public cache, even when the
            // underlying recipe is the same shape.
            "@app/$effectiveApp/$canonicalModelId"
        } else {
            recipe.modelId
        }
        // Policy invariant: private + local_only never permit a
        // hosted-cloud fallback. Surface a hard error if the caller
        // tried to set those policies on a path that has no on-
        // device backend yet — the validator below catches that.
        if (policy == RoutingPolicy.CLOUD_ONLY) {
            throw OctomilException(
                OctomilErrorCode.UNSUPPORTED_MODALITY,
                "policy=cloud_only is not supported by client.audio.speech; " +
                    "use OctomilHosted for hosted TTS.",
            )
        }
        val candidate = PrepareCandidate(
            locality = "local",
            engine = "sherpa-onnx",
            artifact = PrepareArtifactPlan(
                modelId = canonicalModelId,
                artifactId = artifactId,
                source = "static_recipe",
                recipeId = recipeId,
            ),
        )
        return SpeechResolution(
            canonicalModelId = canonicalModelId,
            appSlug = effectiveApp,
            policy = policy,
            candidate = candidate,
        )
    }
}

/** Outcome of [AudioSpeech.warmup]. */
data class WarmupOutcome(
    val artifactDir: File,
    val cached: Boolean,
    val runtimeReused: Boolean,
    val model: String,
)

/** Internal: the resolved view of `model` + `app` + `policy`. */
internal data class SpeechResolution(
    val canonicalModelId: String,
    val appSlug: String?,
    val policy: RoutingPolicy?,
    val candidate: PrepareCandidate,
)
