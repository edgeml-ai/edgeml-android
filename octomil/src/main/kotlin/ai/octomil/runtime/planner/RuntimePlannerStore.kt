package ai.octomil.runtime.planner

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.security.MessageDigest

/**
 * Local cache for runtime plans and benchmark results.
 *
 * Uses [SharedPreferences] for persistence -- lightweight and works without
 * Room/SQLite dependencies. Plans and benchmarks are stored as JSON strings
 * keyed by a deterministic hash of model/capability/policy/device attributes.
 *
 * TTL-based expiry: entries older than [planTtlSeconds] or [benchmarkTtlSeconds]
 * are treated as stale and discarded on read.
 *
 * Thread-safe: all public methods are synchronized.
 */
class RuntimePlannerStore(
    private val prefs: SharedPreferences,
    private val planTtlSeconds: Int = DEFAULT_PLAN_TTL,
    private val benchmarkTtlSeconds: Int = DEFAULT_BENCHMARK_TTL,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // =========================================================================
    // Plan Cache
    // =========================================================================

    /**
     * Retrieve a cached plan if it is still fresh.
     *
     * @param cacheKey Deterministic key from [makeCacheKey].
     * @return The cached [RuntimePlanResponse] or null if missing/expired.
     */
    @Synchronized
    fun getPlan(cacheKey: String): RuntimePlanResponse? {
        val key = "$PLAN_PREFIX$cacheKey"
        val raw = prefs.getString(key, null) ?: return null
        val tsKey = "${key}_ts"
        val storedAt = prefs.getLong(tsKey, 0L)
        val ttlKey = "${key}_ttl"
        val ttl = prefs.getInt(ttlKey, planTtlSeconds)

        if (isExpired(storedAt, ttl)) {
            Timber.d("Plan cache expired for key %s", cacheKey)
            prefs.edit().remove(key).remove(tsKey).remove(ttlKey).apply()
            return null
        }

        return try {
            json.decodeFromString(RuntimePlanResponse.serializer(), raw)
        } catch (e: Exception) {
            Timber.d(e, "Failed to parse cached plan")
            prefs.edit().remove(key).remove(tsKey).remove(ttlKey).apply()
            null
        }
    }

    /**
     * Store a plan in the local cache.
     *
     * @param cacheKey Deterministic key from [makeCacheKey].
     * @param plan The plan response to cache.
     * @param ttlSeconds Override TTL for this entry (default: server-specified or [planTtlSeconds]).
     */
    @Synchronized
    fun putPlan(cacheKey: String, plan: RuntimePlanResponse, ttlSeconds: Int = plan.planTtlSeconds) {
        val key = "$PLAN_PREFIX$cacheKey"
        try {
            val encoded = json.encodeToString(RuntimePlanResponse.serializer(), plan)
            prefs.edit()
                .putString(key, encoded)
                .putLong("${key}_ts", nowSeconds())
                .putInt("${key}_ttl", ttlSeconds)
                .apply()
        } catch (e: Exception) {
            Timber.d(e, "Failed to write plan cache")
        }
    }

    // =========================================================================
    // Benchmark Cache
    // =========================================================================

    /**
     * Retrieve a cached benchmark result if still fresh.
     *
     * @param cacheKey Deterministic key.
     * @return Cached benchmark data or null.
     */
    @Synchronized
    fun getBenchmark(cacheKey: String): CachedBenchmark? {
        val key = "$BENCHMARK_PREFIX$cacheKey"
        val raw = prefs.getString(key, null) ?: return null
        val storedAt = prefs.getLong("${key}_ts", 0L)

        if (isExpired(storedAt, benchmarkTtlSeconds)) {
            Timber.d("Benchmark cache expired for key %s", cacheKey)
            prefs.edit().remove(key).remove("${key}_ts").apply()
            return null
        }

        return try {
            json.decodeFromString(CachedBenchmark.serializer(), raw)
        } catch (e: Exception) {
            Timber.d(e, "Failed to parse cached benchmark")
            prefs.edit().remove(key).remove("${key}_ts").apply()
            null
        }
    }

    /**
     * Store a benchmark result in the local cache.
     *
     * @param cacheKey Deterministic key.
     * @param benchmark The result to cache.
     */
    @Synchronized
    fun putBenchmark(cacheKey: String, benchmark: CachedBenchmark) {
        val key = "$BENCHMARK_PREFIX$cacheKey"
        try {
            val encoded = json.encodeToString(
                CachedBenchmark.serializer(),
                benchmark.copy(engine = RuntimeEngineIds.canonical(benchmark.engine)),
            )
            prefs.edit()
                .putString(key, encoded)
                .putLong("${key}_ts", nowSeconds())
                .apply()
        } catch (e: Exception) {
            Timber.d(e, "Failed to write benchmark cache")
        }
    }

    // =========================================================================
    // Cache Management
    // =========================================================================

    /**
     * Clear all planner cache entries (plans + benchmarks).
     */
    @Synchronized
    fun clearAll() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(PLAN_PREFIX) || it.startsWith(BENCHMARK_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private fun isExpired(storedAtSeconds: Long, ttlSeconds: Int): Boolean {
        if (storedAtSeconds == 0L) return true
        return nowSeconds() - storedAtSeconds > ttlSeconds
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    companion object {
        private const val PREFS_NAME = "octomil_runtime_planner"
        private const val PLAN_PREFIX = "plan_"
        private const val BENCHMARK_PREFIX = "bm_"
        internal const val DEFAULT_PLAN_TTL = 604_800      // 7 days
        internal const val DEFAULT_BENCHMARK_TTL = 1_209_600 // 14 days

        /**
         * Create a store using a dedicated SharedPreferences file.
         */
        fun create(context: android.content.Context): RuntimePlannerStore {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            return RuntimePlannerStore(prefs)
        }

        /**
         * Build a deterministic cache key from request components.
         *
         * The key is a SHA-256 prefix hash of the sorted components, matching
         * the Python SDK's `_make_cache_key` approach.
         */
        fun makeCacheKey(
            model: String,
            capability: String,
            policy: String,
            sdkVersion: String,
            platform: String = "Android",
            arch: String,
            chip: String? = null,
            installedHash: String? = null,
        ): String {
            val parts = sortedMapOf(
                "arch" to arch,
                "capability" to capability,
                "chip" to (chip ?: ""),
                "installed_hash" to (installedHash ?: ""),
                "model" to model,
                "platform" to platform,
                "policy" to policy,
                "sdk_version" to sdkVersion,
            )
            val raw = parts.entries.joinToString("|") { "${it.key}=${it.value}" }
            return sha256Prefix(raw, 32)
        }

        /**
         * Compute an installed-runtimes hash for cache key differentiation.
         */
        fun installedRuntimesHash(runtimes: List<InstalledRuntime>): String {
            val sorted = runtimes
                .filter { it.available }
                .map { it.canonicalized() }
                .sortedBy { it.engine }
                .joinToString(",") { "${it.engine}:${it.version ?: ""}" }
            return sha256Prefix(sorted, 16)
        }

        private fun sha256Prefix(input: String, length: Int): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(input.toByteArray(Charsets.UTF_8))
            return md.digest().joinToString("") { "%02x".format(it) }.take(length)
        }
    }
}

/**
 * Cached benchmark result stored in SharedPreferences.
 *
 * Only privacy-safe metrics are persisted. No prompts, responses, or
 * user data is included.
 */
@kotlinx.serialization.Serializable
data class CachedBenchmark(
    @kotlinx.serialization.SerialName("model") val model: String,
    @kotlinx.serialization.SerialName("capability") val capability: String,
    @kotlinx.serialization.SerialName("engine") val engine: String,
    @kotlinx.serialization.SerialName("tokens_per_second") val tokensPerSecond: Double = 0.0,
    @kotlinx.serialization.SerialName("ttft_ms") val ttftMs: Double = 0.0,
    @kotlinx.serialization.SerialName("memory_mb") val memoryMb: Double = 0.0,
)
