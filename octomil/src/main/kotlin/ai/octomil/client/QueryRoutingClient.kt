package ai.octomil.client

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

// =============================================================================
// Models
// =============================================================================

/** Routing policy fetched from the server, cached locally for offline use. */
@Serializable
data class RoutingPolicy(
    val version: Int,
    val thresholds: PolicyThresholds,
    @SerialName("complex_indicators") val complexIndicators: List<String>,
    @SerialName("deterministic_enabled") val deterministicEnabled: Boolean,
    @SerialName("ttl_seconds") val ttlSeconds: Int,
    @SerialName("fetched_at") var fetchedAt: Double = 0.0,
    var etag: String = "",
) {
    @Serializable
    data class PolicyThresholds(
        @SerialName("fast_max_words") val fastMaxWords: Int,
        @SerialName("quality_min_words") val qualityMinWords: Int,
    )

    val isExpired: Boolean
        get() = fetchedAt == 0.0 ||
            (System.currentTimeMillis() / 1000.0 - fetchedAt) > ttlSeconds
}

/** Metadata about an available model for query routing. */
data class QueryModelInfo(
    val name: String,
    val tier: String, // "fast", "balanced", "quality"
    val paramB: Double = 0.0,
    val loaded: Boolean = true,
)

/** Result of a query routing decision. */
data class QueryRoutingDecision(
    val modelName: String,
    val complexityScore: Double,
    val tier: String,
    val strategy: String,
    val fallbackChain: List<String>,
    val deterministicResult: DeterministicResult? = null,
)

/** Result from a deterministic computation (no model needed). */
data class DeterministicResult(
    val answer: String,
    val method: String,
    val confidence: Double = 1.0,
)

// =============================================================================
// Default Policy
// =============================================================================

private val DEFAULT_POLICY = RoutingPolicy(
    version = 1,
    thresholds = RoutingPolicy.PolicyThresholds(
        fastMaxWords = 10,
        qualityMinWords = 50,
    ),
    complexIndicators = listOf(
        "code", "explain", "compare", "analyze", "implement",
        "algorithm", "step by step", "debug", "refactor", "optimize",
        "design", "architecture", "prove", "derive",
    ),
    deterministicEnabled = true,
    ttlSeconds = 300,
)

// =============================================================================
// PolicyClient
// =============================================================================

/**
 * Fetches and caches the query routing policy from the Octomil server.
 *
 * Uses ETag-based conditional requests to minimise bandwidth. Falls back to
 * a persistent disk cache on network failure, then to an embedded default.
 *
 * Thread-safe: [OkHttpClient] is thread-safe and policy reads/writes are
 * guarded by `@Synchronized`.
 */
class PolicyClient(
    private val context: Context,
    private val apiBase: String,
    private val apiKey: String? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serverUrl = apiBase.trimEnd('/')
    private val cacheFile = File(context.cacheDir, "octomil_routing_policy.json")

    @Volatile
    private var cachedPolicy: RoutingPolicy? = null

    /**
     * Return the current routing policy.
     *
     * Priority: in-memory (if not expired) -> server fetch -> disk cache -> default.
     */
    @Synchronized
    fun getPolicy(): RoutingPolicy {
        val mem = cachedPolicy
        if (mem != null && !mem.isExpired) return mem

        // Try fetching from server.
        val fetched = fetchFromServer(mem?.etag)
        if (fetched != null) {
            cachedPolicy = fetched
            persistToDisk(fetched)
            return fetched
        }

        // Server unreachable — try expired in-memory cache.
        if (mem != null) {
            Timber.i("Using expired in-memory policy (version=%d)", mem.version)
            return mem
        }

        // Try disk cache (may be expired but still useful).
        val disk = loadFromDisk()
        if (disk != null) {
            Timber.i("Using disk-cached policy (version=%d)", disk.version)
            cachedPolicy = disk
            return disk
        }

        // Absolute fallback.
        Timber.i("Using embedded default routing policy")
        cachedPolicy = DEFAULT_POLICY
        return DEFAULT_POLICY
    }

    // =========================================================================
    // HTTP
    // =========================================================================

    private fun fetchFromServer(currentEtag: String?): RoutingPolicy? {
        val requestBuilder = Request.Builder()
            .url("$serverUrl/api/v1/route/policy")
            .get()
            .header("User-Agent", "octomil-android/1.0")

        if (apiKey != null) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        if (!currentEtag.isNullOrEmpty()) {
            requestBuilder.header("If-None-Match", currentEtag)
        }

        return try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code == 304 -> {
                        // Policy unchanged — refresh fetchedAt on cached version.
                        cachedPolicy?.let { existing ->
                            existing.fetchedAt = System.currentTimeMillis() / 1000.0
                            persistToDisk(existing)
                        }
                        cachedPolicy
                    }
                    response.isSuccessful -> {
                        val body = response.body?.string() ?: return null
                        val policy = json.decodeFromString(RoutingPolicy.serializer(), body)
                        policy.fetchedAt = System.currentTimeMillis() / 1000.0
                        policy.etag = response.header("ETag") ?: ""
                        policy
                    }
                    else -> {
                        Timber.w("Policy fetch returned HTTP %d", response.code)
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Policy fetch failed")
            null
        }
    }

    // =========================================================================
    // Disk Cache
    // =========================================================================

    private fun persistToDisk(policy: RoutingPolicy) {
        try {
            cacheFile.writeText(json.encodeToString(RoutingPolicy.serializer(), policy))
        } catch (e: Exception) {
            Timber.w(e, "Failed to write routing policy cache")
        }
    }

    private fun loadFromDisk(): RoutingPolicy? {
        if (!cacheFile.exists()) return null
        return try {
            json.decodeFromString(RoutingPolicy.serializer(), cacheFile.readText())
        } catch (e: Exception) {
            Timber.w(e, "Failed to read routing policy cache")
            null
        }
    }
}

