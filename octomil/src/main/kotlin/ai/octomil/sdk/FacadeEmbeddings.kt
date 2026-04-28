package ai.octomil.sdk

import ai.octomil.client.EmbeddingClient
import ai.octomil.client.EmbeddingResult
import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RoutingPolicy
import ai.octomil.runtime.routing.ModelRefParser
import ai.octomil.runtime.routing.ParsedModelRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Facade namespace for the embeddings API.
 *
 * Wraps [EmbeddingClient] (which is synchronous / blocking) behind
 * suspend functions that dispatch onto [Dispatchers.IO]. Honors
 * `app=` / `@app/` model refs and `policy=` arguments so the
 * embeddings surface lines up with the other capability lifecycles.
 *
 * Obtain an instance via [Octomil.embeddings] after calling [Octomil.initialize].
 */
class FacadeEmbeddings internal constructor(
    private val embeddingClient: EmbeddingClient,
) {
    /**
     * Create embeddings for a single input string.
     *
     * Routes through [resolveEmbeddingsRoute] so an `@app/` prefix
     * is parsed up front (the canonical model id is what the
     * underlying client receives), not silently passed through to
     * the network.
     *
     * ```kotlin
     * val result = client.embeddings.create(
     *     model = "nomic-embed-text-v1.5",
     *     input = "On-device AI inference at scale",
     * )
     * println(result.embeddings.first().take(5))
     * ```
     */
    suspend fun create(model: String, input: String): EmbeddingResult {
        val resolved = resolveEmbeddingsRoute(model, app = null, policy = null)
        return withContext(Dispatchers.IO) {
            embeddingClient.embed(resolved.canonicalModel, input)
        }
    }

    /**
     * Create embeddings for multiple input strings. Same routing
     * semantics as the single-input overload.
     *
     * ```kotlin
     * val result = client.embeddings.create(
     *     model = "nomic-embed-text-v1.5",
     *     input = listOf("Hello", "World"),
     * )
     * result.embeddings.forEach { vec -> println(vec.take(3)) }
     * ```
     */
    suspend fun create(model: String, input: List<String>): EmbeddingResult {
        val resolved = resolveEmbeddingsRoute(model, app = null, policy = null)
        return withContext(Dispatchers.IO) {
            embeddingClient.embed(resolved.canonicalModel, input)
        }
    }

    /**
     * App-aware single-input embeddings. The combined `model` /
     * `app` identity is resolved up front; an explicit `app=` is
     * required to disagree with an `@app/` ref, otherwise the call
     * fails closed (never silently rerouted). `policy=private` and
     * `policy=local_only` refuse cloud transport.
     */
    suspend fun create(
        model: String,
        input: String,
        app: String? = null,
        policy: RoutingPolicy? = null,
    ): EmbeddingResult {
        val resolved = resolveEmbeddingsRoute(model, app, policy)
        return withContext(Dispatchers.IO) {
            embeddingClient.embed(resolved.canonicalModel, input)
        }
    }

    /**
     * App-aware batch embeddings. Same identity gates as the
     * single-input overload.
     */
    suspend fun create(
        model: String,
        input: List<String>,
        app: String? = null,
        policy: RoutingPolicy? = null,
    ): EmbeddingResult {
        val resolved = resolveEmbeddingsRoute(model, app, policy)
        return withContext(Dispatchers.IO) {
            embeddingClient.embed(resolved.canonicalModel, input)
        }
    }

    internal fun resolveEmbeddingsRoute(
        model: String,
        app: String?,
        policy: RoutingPolicy?,
    ): EmbeddingsRoute {
        require(model.isNotBlank()) { "model must be a non-empty string." }
        val parsed = ModelRefParser.parse(model)
        val (canonical, parsedApp) = when (parsed) {
            is ParsedModelRef.AppRef -> parsed.capability to parsed.slug
            is ParsedModelRef.ModelRef -> parsed.model to null
            is ParsedModelRef.UnknownRef -> parsed.raw to null
            is ParsedModelRef.AliasRef -> parsed.alias to null
            is ParsedModelRef.CapabilityRef -> parsed.capability to null
            is ParsedModelRef.DeploymentRef -> parsed.deploymentId to null
            is ParsedModelRef.ExperimentRef -> parsed.experimentId to null
            is ParsedModelRef.DefaultRef -> "" to null
        }
        if (canonical.isBlank()) {
            throw OctomilException(
                OctomilErrorCode.INVALID_INPUT,
                "Could not resolve embeddings model id from \"$model\".",
            )
        }
        if (parsedApp != null && app != null && parsedApp != app) {
            throw OctomilException(
                OctomilErrorCode.INVALID_INPUT,
                "@app/ ref \"$parsedApp\" does not match app= argument \"$app\".",
            )
        }
        val effectiveApp = parsedApp ?: app
        // Privacy gate: Android embeddings runs cloud-side today.
        // private / local_only therefore cannot succeed; refusing
        // is the right action — never silently fall back.
        if (policy == RoutingPolicy.PRIVATE || policy == RoutingPolicy.LOCAL_ONLY) {
            throw OctomilException(
                OctomilErrorCode.UNSUPPORTED_MODALITY,
                "policy=${policy.code} cannot route to client.embeddings on Android; " +
                    "no on-device embeddings backend is registered.",
            )
        }
        return EmbeddingsRoute(canonical, effectiveApp, policy)
    }
}

/** Internal: resolved view of an embeddings call. */
internal data class EmbeddingsRoute(
    val canonicalModel: String,
    val appSlug: String?,
    val policy: RoutingPolicy?,
)
