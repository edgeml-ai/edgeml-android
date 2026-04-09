package ai.octomil.sdk

import ai.octomil.client.EmbeddingClient
import ai.octomil.client.EmbeddingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Facade namespace for the embeddings API.
 *
 * Wraps [EmbeddingClient] (which is synchronous / blocking) behind
 * suspend functions that dispatch onto [Dispatchers.IO].
 *
 * Obtain an instance via [Octomil.embeddings] after calling [Octomil.initialize].
 */
class FacadeEmbeddings internal constructor(
    private val embeddingClient: EmbeddingClient,
) {
    /**
     * Create embeddings for a single input string.
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
        return withContext(Dispatchers.IO) {
            embeddingClient.embed(model, input)
        }
    }

    /**
     * Create embeddings for multiple input strings.
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
        return withContext(Dispatchers.IO) {
            embeddingClient.embed(model, input)
        }
    }
}
