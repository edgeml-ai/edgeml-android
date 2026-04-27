package ai.octomil.prepare

import java.io.File
import java.net.URL
import java.time.Instant

/**
 * PrepareManager — bridge from a planner candidate to on-disk
 * artifact readiness.
 *
 * Port of Python `PrepareManager`, Node `prepare-manager.ts`, and
 * Swift `PrepareManager.swift`. Single owner of artifact
 * materialization for `sdk_runtime` candidates. Wraps
 * [DurableDownloader] (the actual byte pump) and threads policy +
 * cache + safe filesystem keys through one consistent surface.
 */
class PrepareManager(
    cacheDir: File? = null,
    downloader: DurableDownloader? = null,
) {
    val cacheDir: File = (cacheDir ?: defaultCacheDir()).also { it.mkdirs() }
    private val downloader: DurableDownloader = downloader ?: DurableDownloader(this.cacheDir)

    /**
     * Pure inspection — does NOT touch disk or network. Returns
     * `true` only when [prepare] is structurally guaranteed to
     * succeed. Synthetic / malformed candidates return `false`.
     */
    fun canPrepare(candidate: PrepareCandidate): Boolean = try {
        validateForPrepare(candidate)
        true
    } catch (_: PrepareException) {
        false
    }

    /**
     * Compute the deterministic `<cacheDir>/<safeKey>` directory for
     * an artifact id. Same key shape Python / Node / Swift use.
     */
    fun artifactDirFor(artifactId: String): File {
        if (artifactId.isEmpty()) {
            throw PrepareException("Refusing to prepare artifact with empty artifact_id.")
        }
        val key = try {
            safeFilesystemKey(artifactId)
        } catch (e: FilesystemKeyException) {
            throw PrepareException("artifact_id is not a valid filesystem key: ${e.message}")
        }
        return File(cacheDir, key)
    }

    /**
     * Materialize a candidate's bytes on disk and return a
     * [PrepareOutcome]. Throws [PrepareException] on contract
     * violations or download exhaustion.
     */
    suspend fun prepare(
        candidate: PrepareCandidate,
        mode: PrepareMode = PrepareMode.LAZY,
    ): PrepareOutcome {
        validateForPrepare(candidate)
        checkExplicitOnlyVsMode(candidate, mode)

        if (!candidate.prepareRequired) {
            return PrepareOutcome(
                artifactId = candidate.artifact?.artifactId
                    ?: candidate.artifact?.modelId.orEmpty(),
                artifactDir = cacheDir,
                files = emptyMap(),
                engine = candidate.engine,
                deliveryMode = candidate.deliveryMode,
                preparePolicy = candidate.preparePolicy,
                cached = true,
            )
        }
        val artifact = candidate.artifact
            ?: throw PrepareException(
                "Candidate marks prepareRequired=true but carries no artifact plan. " +
                    "This is a server contract violation; refusing to prepare."
            )
        val expanded = expandStaticRecipeSource(artifact)
        val descriptor = buildDescriptor(expanded)
        val dir = artifactDirFor(descriptor.artifactId).also { it.mkdirs() }
        val cached = alreadyVerified(descriptor, dir)
        if (cached != null) {
            return PrepareOutcome(
                artifactId = descriptor.artifactId,
                artifactDir = dir,
                files = cached,
                engine = candidate.engine,
                deliveryMode = candidate.deliveryMode,
                preparePolicy = candidate.preparePolicy,
                cached = true,
            )
        }
        val result = downloader.download(descriptor, dir)
        return PrepareOutcome(
            artifactId = descriptor.artifactId,
            artifactDir = dir,
            files = result.files,
            engine = candidate.engine,
            deliveryMode = candidate.deliveryMode,
            preparePolicy = candidate.preparePolicy,
            cached = false,
        )
    }

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    private fun validateForPrepare(candidate: PrepareCandidate) {
        if (candidate.locality != "local") {
            throw PrepareException(
                "Candidate locality is \"${candidate.locality}\"; only \"local\" candidates are preparable."
            )
        }
        if (candidate.deliveryMode != "sdk_runtime") {
            throw PrepareException(
                "Candidate deliveryMode is \"${candidate.deliveryMode}\"; only \"sdk_runtime\" is preparable."
            )
        }
        if (candidate.preparePolicy == PreparePolicy.DISABLED) {
            throw PrepareException("Candidate preparePolicy is DISABLED; refusing to prepare.")
        }
        if (!candidate.prepareRequired) return
        val artifact = candidate.artifact
            ?: throw PrepareException("Candidate has prepareRequired=true but no artifact plan.")
        if (artifact.digest.isNullOrEmpty()) {
            throw PrepareException(
                "Artifact '${artifact.artifactId ?: artifact.modelId}' is missing 'digest'; " +
                    "refusing to prepare without integrity."
            )
        }
        if (artifact.downloadUrls.isEmpty() && artifact.source != "static_recipe") {
            throw PrepareException(
                "Artifact '${artifact.artifactId ?: artifact.modelId}' has no downloadUrls. " +
                    "Cannot prepare; the planner must emit at least one endpoint."
            )
        }
        if (artifact.requiredFiles.size > 1 && artifact.manifestUri == null) {
            throw PrepareException(
                "Artifact '${artifact.artifactId ?: artifact.modelId}' lists ${artifact.requiredFiles.size} " +
                    "requiredFiles but the planner emitted no manifestUri."
            )
        }
        if (artifact.requiredFiles.size == 1) {
            DurableDownloader.validateRelativePath(artifact.requiredFiles[0])
        }
        val id = artifact.artifactId ?: artifact.modelId
        if (id.isEmpty()) {
            throw PrepareException("Refusing to prepare artifact with empty artifact_id.")
        }
        if (id.contains('\u0000')) {
            throw PrepareException("artifact_id contains a NUL byte: \"$id\"")
        }
    }

    private fun checkExplicitOnlyVsMode(candidate: PrepareCandidate, mode: PrepareMode) {
        if (candidate.preparePolicy == PreparePolicy.EXPLICIT_ONLY && mode == PrepareMode.LAZY) {
            throw PrepareException(
                "Candidate has preparePolicy=EXPLICIT_ONLY; refusing to prepare lazily. " +
                    "Use PrepareMode.EXPLICIT (or the SDK's explicit prepare entry point)."
            )
        }
    }

    // ---------------------------------------------------------------------
    // Static recipe expansion (PR C-followup option 2)
    // ---------------------------------------------------------------------

    private fun expandStaticRecipeSource(artifact: PrepareArtifactPlan): PrepareArtifactPlan {
        val source = artifact.source ?: return artifact
        if (source != "static_recipe") {
            throw PrepareException(
                "Artifact source \"$source\" is not recognized by this SDK release. Known: 'static_recipe'."
            )
        }
        val recipeId = artifact.recipeId
            ?: throw PrepareException("Artifact has source='static_recipe' but no recipeId.")
        val recipe = StaticRecipeRegistry.recipe(recipeId)
            ?: throw PrepareException(
                "Artifact source='static_recipe' but recipeId \"$recipeId\" is not in the SDK's recipe table."
            )
        if (artifact.digest != null && artifact.digest != recipe.file.digest) {
            throw PrepareException(
                "Static recipe \"$recipeId\" digest \"${recipe.file.digest}\" does not match " +
                    "planner-declared digest \"${artifact.digest}\"."
            )
        }
        if (artifact.requiredFiles.isNotEmpty() && artifact.requiredFiles != listOf(recipe.file.relativePath)) {
            throw PrepareException(
                "Static recipe \"$recipeId\" ships file \"${recipe.file.relativePath}\"; " +
                    "planner-declared requiredFiles ${artifact.requiredFiles} does not match."
            )
        }
        return artifact.copy(
            artifactId = artifact.artifactId ?: recipe.modelId,
            digest = recipe.file.digest,
            sizeBytes = artifact.sizeBytes ?: recipe.file.sizeBytes,
            requiredFiles = listOf(recipe.file.relativePath),
            downloadUrls = listOf(
                DownloadEndpoint(
                    url = recipe.file.url,
                    headers = mapOf("X-Octomil-Recipe-Path" to recipe.file.relativePath),
                )
            ),
            manifestUri = null,
            source = null,
            recipeId = null,
        )
    }

    // ---------------------------------------------------------------------
    // Descriptor / cache
    // ---------------------------------------------------------------------

    private fun buildDescriptor(artifact: PrepareArtifactPlan): ArtifactDescriptor {
        val endpoints = artifact.downloadUrls
        val id = artifact.artifactId ?: artifact.modelId
        val digest = artifact.digest
            ?: throw PrepareException("Artifact '$id' has no digest.")
        val required: List<RequiredFile> = when {
            artifact.requiredFiles.size == 1 -> {
                val rel = DurableDownloader.validateRelativePath(artifact.requiredFiles[0])
                listOf(RequiredFile(rel, digest, artifact.sizeBytes))
            }
            artifact.requiredFiles.isEmpty() -> {
                listOf(RequiredFile("", digest, artifact.sizeBytes))
            }
            else -> {
                throw PrepareException(
                    "Multi-file artifacts via manifestUri are not yet implemented in the Android SDK; " +
                        "restrict to single-file plans for now."
                )
            }
        }
        return ArtifactDescriptor(id, required, endpoints)
    }

    private fun alreadyVerified(descriptor: ArtifactDescriptor, dir: File): Map<String, File>? {
        val verified = HashMap<String, File>()
        for (req in descriptor.requiredFiles) {
            val target = if (req.relativePath.isEmpty()) {
                File(dir, "artifact")
            } else {
                DurableDownloader.safeJoin(dir, req.relativePath)
            }
            if (!target.exists()) return null
            if (!DurableDownloader.digestMatches(target, req.digest)) return null
            verified[req.relativePath] = target
        }
        return verified
    }

    companion object {
        /** Where artifacts live by default. Mirrors the other SDKs. */
        fun defaultCacheDir(): File {
            val cacheRoot = System.getenv("OCTOMIL_CACHE_DIR")
            if (cacheRoot != null) return File(cacheRoot, "artifacts")
            val xdg = System.getenv("XDG_CACHE_HOME")
            if (xdg != null) return File(xdg, "octomil/artifacts")
            val home = System.getProperty("user.home") ?: "."
            return File(home, ".cache/octomil/artifacts")
        }
    }
}

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

