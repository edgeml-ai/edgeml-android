package ai.octomil.client

import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class QueryRoutingClientTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var models: Map<String, QueryModelInfo>
    private lateinit var router: QueryRouter

    @Before
    fun setUp() {
        models = mapOf(
            "smollm-360m" to QueryModelInfo(
                name = "smollm-360m", tier = "fast", paramB = 0.36,
            ),
            "phi-4-mini" to QueryModelInfo(
                name = "phi-4-mini", tier = "balanced", paramB = 3.8,
            ),
            "llama-3.2-3b" to QueryModelInfo(
                name = "llama-3.2-3b", tier = "quality", paramB = 3.0,
            ),
        )
        router = QueryRouter(models)
    }

    // =========================================================================
    // Policy serialization
    // =========================================================================

    @Test
    fun `RoutingPolicy serializes to JSON correctly`() {
        val policy = RoutingPolicy(
            version = 1,
            thresholds = RoutingPolicy.PolicyThresholds(
                fastMaxWords = 10,
                qualityMinWords = 50,
            ),
            complexIndicators = listOf("code", "explain"),
            deterministicEnabled = true,
            ttlSeconds = 300,
            fetchedAt = 1000.0,
            etag = "\"abc123\"",
        )
        val encoded = json.encodeToString(RoutingPolicy.serializer(), policy)
        assertTrue(encoded.contains("\"fast_max_words\":10"))
        assertTrue(encoded.contains("\"quality_min_words\":50"))
        assertTrue(encoded.contains("\"complex_indicators\""))
        assertTrue(encoded.contains("\"deterministic_enabled\":true"))
        assertTrue(encoded.contains("\"ttl_seconds\":300"))
    }

    @Test
    fun `RoutingPolicy deserializes from JSON correctly`() {
        val raw = """
            {
                "version": 2,
                "thresholds": {
                    "fast_max_words": 15,
                    "quality_min_words": 60
                },
                "complex_indicators": ["code", "explain", "analyze"],
                "deterministic_enabled": false,
                "ttl_seconds": 600,
                "fetched_at": 1709000000.0,
                "etag": "\"xyz789\""
            }
        """.trimIndent()
        val policy = json.decodeFromString(RoutingPolicy.serializer(), raw)
        assertEquals(2, policy.version)
        assertEquals(15, policy.thresholds.fastMaxWords)
        assertEquals(60, policy.thresholds.qualityMinWords)
        assertEquals(3, policy.complexIndicators.size)
        assertFalse(policy.deterministicEnabled)
        assertEquals(600, policy.ttlSeconds)
        assertEquals(1709000000.0, policy.fetchedAt)
        assertEquals("\"xyz789\"", policy.etag)
    }

    @Test
    fun `RoutingPolicy round-trips through serialization`() {
        val original = RoutingPolicy(
            version = 1,
            thresholds = RoutingPolicy.PolicyThresholds(fastMaxWords = 10, qualityMinWords = 50),
            complexIndicators = listOf("code", "explain", "compare"),
            deterministicEnabled = true,
            ttlSeconds = 300,
            fetchedAt = 1234567890.0,
            etag = "\"etag-val\"",
        )
        val encoded = json.encodeToString(RoutingPolicy.serializer(), original)
        val decoded = json.decodeFromString(RoutingPolicy.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // =========================================================================
    // Policy expiry
    // =========================================================================

    @Test
    fun `policy with fetchedAt zero is expired`() {
        val policy = RoutingPolicy(
            version = 1,
            thresholds = RoutingPolicy.PolicyThresholds(fastMaxWords = 10, qualityMinWords = 50),
            complexIndicators = emptyList(),
            deterministicEnabled = true,
            ttlSeconds = 300,
            fetchedAt = 0.0,
        )
        assertTrue(policy.isExpired)
    }

    @Test
    fun `recently fetched policy is not expired`() {
        val policy = RoutingPolicy(
            version = 1,
            thresholds = RoutingPolicy.PolicyThresholds(fastMaxWords = 10, qualityMinWords = 50),
            complexIndicators = emptyList(),
            deterministicEnabled = true,
            ttlSeconds = 300,
            fetchedAt = System.currentTimeMillis() / 1000.0,
        )
        assertFalse(policy.isExpired)
    }

    @Test
    fun `old policy is expired`() {
        val policy = RoutingPolicy(
            version = 1,
            thresholds = RoutingPolicy.PolicyThresholds(fastMaxWords = 10, qualityMinWords = 50),
            complexIndicators = emptyList(),
            deterministicEnabled = true,
            ttlSeconds = 300,
            fetchedAt = System.currentTimeMillis() / 1000.0 - 400.0, // 400s ago, TTL is 300s
        )
        assertTrue(policy.isExpired)
    }

    // =========================================================================
    // Query routing — tier assignment
    // =========================================================================

    @Test
    fun `short query routes to fast tier`() {
        val messages = listOf(mapOf("role" to "user", "content" to "Hi there"))
        val decision = router.route(messages)
        assertEquals("fast", decision.tier)
        assertEquals("smollm-360m", decision.modelName)
        assertEquals("policy", decision.strategy)
    }

    @Test
    fun `medium query routes to balanced tier`() {
        val content = "Tell me about the history of computing and how it evolved " +
            "over the last few decades with various technological breakthroughs"
        val messages = listOf(mapOf("role" to "user", "content" to content))
        val decision = router.route(messages)
        assertEquals("balanced", decision.tier)
        assertEquals("phi-4-mini", decision.modelName)
    }

    @Test
    fun `long query routes to quality tier`() {
        val words = (1..60).joinToString(" ") { "word$it" }
        val messages = listOf(mapOf("role" to "user", "content" to words))
        val decision = router.route(messages)
        assertEquals("quality", decision.tier)
        assertEquals("llama-3.2-3b", decision.modelName)
    }

    @Test
    fun `complex keyword forces quality tier regardless of length`() {
        val messages = listOf(mapOf("role" to "user", "content" to "explain this"))
        val decision = router.route(messages)
        assertEquals("quality", decision.tier)
        assertEquals("llama-3.2-3b", decision.modelName)
    }

    @Test
    fun `multiple complex indicators route to quality`() {
        val messages = listOf(
            mapOf("role" to "user", "content" to "implement the algorithm step by step")
        )
        val decision = router.route(messages)
        assertEquals("quality", decision.tier)
    }

    @Test
    fun `empty message routes to fast tier`() {
        val messages = listOf(mapOf("role" to "user", "content" to ""))
        val decision = router.route(messages)
        assertEquals("fast", decision.tier)
    }

    @Test
    fun `uses last user message for routing`() {
        val messages = listOf(
            mapOf("role" to "system", "content" to "You are a helpful assistant"),
            mapOf("role" to "user", "content" to "explain quantum computing in detail"),
            mapOf("role" to "assistant", "content" to "Sure..."),
            mapOf("role" to "user", "content" to "thanks"),
        )
        val decision = router.route(messages)
        assertEquals("fast", decision.tier) // "thanks" is short, no complex indicator
    }

    // =========================================================================
    // Deterministic interception
    // =========================================================================

    @Test
    fun `pure arithmetic is intercepted deterministically`() {
        val messages = listOf(mapOf("role" to "user", "content" to "2 + 2"))
        val decision = router.route(messages)
        assertEquals("deterministic", decision.tier)
        assertNotNull(decision.deterministicResult)
        assertEquals("4", decision.deterministicResult!!.answer)
        assertEquals("arithmetic", decision.deterministicResult!!.method)
        assertEquals(1.0, decision.deterministicResult!!.confidence)
    }

    @Test
    fun `multiplication is intercepted`() {
        val messages = listOf(mapOf("role" to "user", "content" to "15 * 3"))
        val decision = router.route(messages)
        assertEquals("deterministic", decision.tier)
        assertEquals("45", decision.deterministicResult?.answer)
    }

    @Test
    fun `division is intercepted`() {
        val messages = listOf(mapOf("role" to "user", "content" to "100 / 4"))
        val decision = router.route(messages)
        assertEquals("deterministic", decision.tier)
        assertEquals("25", decision.deterministicResult?.answer)
    }

    @Test
    fun `parenthesized expression is intercepted`() {
        val messages = listOf(mapOf("role" to "user", "content" to "(3 + 4) * 2"))
        val decision = router.route(messages)
        assertEquals("deterministic", decision.tier)
        assertEquals("14", decision.deterministicResult?.answer)
    }

    @Test
    fun `exponent expression is intercepted`() {
        val messages = listOf(mapOf("role" to "user", "content" to "2^10"))
        val decision = router.route(messages)
        assertEquals("deterministic", decision.tier)
        assertEquals("1024", decision.deterministicResult?.answer)
    }

    @Test
    fun `non-arithmetic text is not intercepted`() {
        val messages = listOf(mapOf("role" to "user", "content" to "Hello world"))
        val decision = router.route(messages)
        assertNull(decision.deterministicResult)
    }

    @Test
    fun `deterministic disabled skips interception`() {
        val routerNoDet = QueryRouter(models, enableDeterministic = false)
        val messages = listOf(mapOf("role" to "user", "content" to "2 + 2"))
        val decision = routerNoDet.route(messages)
        assertNull(decision.deterministicResult)
        assertEquals("fast", decision.tier)
    }

    // =========================================================================
    // Model fallback chain
    // =========================================================================

    @Test
    fun `fallback chain for fast model goes balanced then quality`() {
        val messages = listOf(mapOf("role" to "user", "content" to "hi"))
        val decision = router.route(messages)
        assertEquals("smollm-360m", decision.modelName)
        assertEquals(listOf("phi-4-mini", "llama-3.2-3b"), decision.fallbackChain)
    }

    @Test
    fun `fallback chain for quality model goes down`() {
        val messages = listOf(
            mapOf("role" to "user", "content" to "explain the algorithm in detail")
        )
        val decision = router.route(messages)
        assertEquals("llama-3.2-3b", decision.modelName)
        assertEquals(listOf("phi-4-mini", "smollm-360m"), decision.fallbackChain)
    }

    @Test
    fun `fallback chain for balanced model includes both directions`() {
        val content = "Tell me about the history of computing and how it evolved " +
            "over the last few decades with various breakthroughs"
        val messages = listOf(mapOf("role" to "user", "content" to content))
        val decision = router.route(messages)
        assertEquals("phi-4-mini", decision.modelName)
        assertEquals(listOf("llama-3.2-3b", "smollm-360m"), decision.fallbackChain)
    }

    @Test
    fun `getFallback returns next model in chain`() {
        val fallback = router.getFallback("smollm-360m")
        assertNotNull(fallback)
        assertEquals("phi-4-mini", fallback)
    }

    @Test
    fun `getFallback for unknown model returns null`() {
        val fallback = router.getFallback("unknown-model")
        assertNull(fallback)
    }

    // =========================================================================
    // Complexity score
    // =========================================================================

    @Test
    fun `complexity score is between 0 and 1`() {
        val messages = listOf(mapOf("role" to "user", "content" to "Hello"))
        val decision = router.route(messages)
        assertTrue(decision.complexityScore in 0.0..1.0)
    }

    @Test
    fun `complex indicator increases complexity score`() {
        val simple = router.route(listOf(mapOf("role" to "user", "content" to "hello")))
        val complex = router.route(
            listOf(mapOf("role" to "user", "content" to "explain hello"))
        )
        assertTrue(
            complex.complexityScore > simple.complexityScore,
            "Complex query (${complex.complexityScore}) should score higher than simple (${simple.complexityScore})"
        )
    }

    // =========================================================================
    // ScoringWeights — server-driven scoring
    // =========================================================================

    @Test
    fun `ScoringWeights defaults are neutral`() {
        val weights = RoutingPolicy.ScoringWeights()
        assertEquals(0.5, weights.lengthWeight)
        assertEquals(0.0, weights.complexityBoost)
        assertEquals(100, weights.lengthNormalizor)
    }

    @Test
    fun `ScoringWeights serializes to JSON correctly`() {
        val weights = RoutingPolicy.ScoringWeights(
            lengthWeight = 0.7,
            complexityBoost = 0.3,
            lengthNormalizor = 80,
        )
        val encoded = json.encodeToString(RoutingPolicy.ScoringWeights.serializer(), weights)
        assertTrue(encoded.contains("\"length_weight\":0.7"))
        assertTrue(encoded.contains("\"complexity_boost\":0.3"))
        assertTrue(encoded.contains("\"length_normalizor\":80"))
    }

    @Test
    fun `ScoringWeights deserializes from JSON correctly`() {
        val raw = """{"length_weight":0.8,"complexity_boost":0.25,"length_normalizor":120}"""
        val weights = json.decodeFromString(RoutingPolicy.ScoringWeights.serializer(), raw)
        assertEquals(0.8, weights.lengthWeight)
        assertEquals(0.25, weights.complexityBoost)
        assertEquals(120, weights.lengthNormalizor)
    }

    @Test
    fun `ScoringWeights absent in JSON uses defaults`() {
        val raw = """
            {
                "version": 1,
                "thresholds": {"fast_max_words": 10, "quality_min_words": 50},
                "complex_indicators": [],
                "deterministic_enabled": true,
                "ttl_seconds": 300
            }
        """.trimIndent()
        val policy = json.decodeFromString(RoutingPolicy.serializer(), raw)
        assertEquals(0.5, policy.scoringWeights.lengthWeight)
        assertEquals(0.0, policy.scoringWeights.complexityBoost)
        assertEquals(100, policy.scoringWeights.lengthNormalizor)
    }

    @Test
    fun `policy with scoring_weights round-trips correctly`() {
        val original = RoutingPolicy(
            version = 2,
            thresholds = RoutingPolicy.PolicyThresholds(fastMaxWords = 10, qualityMinWords = 50),
            complexIndicators = listOf("code"),
            deterministicEnabled = true,
            ttlSeconds = 300,
            scoringWeights = RoutingPolicy.ScoringWeights(
                lengthWeight = 0.7,
                complexityBoost = 0.3,
                lengthNormalizor = 80,
            ),
        )
        val encoded = json.encodeToString(RoutingPolicy.serializer(), original)
        val decoded = json.decodeFromString(RoutingPolicy.serializer(), encoded)
        assertEquals(original.scoringWeights, decoded.scoringWeights)
    }

    // =========================================================================
    // Data classes
    // =========================================================================

    @Test
    fun `QueryModelInfo fields`() {
        val info = QueryModelInfo(name = "test", tier = "fast", paramB = 0.5, loaded = true)
        assertEquals("test", info.name)
        assertEquals("fast", info.tier)
        assertEquals(0.5, info.paramB)
        assertTrue(info.loaded)
    }

    @Test
    fun `QueryRoutingDecision fields`() {
        val decision = QueryRoutingDecision(
            modelName = "test-model",
            complexityScore = 0.42,
            tier = "balanced",
            strategy = "policy",
            fallbackChain = listOf("fallback-1"),
        )
        assertEquals("test-model", decision.modelName)
        assertEquals(0.42, decision.complexityScore)
        assertEquals("balanced", decision.tier)
        assertEquals("policy", decision.strategy)
        assertEquals(1, decision.fallbackChain.size)
        assertNull(decision.deterministicResult)
    }

    @Test
    fun `DeterministicResult fields`() {
        val det = DeterministicResult(answer = "42", method = "arithmetic", confidence = 1.0)
        assertEquals("42", det.answer)
        assertEquals("arithmetic", det.method)
        assertEquals(1.0, det.confidence)
    }

    @Test
    fun `DeterministicResult default confidence`() {
        val det = DeterministicResult(answer = "42", method = "arithmetic")
        assertEquals(1.0, det.confidence)
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun `router with single model always selects it`() {
        val singleModel = mapOf(
            "only-model" to QueryModelInfo(name = "only-model", tier = "balanced", paramB = 1.0)
        )
        val singleRouter = QueryRouter(singleModel)
        val decision = singleRouter.route(listOf(mapOf("role" to "user", "content" to "hi")))
        assertEquals("only-model", decision.modelName)
        assertTrue(decision.fallbackChain.isEmpty())
    }

    @Test
    fun `router with no models returns empty model name`() {
        val emptyRouter = QueryRouter(emptyMap())
        val decision = emptyRouter.route(listOf(mapOf("role" to "user", "content" to "hi")))
        assertEquals("", decision.modelName)
    }

    @Test
    fun `trailing question mark does not break deterministic`() {
        val messages = listOf(mapOf("role" to "user", "content" to "2+2?"))
        val decision = router.route(messages)
        assertEquals("deterministic", decision.tier)
        assertEquals("4", decision.deterministicResult?.answer)
    }
}
