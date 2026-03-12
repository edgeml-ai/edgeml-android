package ai.octomil

import android.content.Context
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Strategy interface for resolving a model name to a file on disk.
 *
 * The SDK ships with built-in resolvers for paired models, assets, and
 * the download cache. Custom implementations can add CDN downloads,
 * custom directories, or any other model source.
 *
 * ## Built-in resolvers
 *
 * | Factory method | Search location |
 * |---|---|
 * | [paired] | `filesDir/octomil_models/{name}/{version}/` |
 * | [assets] | `assets/{name}.tflite` (copied to cache) |
 * | [cache]  | `cacheDir/octomil_models/` |
 *
 * ## Chaining
 *
 * ```kotlin
 * val resolver = ModelResolver.chain(
 *     ModelResolver.paired(),
 *     ModelResolver.assets(),
 *     MyCustomResolver(),
 * )
 * ```
 */
fun interface ModelResolver {

    /**
     * Resolve a model name to a file.
     *
     * @param context Android context.
     * @param name Logical model name (e.g., "mobilenet-v2").
     * @return The model [File] on disk, or null if this resolver cannot find it.
     */
    suspend fun resolve(context: Context, name: String): File?

    companion object {

        /**
         * Resolver that checks paired models persisted by the pairing flow.
         *
         * Looks in `filesDir/octomil_models/{name}/` and returns the
         * latest version (directory sorted descending by name).
         */
        fun paired(): ModelResolver = ModelResolver { context, name ->
            val modelsDir = File(context.filesDir, "octomil_models/$name")
            if (!modelsDir.exists()) return@ModelResolver null

            val latestVersionDir = modelsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.name }
                ?: return@ModelResolver null

            latestVersionDir.listFiles()?.firstOrNull { it.isFile }
        }

        /**
         * Resolver that checks the app's assets directory.
         *
         * Looks for `{name}.tflite` in assets. If found, copies to cache
         * and returns the cached file.
         */
        fun assets(): ModelResolver = ModelResolver { context, name ->
            val assetName = "$name.tflite"
            try {
                context.assets.open(assetName).use { it.read() }
                Octomil.copyAssetToCache(context, assetName)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Resolver that checks the model download cache.
         *
         * Looks in `cacheDir/octomil_models/` for files matching the
         * model name pattern.
         */
        fun cache(): ModelResolver = ModelResolver { context, name ->
            val cacheDir = File(context.cacheDir, "octomil_models")
            if (!cacheDir.exists()) return@ModelResolver null

            cacheDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(name) }
                ?.maxByOrNull { it.lastModified() }
        }

        /**
         * Compose multiple resolvers with first-match semantics.
         */
        fun chain(vararg resolvers: ModelResolver): ModelResolver = ModelResolver { context, name ->
            for (resolver in resolvers) {
                val file = resolver.resolve(context, name)
                if (file != null) return@ModelResolver file
            }
            null
        }

        /**
         * The default resolution chain: paired -> assets -> cache.
         */
        fun default(): ModelResolver = chain(
            paired(),
            assets(),
            cache(),
        )
    }

    /**
     * Synchronous resolve for non-suspend contexts (e.g., chat client creation).
     *
     * Built-in resolvers only do file I/O, so this is safe to call from
     * any thread. Custom resolvers with actual suspension should use [resolve].
     */
    fun resolveSync(context: Context, name: String): File? =
        runBlocking { resolve(context, name) }
}
