package ai.octomil.manifest

import ai.octomil.Octomil
import ai.octomil.generated.ArtifactResourceKind
import ai.octomil.generated.DeliveryMode
import ai.octomil.generated.ModelCapability
import ai.octomil.runtime.core.ModelRuntime
import ai.octomil.runtime.core.ModelRuntimeRegistry
import ai.octomil.runtime.engines.LocalFileModelRuntime
import ai.octomil.runtime.routing.CloudModelRuntime
import ai.octomil.runtime.routing.RouterModelRuntime
import ai.octomil.runtime.routing.RoutingPolicy
import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * Bootstraps runtimes from an [AppManifest] and provides capability-based lookup.
 *
 * For each [AppModelEntry] in the manifest:
 * - **BUNDLED**: copies from assets, registers a [LocalFileModelRuntime]
 *   with resource bindings and engine config
 * - **MANAGED**: queues download via [ModelReadinessManager], pre-wires a
 *   [RouterModelRuntime] that resolves resource bindings at load time
 * - **CLOUD**: registers a [CloudModelRuntime]
 *
 * After [bootstrap], callers resolve runtimes by capability rather than by model ID:
 * ```kotlin
 * val runtime = catalog.runtimeForCapability(ModelCapability.CHAT)
 * ```
 */
class ModelCatalogService(
    private val manifest: AppManifest,
    private val context: Context,
    private val serverUrl: String = "https://api.octomil.com",
    private val apiKey: String? = null,
) {
    private val capabilityRuntimes = mutableMapOf<ModelCapability, ModelRuntime>()

    /** Readiness manager for managed model downloads. */
    val readiness: ModelReadinessManager = ModelReadinessManager(context)

    /**
     * Bootstrap all manifest entries. Call once at startup after [Octomil.init].
     *
     * Registers runtimes in [ModelRuntimeRegistry] for both capability-based
     * and ID-based lookup.
     */
    suspend fun bootstrap() {
        for (entry in manifest.models) {
            try {
                val runtime = bootstrapEntry(entry)
                capabilityRuntimes[entry.capability] = runtime
                ModelRuntimeRegistry.register(entry.id) { runtime }
            } catch (e: Exception) {
                if (entry.required) throw e
                Timber.w(e, "Failed to bootstrap optional model: ${entry.id}")
            }
        }
    }

    /** Resolve a runtime by capability, or null if none registered. */
    fun runtimeForCapability(capability: ModelCapability): ModelRuntime? =
        capabilityRuntimes[capability]

    /** Resolve a runtime from a [ModelRef]. */
    fun runtimeForRef(ref: ModelRef): ModelRuntime? = when (ref) {
        is ModelRef.Id -> ModelRuntimeRegistry.resolve(ref.value)
        is ModelRef.Capability -> runtimeForCapability(ref.value)
    }

    /** The model ID configured for a given capability, if any. */
    fun modelIdForCapability(capability: ModelCapability): String? =
        manifest.entryFor(capability)?.id

    private suspend fun bootstrapEntry(entry: AppModelEntry): ModelRuntime {
        return when (entry.delivery) {
            DeliveryMode.BUNDLED -> bootstrapBundled(entry)
            DeliveryMode.MANAGED -> bootstrapManaged(entry)
            DeliveryMode.CLOUD -> bootstrapCloud(entry)
        }
    }

    private suspend fun bootstrapBundled(entry: AppModelEntry): ModelRuntime {
        val assetPath = entry.bundledPath
            ?: error("bundledPath required for BUNDLED delivery: ${entry.id}")

        val file = Octomil.copyAssetToCache(context, assetPath)

        // Build resource bindings from manifest resources
        val bindings = buildResourceBindings(entry, file.parentFile ?: file)

        return LocalFileModelRuntime(
            modelFile = file,
            resourceBindings = bindings,
            engineConfig = entry.engineConfig,
        )
    }

    private suspend fun bootstrapManaged(entry: AppModelEntry): ModelRuntime {
        // Queue the download — the readiness manager tracks progress
        readiness.enqueue(entry)

        // Pre-wire a router: local when ready, cloud fallback based on policy
        val localFactory = localFactory@{ _: String ->
            val file = readiness.resolvedFile(entry.capability) ?: return@localFactory null
            val bindings = buildResourceBindings(entry, file.parentFile ?: file)
            LocalFileModelRuntime(
                modelFile = file,
                resourceBindings = bindings,
                engineConfig = entry.engineConfig,
            )
        }

        val cloudFactory = when (entry.effectiveRoutingPolicy) {
            ai.octomil.generated.RoutingPolicy.LOCAL_ONLY -> null
            else -> { _: String -> CloudModelRuntime(serverUrl, apiKey, entry.id) }
        }

        val routingPolicy = toInternalRoutingPolicy(entry.effectiveRoutingPolicy)

        return RouterModelRuntime(
            localFactory = localFactory,
            cloudFactory = cloudFactory,
            defaultPolicy = routingPolicy,
        )
    }

    private fun bootstrapCloud(entry: AppModelEntry): ModelRuntime {
        return CloudModelRuntime(serverUrl, apiKey, entry.id)
    }

    /**
     * Build a resource kind -> file mapping from the entry's manifest resources.
     * Falls back to scanning the package directory if no resources are declared.
     */
    private fun buildResourceBindings(
        entry: AppModelEntry,
        packageDir: File,
    ): Map<ArtifactResourceKind, File> {
        if (entry.resources.isEmpty()) return emptyMap()

        return entry.resources.associate { resource ->
            val path = resource.path ?: resource.uri.substringAfterLast("/")
            resource.kind to File(packageDir, path)
        }
    }

    private fun toInternalRoutingPolicy(
        policy: ai.octomil.generated.RoutingPolicy,
    ): RoutingPolicy = when (policy) {
        ai.octomil.generated.RoutingPolicy.LOCAL_ONLY -> RoutingPolicy.LocalOnly
        ai.octomil.generated.RoutingPolicy.LOCAL_FIRST -> RoutingPolicy.Auto(
            preferLocal = true,
            fallback = "cloud",
        )
        ai.octomil.generated.RoutingPolicy.CLOUD_ONLY -> RoutingPolicy.CloudOnly
        ai.octomil.generated.RoutingPolicy.AUTO -> RoutingPolicy.Auto(
            preferLocal = true,
            fallback = "cloud",
        )
    }
}