enum class PrepareMode {
    /** Runtime-driven prepare (just-in-time during inference dispatch). */
    LAZY,
    /** Caller-driven prepare (CLI, `client.prepare`). */
    EXPLICIT,
}

enum class PreparePolicy(val wireValue: String) {
    LAZY("lazy"),
    EXPLICIT_ONLY("explicit_only"),
    DISABLED("disabled");

    companion object {
        fun fromWire(value: String?): PreparePolicy = when (value) {
            "lazy", null -> LAZY
            "explicit_only" -> EXPLICIT_ONLY
            "disabled" -> DISABLED
            else -> LAZY
        }
    }
}

data class PrepareArtifactPlan(
    val modelId: String,
    val artifactId: String? = null,
    val digest: String? = null,
    val sizeBytes: Long? = null,
    val requiredFiles: List<String> = emptyList(),
    val downloadUrls: List<DownloadEndpoint> = emptyList(),
    val manifestUri: String? = null,
    val source: String? = null,
    val recipeId: String? = null,
)

data class PrepareCandidate(
    val locality: String,
    val engine: String? = null,
    val artifact: PrepareArtifactPlan? = null,
    val deliveryMode: String = "sdk_runtime",
    val prepareRequired: Boolean = true,
    val preparePolicy: PreparePolicy = PreparePolicy.LAZY,
)

