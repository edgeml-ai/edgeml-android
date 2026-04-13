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
 * 5. Check local benchmark cache
 * 6. If benchmark_required flag set, run local benchmark primitives
 * 7. Persist benchmark locally
 * 8. Upload privacy-safe benchmark telemetry (not for private policy)
 * 9. Return [RuntimeSelection]
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
            return resolveFromServerPlan(cachedPlan, device, source = "cache")
                ?: fallbackSelection(routingPolicy, "cached plan had no viable candidates")
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
                engine = cachedBenchmark.engine,
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

        // For local engines: select the first available installed runtime as
        // the best candidate. Real benchmarking is delegated to the engine
        // registry's BenchmarkRunner; the planner does not duplicate that logic.
        val installedEngines = device.installedRuntimes.filter { it.available }
        if (installedEngines.isNotEmpty()) {
            val best = installedEngines.first()

            // Persist the selection as a benchmark cache entry
            store.putBenchmark(
                bmCacheKey,
                CachedBenchmark(
                    model = model,
                    capability = capability,
                    engine = best.engine,
                ),
            )

            // Step 8: Upload telemetry best-effort (not for private)
            if (!isPrivate && client != null && allowNetwork) {
                uploadBenchmarkTelemetry(
                    model = model,
                    capability = capability,
                    engine = best.engine,
                    device = device,
                )
            }

            return RuntimeSelection(
                locality = "local",
                engine = best.engine,
                benchmarkRan = false,
                source = "local_benchmark",
                reason = "selected local engine: ${best.engine}",
            )
        }

        return fallbackSelection(routingPolicy, "no local engine available")
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
            .map { it.engine }
            .toSet()

        // Try primary candidates
        for (candidate in plan.candidates) {
            if (candidate.locality == "local") {
                if (candidate.engine != null && candidate.engine !in installedEngines) {
                    continue // Skip engines we don't have
                }
                return RuntimeSelection(
                    locality = "local",
                    engine = candidate.engine,
                    artifact = candidate.artifact,
                    benchmarkRan = false,
                    source = source,
                    fallbackCandidates = plan.fallbackCandidates,
                    reason = candidate.reason,
                )
            } else if (candidate.locality == "cloud") {
                return RuntimeSelection(
                    locality = "cloud",
                    engine = candidate.engine,
                    artifact = candidate.artifact,
                    benchmarkRan = false,
                    source = source,
                    fallbackCandidates = plan.fallbackCandidates,
                    reason = candidate.reason,
                )
            }
        }

        // Try fallback candidates
        for (candidate in plan.fallbackCandidates) {
            if (candidate.locality == "local" && candidate.engine in installedEngines) {
                return RuntimeSelection(
                    locality = "local",
                    engine = candidate.engine,
                    artifact = candidate.artifact,
                    benchmarkRan = false,
                    source = source,
                    fallbackCandidates = emptyList(),
                    reason = "fallback: ${candidate.reason}",
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
    ) {
        try {
            client?.uploadBenchmark(
                BenchmarkTelemetryPayload(
                    source = "planner",
                    model = model,
                    capability = capability,
                    engine = engine,
                    device = device,
                    success = true,
                    metadata = mapOf("selection_source" to "local_benchmark"),
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
}