// =============================================================================
// QueryRouter
// =============================================================================

private val TIER_ORDER = listOf("fast", "balanced", "quality")
private val ARITHMETIC_PATTERN = Regex("""^\s*[\d+\-*/().^ ]+\s*$""")

/**
 * Routes queries to the appropriate model tier using a cached [RoutingPolicy].
 *
 * Performs lightweight local routing: word-count thresholds, keyword matching
 * for complex indicators, and optional deterministic interception for pure
 * arithmetic expressions. Falls back to server-side routing when policy is
 * unavailable.
 */
class QueryRouter(
    private val models: Map<String, QueryModelInfo>,
    context: Context? = null,
    apiBase: String? = null,
    apiKey: String? = null,
    private val enableDeterministic: Boolean = true,
) {
    private val policyClient: PolicyClient? =
        if (context != null && apiBase != null) PolicyClient(context, apiBase, apiKey)
        else null

    /**
     * Route a conversation to the optimal model tier.
     *
     * @param messages OpenAI-style message list (`role` + `content`).
     */
    fun route(messages: List<Map<String, String>>): QueryRoutingDecision {
        val policy = policyClient?.getPolicy() ?: DEFAULT_POLICY

        // Extract user text from the last user message.
        val userText = messages.lastOrNull { it["role"] == "user" }?.get("content") ?: ""

        // Tier 0: deterministic interception (pure arithmetic).
        if (enableDeterministic && policy.deterministicEnabled && userText.isNotBlank()) {
            val det = tryDeterministic(userText)
            if (det != null) {
                return QueryRoutingDecision(
                    modelName = "",
                    complexityScore = 0.0,
                    tier = "deterministic",
                    strategy = "policy",
                    fallbackChain = emptyList(),
                    deterministicResult = det,
                )
            }
        }

        // Determine tier from word count + complex indicators.
        val wordCount = userText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val textLower = userText.lowercase()
        val hasComplexIndicator = policy.complexIndicators.any { indicator ->
            textLower.contains(indicator)
        }

        val tier = when {
            hasComplexIndicator -> "quality"
            wordCount <= policy.thresholds.fastMaxWords -> "fast"
            wordCount >= policy.thresholds.qualityMinWords -> "quality"
            else -> "balanced"
        }

        val modelName = resolveModel(tier)
        val fallbackChain = buildFallbackChain(modelName)

        // Compute a simple complexity score (normalised 0.0-1.0).
        val lengthSignal = (wordCount.toDouble() / 100.0).coerceIn(0.0, 1.0)
        val complexityBoost = if (hasComplexIndicator) 0.3 else 0.0
        val complexityScore = (lengthSignal * 0.7 + complexityBoost).coerceIn(0.0, 1.0)

        return QueryRoutingDecision(
            modelName = modelName,
            complexityScore = complexityScore,
            tier = tier,
            strategy = "policy",
            fallbackChain = fallbackChain,
        )
    }

    /**
     * Get the next fallback model when [failedModel] is unavailable.
     *
     * Returns `null` when no further fallbacks exist.
     */
    fun getFallback(failedModel: String): String? {
        val chain = buildFallbackChain(failedModel)
        return chain.firstOrNull()
    }

    // =========================================================================
    // Deterministic Interception
    // =========================================================================

    private fun tryDeterministic(text: String): DeterministicResult? {
        val stripped = text.trim().trimEnd('?', '.')
        if (!ARITHMETIC_PATTERN.matches(stripped)) return null

        return try {
            val expr = stripped.replace("^", "**")
            val result = evaluateArithmetic(expr)
            if (result != null) {
                val formatted = if (result == result.toLong().toDouble()) {
                    result.toLong().toString()
                } else {
                    result.toString()
                }
                DeterministicResult(
                    answer = formatted,
                    method = "arithmetic",
                    confidence = 1.0,
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Evaluate a simple arithmetic expression.
     *
     * Handles +, -, *, /, parentheses, and ** (power).
     * Uses a basic recursive descent parser — no eval() or scripting engines.
     */
    private fun evaluateArithmetic(expr: String): Double? {
        val tokens = tokenize(expr) ?: return null
        val parser = ArithmeticParser(tokens)
        return try {
            val result = parser.parseExpression()
            if (parser.hasMore()) null else result
        } catch (_: Exception) {
            null
        }
    }

    private fun tokenize(expr: String): List<String>? {
        val tokens = mutableListOf<String>()
        var i = 0
        val s = expr.replace(" ", "")
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    tokens.add(s.substring(start, i))
                }
                c == '+' || c == '-' || c == '/' || c == '(' || c == ')' -> {
                    tokens.add(c.toString())
                    i++
                }
                c == '*' -> {
                    if (i + 1 < s.length && s[i + 1] == '*') {
                        tokens.add("**")
                        i += 2
                    } else {
                        tokens.add("*")
                        i++
                    }
                }
                else -> return null // unknown character
            }
        }
        return tokens
    }

    private class ArithmeticParser(private val tokens: List<String>) {
        private var pos = 0

        fun hasMore(): Boolean = pos < tokens.size

        fun parseExpression(): Double {
            var result = parseTerm()
            while (pos < tokens.size && (tokens[pos] == "+" || tokens[pos] == "-")) {
                val op = tokens[pos++]
                val right = parseTerm()
                result = if (op == "+") result + right else result - right
            }
            return result
        }

        private fun parseTerm(): Double {
            var result = parsePower()
            while (pos < tokens.size && (tokens[pos] == "*" || tokens[pos] == "/")) {
                val op = tokens[pos++]
                val right = parsePower()
                result = if (op == "*") result * right else result / right
            }
            return result
        }

        private fun parsePower(): Double {
            var result = parseUnary()
            while (pos < tokens.size && tokens[pos] == "**") {
                pos++
                val right = parseUnary()
                result = Math.pow(result, right)
            }
            return result
        }

        private fun parseUnary(): Double {
            if (pos < tokens.size && tokens[pos] == "-") {
                pos++
                return -parseAtom()
            }
            if (pos < tokens.size && tokens[pos] == "+") {
                pos++
            }
            return parseAtom()
        }

        private fun parseAtom(): Double {
            if (pos < tokens.size && tokens[pos] == "(") {
                pos++ // consume '('
                val result = parseExpression()
                if (pos < tokens.size && tokens[pos] == ")") pos++ // consume ')'
                return result
            }
            if (pos >= tokens.size) throw IllegalStateException("Unexpected end of expression")
            val token = tokens[pos++]
            return token.toDoubleOrNull()
                ?: throw IllegalStateException("Expected number, got: $token")
        }
    }

    // =========================================================================
    // Model Resolution
    // =========================================================================

    private fun resolveModel(targetTier: String): String {
        // Direct tier match (prefer loaded models).
        models.values
            .filter { it.tier == targetTier && it.loaded }
            .maxByOrNull { it.paramB }
            ?.let { return it.name }

        // Fallback upward through tiers.
        val targetIdx = TIER_ORDER.indexOf(targetTier).coerceAtLeast(0)
        for (tier in TIER_ORDER.drop(targetIdx)) {
            models.values
                .filter { it.tier == tier && it.loaded }
                .maxByOrNull { it.paramB }
                ?.let { return it.name }
        }

        // Fallback downward.
        for (tier in TIER_ORDER.take(targetIdx).reversed()) {
            models.values
                .filter { it.tier == tier && it.loaded }
                .maxByOrNull { it.paramB }
                ?.let { return it.name }
        }

        // Last resort: any model.
        return models.values.firstOrNull()?.name ?: ""
    }

    private fun buildFallbackChain(primary: String): List<String> {
        val primaryInfo = models[primary] ?: return emptyList()
        val primaryIdx = TIER_ORDER.indexOf(primaryInfo.tier).coerceAtLeast(0)

        val chain = mutableListOf<String>()

        // Higher tiers first.
        for (tier in TIER_ORDER.drop(primaryIdx + 1)) {
            models.values
                .filter { it.tier == tier && it.loaded && it.name != primary }
                .maxByOrNull { it.paramB }
                ?.let { chain.add(it.name) }
        }

        // Then lower tiers.
        for (tier in TIER_ORDER.take(primaryIdx).reversed()) {
            models.values
                .filter { it.tier == tier && it.loaded && it.name != primary }
                .maxByOrNull { it.paramB }
                ?.let { chain.add(it.name) }
        }

        return chain
    }

}