data class PrepareOutcome(
    val artifactId: String,
    val artifactDir: File,
    val files: Map<String, File>,
    val engine: String?,
    val deliveryMode: String,
    val preparePolicy: PreparePolicy,
    val cached: Boolean,
)

class PrepareException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// ---------------------------------------------------------------------------
// StaticRecipeRegistry
// ---------------------------------------------------------------------------

data class StaticRecipeFile(
    val relativePath: String,
    val url: String,
    val digest: String,
    val sizeBytes: Long? = null,
)

data class StaticRecipe(
    val modelId: String,
    val file: StaticRecipeFile,
)

/**
 * In-process registry of static recipes the SDK is willing to expand
 * under `source="static_recipe"`. Ships the canonical Kokoro v0.19
 * recipe by default, mirroring Python `static_recipes._RECIPES`.
 */
object StaticRecipeRegistry {
    private val recipes: MutableMap<String, StaticRecipe>

    init {
        val kokoro = StaticRecipe(
            modelId = "kokoro-82m",
            file = StaticRecipeFile(
                relativePath = "kokoro-en-v0_19.tar.bz2",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models",
                digest = "sha256:912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7",
            ),
        )
        recipes = HashMap<String, StaticRecipe>().apply {
            put("kokoro-82m", kokoro)
            put("kokoro-en-v0_19", kokoro)
        }
    }

    fun register(recipe: StaticRecipe, id: String? = null) {
        synchronized(recipes) {
            recipes[id ?: recipe.modelId] = recipe
        }
    }

    fun recipe(id: String): StaticRecipe? = synchronized(recipes) { recipes[id] }
}
