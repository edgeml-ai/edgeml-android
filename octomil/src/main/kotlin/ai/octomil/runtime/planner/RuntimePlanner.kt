package ai.octomil.runtime.planner

import android.content.Context
import timber.log.Timber

/**
 * Runtime planner -- resolves the best engine/locality for a request.
 *
 * Resolution order:
 * 1. Collect device profile
 * 2. Check local plan cache
 * 3. If network allowed and not private policy, fetch server plan
 * 4. Validate server plan against installed runtimes
 * 5. Check real local benchmark cache
 * 6. Select an explicitly model-capable local runtime, if one exists
 * 7. Return [RuntimeSelection]
 *
 * Privacy guarantees:
 * - No prompts, responses, file paths, or user input in telemetry
 * - "private" policy skips server plan fetch and telemetry upload
 * - "cloud_only" policy skips local benchmarking
 *
 * Lifecycle-aware: does not start background services. All work is done
 * synchronously on the caller's thread (or coroutine). Callers should
 * invoke from a background dispatcher.
 */
class RuntimePlanner(
    private val context: Context,
    private val store: RuntimePlannerStore = RuntimePlannerStore.create(context),
    private val client: RuntimePlannerClient? = null,
    private val profileCollector: (() -> DeviceRuntimeProfile)? = null,
) {
    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Resolve the best runtime for a model + capability.
     *
     * This call is blocking (network I/O). Invoke from a background thread
     * or coroutine dispatcher.
     *
     * @param model Model identifier (e.g. "gemma-2b", "llama-8b").
     * @param capability The capability needed (e.g. "text", "embeddings", "audio").
     * @param routingPolicy One of "local_first", "cloud_first", "local_only",
     *                      "cloud_only", "private".
     * @param allowNetwork Whether to contact the server for plan/telemetry.
     *                     Set to false for fully-offline operation.
     * @return Resolved [RuntimeSelection].
     */
    fun resolve(
        model: String,
        capability: String,
        routingPolicy: String = "local_first",
        allowNetwork: Boolean = true,
    ): RuntimeSelection {
        // Step 1: Collect device profile
        val device = profileCollector?.invoke()
            ?: DeviceRuntimeProfileCollector.collect(context)

        val isPrivate = routingPolicy == "private"
        val isCloudOnly = routingPolicy == "cloud_only"

        // Step 2: Check local plan cache
        val cacheKey = RuntimePlannerStore.makeCacheKey(
            model = model,
            capability = capability,
            policy = routingPolicy,
            sdkVersion = device.sdkVersion,
            platform = device.platform,
            arch = device.arch,
            chip = device.chip,
            installedHash = RuntimePlannerStore.installedRuntimesHash(device.installedRuntimes),
        )

        val cachedPlan = store.getPlan(cacheKey)
        if (cachedPlan != null) {
            Timber.d("Using cached plan for %s/%s", model, capability)
            val selection = resolveFromServerPlan(cachedPlan, device, source = "cache")
            if (selection != null) return selection
            Timber.d("Cached plan had no viable candidates; continuing resolution")
        }

        // Step 3: Fetch server plan if network allowed and not private
        var serverPlan: RuntimePlanResponse? = null
        if (allowNetwork && client != null && !isPrivate) {
            val request = RuntimePlanRequest(
                model = model,
                capability = capability,
                device = device,
                routingPolicy = routingPolicy,
                allowCloudFallback = routingPolicy != "local_only",
            )
            serverPlan = try {
                client.fetchPlan(request)
            } catch (e: Exception) {
                Timber.d(e, "Server plan fetch failed")
                null
            }

            if (serverPlan != null) {
                Timber.d("Received server plan for %s/%s", model, capability)
                store.putPlan(cacheKey, serverPlan, serverPlan.planTtlSeconds)
            }
        }

        // Step 4: Validate server plan against installed runtimes
        if (serverPlan != null) {
            val selection = resolveFromServerPlan(serverPlan, device, source = "server_plan")
            if (selection != null) return selection
        }

        // Step 5: Check local benchmark cache
        val bmCacheKey = RuntimePlannerStore.makeCacheKey(
            model = model,
            capability = capability,
            policy = routingPolicy,
            sdkVersion = device.sdkVersion,
            platform = device.platform,
            arch = device.arch,
            chip = device.chip,
            installedHash = RuntimePlannerStore.installedRuntimesHash(device.installedRuntimes),
        )

        val cachedBenchmark = store.getBenchmark(bmCacheKey)
        if (cachedBenchmark != null) {
            return RuntimeSelection(
                locality = "local",
                engine = RuntimeEngineIds.canonical(cachedBenchmark.engine),
                benchmarkRan = false,
                source = "cache",
                reason = "cached benchmark: %.1f tok/s".format(cachedBenchmark.tokensPerSecond),
            )
        }

        // Step 6-7: Cloud-only skips local benchmarking
        if (isCloudOnly) {
            return RuntimeSelection(
                locality = "cloud",
                engine = null,
                source = "fallback",
                reason = "cloud_only policy -- no local engines attempted",
            )
        }

        // For no-plan local selection, only use runtimes that explicitly
        // declare support for this model/capability. Plain engine availability
        // is not enough to prove the requested model can run.
        val installedEngines = device.installedRuntimes.filter {
            supportsLocalDefault(it, model, capability)
        }
        if (installedEngines.isNotEmpty()) {
            val best = installedEngines.first()
            val engine = RuntimeEngineIds.canonical(best.engine)

            return RuntimeSelection(
                locality = "local",
                engine = engine,
                benchmarkRan = false,
                source = "local_default",
                reason = "selected explicitly reported local engine: $engine",
            )
        }

        return fallbackSelection(routingPolicy, "no local engine available")
    }

    /**
     * Persist and upload a benchmark collected from a real inference run.
     *
     * The planner does not synthesize benchmark entries during resolution;
     * engine integrations should call this after actual execution.
     */
    fun recordBenchmark(
        model: String,
        capability: String,
        engine: String,
        tokensPerSecond: Double,
        ttftMs: Double = 0.0,
        memoryMb: Double = 0.0,
        routingPolicy: String = "local_first",
        success: Boolean = true,
        allowNetwork: Boolean = true,
    ) {
        val device = profileCollector?.invoke()
            ?: DeviceRuntimeProfileCollector.collect(context)
        val bmCacheKey = RuntimePlannerStore.makeCacheKey(
            model = model,
            capability = capability,
            policy = routingPolicy,
            sdkVersion = device.sdkVersion,
            platform = device.platform,
            arch = device.arch,
            chip = device.chip,
            installedHash = RuntimePlannerStore.installedRuntimesHash(device.installedRuntimes),
        )
        val canonicalEngine = RuntimeEngineIds.canonical(engine)

        store.putBenchmark(
            bmCacheKey,
            CachedBenchmark(
                model = model,
                capability = capability,
                engine = canonicalEngine,
                tokensPerSecond = tokensPerSecond,
                ttftMs = ttftMs,
                memoryMb = memoryMb,
            ),
        )

        if (routingPolicy != "private" && client != null && allowNetwork) {
            uploadBenchmarkTelemetry(
                model = model,
                capability = capability,
                engine = canonicalEngine,
                device = device,
                success = success,
                tokensPerSecond = tokensPerSecond,
                ttftMs = ttftMs,
                memoryMb = memoryMb,
            )
        }
    }

    // =========================================================================
    // Server Plan Resolution
    // =========================================================================

    internal fun resolveFromServerPlan(
        plan: RuntimePlanResponse,
        device: DeviceRuntimeProfile,
        source: String,
    ): RuntimeSelection? {
        val installedEngines = device.installedRuntimes
            .filter { it.available }
            .map { RuntimeEngineIds.canonical(it.engine) }
            .toSet()

        selectCandidate(
            candidates = plan.candidates,
            installedEngines = installedEngines,
            source = source,
            fallbackCandidates = plan.fallbackCandidates,
            fallbackPrefix = null,
        )?.let { return it }

        return selectCandidate(
            candidates = plan.fallbackCandidates,
            installedEngines = installedEngines,
            source = source,
            fallbackCandidates = emptyList(),
            fallbackPrefix = "fallback: ",
        )
    }

    private fun selectCandidate(
        candidates: List<RuntimeCandidatePlan>,
        installedEngines: Set<String>,
        source: String,
        fallbackCandidates: List<RuntimeCandidatePlan>,
        fallbackPrefix: String?,
    ): RuntimeSelection? {
        for (candidate in candidates) {
            if (candidate.locality == "local") {
                val engine = RuntimeEngineIds.canonicalOrNull(candidate.engine)
                if (engine != null && engine !in installedEngines) {
                    continue // Skip engines we don't have
                } else if (engine == null && installedEngines.isEmpty()) {
                    continue // "any local" still requires at least one runtime
                }
                return RuntimeSelection(
                    locality = "local",
                    engine = engine,
                    artifact = candidate.artifact,
                    benchmarkRan = false,
                    source = source,
                    fallbackCandidates = fallbackCandidates,
                    reason = "${fallbackPrefix.orEmpty()}${candidate.reason}",
                )
            } else if (candidate.locality == "cloud") {
                return RuntimeSelection(
                    locality = "cloud",
                    engine = RuntimeEngineIds.canonicalOrNull(candidate.engine),
                    artifact = candidate.artifact,
                    benchmarkRan = false,
                    source = source,
                    fallbackCandidates = fallbackCandidates,
                    reason = "${fallbackPrefix.orEmpty()}${candidate.reason}",
                )
            }
        }

        return null
    }

    // =========================================================================
    // Telemetry
    // =========================================================================

    private fun uploadBenchmarkTelemetry(
        model: String,
        capability: String,
        engine: String,
        device: DeviceRuntimeProfile,
        success: Boolean,
        tokensPerSecond: Double,
        ttftMs: Double,
        memoryMb: Double,
    ) {
        try {
            client?.uploadBenchmark(
                BenchmarkTelemetryPayload(
                    source = "planner",
                    model = model,
                    capability = capability,
                    engine = RuntimeEngineIds.canonical(engine),
                    device = device,
                    success = success,
                    tokensPerSecond = tokensPerSecond,
                    ttftMs = ttftMs,
                    peakMemoryBytes = (memoryMb * 1024 * 1024).toLong(),
                    metadata = mapOf("selection_source" to "benchmark"),
                )
            )
        } catch (e: Exception) {
            Timber.d(e, "Benchmark telemetry upload failed")
        }
    }

    // =========================================================================
    // Fallback
    // =========================================================================

    private fun fallbackSelection(routingPolicy: String, reason: String): RuntimeSelection {
        return if (routingPolicy in listOf("local_only", "private")) {
            RuntimeSelection(
                locality = "local",
                engine = null,
                source = "fallback",
                reason = reason,
            )
        } else {
            RuntimeSelection(
                locality = "cloud",
                engine = null,
                source = "fallback",
                reason = "$reason -- falling back to cloud",
            )
        }
    }

    private fun supportsLocalDefault(
        runtime: InstalledRuntime,
        model: String,
        capability: String,
    ): Boolean {
        if (!runtime.available) return false

        val models = metadataList(runtime.metadata, "model", "model_id", "models")
        if ("*" !in models && model.lowercase() !in models) return false

        val capabilities = metadataList(runtime.metadata, "capability", "capabilities")
        return capabilities.isEmpty() || "*" in capabilities || capability.lowercase() in capabilities
    }

    private fun metadataList(metadata: Map<String, String>, vararg keys: String): Set<String> {
        return keys
            .mapNotNull { metadata[it] }
            .flatMap { it.split(",") }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
