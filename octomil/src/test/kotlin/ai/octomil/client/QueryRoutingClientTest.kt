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
    // Query routing — tier assignment (cross-SDK scoring algorithm)
    // =========================================================================

    @Test
    fun `short query with no indicators routes to fast tier`() {
        // "Hi there" = 2 words (<= fastMaxWords=10), no indicators.
        // wordScore=0.0, indicatorScore=0.0 -> score=0.0 < 0.3 -> fast
        val messages = listOf(mapOf("role" to "user", "content" to "Hi there"))
        val decision = router.route(messages)
        assertEquals("fast", decision.tier)
        assertEquals("smollm-360m", decision.modelName)
        assertEquals("policy", decision.strategy)
    }

    @Test
    fun `medium-length query routes to balanced tier`() {
        // 37 words, no complex indicators. wordScore=(37-10)/(50-10)=27/40=0.675.
        // score=0.675*0.5=0.3375. 0.3 <= 0.3375 < 0.7 -> balanced
        val content = "Tell me about the history of computing and how it evolved " +
            "over the last few decades with various technological breakthroughs " +
            "and innovations that changed the way people interact with machines " +
            "on a daily basis throughout modern civilization"
        val messages = listOf(mapOf("role" to "user", "content" to content))
        val decision = router.route(messages)
        assertEquals("balanced", decision.tier)
        assertEquals("phi-4-mini", decision.modelName)
    }

    @Test
    fun `long query with indicators routes to quality tier`() {
        // 55+ words with "explain", "analyze", and "compare" -> 3 indicator matches.
        // wordScore=1.0 (55>=50), indicatorScore=3/3=1.0.
        // score=1.0*0.5+1.0*0.5=1.0 >= 0.7 -> quality
        val content = "Please explain the differences between various sorting algorithms " +
            "and analyze their time and space complexity across different input sizes " +
            "then compare their performance characteristics when applied to real world " +
            "datasets that contain partially sorted data with duplicate values and " +
            "varying distribution patterns across multiple dimensions of the problem " +
            "space including worst case and average case scenarios"
        val messages = listOf(mapOf("role" to "user", "content" to content))
        val decision = router.route(messages)
        assertEquals("quality", decision.tier)
        assertEquals("llama-3.2-3b", decision.modelName)
    }

    @Test
    fun `indicator on short query increases score but does not force quality`() {
        // "explain this" = 2 words (<= fastMaxWords=10). wordScore=0.0.
        // matchCount=1 ("explain"), indicatorScore=1/3=0.333.
        // score=0+0.333*0.5=0.167 < 0.3 -> fast (not quality, unlike old behavior)
        val messages = listOf(mapOf("role" to "user", "content" to "explain this"))
        val decision = router.route(messages)
        assertEquals("fast", decision.tier)
        // But score is non-zero due to indicator
        assertTrue(decision.complexityScore > 0.0)
    }

    @Test
    fun `multiple indicators on short query route to balanced`() {
        // "implement the algorithm step by step" = 6 words (<= fastMaxWords=10).
        // matchCount=2 ("implement", "step by step"), indicatorScore=2/3=0.667.
        // score=0+0.667*0.5=0.333. 0.3 <= 0.333 < 0.7 -> balanced
        val messages = listOf(
            mapOf("role" to "user", "content" to "implement the algorithm step by step")
        )
        val decision = router.route(messages)
        assertEquals("balanced", decision.tier)
    }

    @Test
    fun `saturated indicators on medium query route to quality`() {
        // A query with 3+ indicator matches and enough words to push score >= 0.7.
        // ~30 words with "explain", "analyze", and "compare".
        // wordScore=(30-10)/40=0.5, indicatorScore=3/3=1.0.
        // score=0.5*0.5+1.0*0.5=0.25+0.5=0.75 >= 0.7 -> quality
        val content = "Can you explain the core differences between these approaches " +
            "then analyze the performance implications and compare them across " +
            "multiple scenarios with varying input sizes and constraints please"
        val messages = listOf(mapOf("role" to "user", "content" to content))
        val decision = router.route(messages)
        assertEquals("quality", decision.tier)
    }

    @Test
    fun `empty message routes to fast tier`() {
        val messages = listOf(mapOf("role" to "user", "content" to ""))
        val decision = router.route(messages)
        assertEquals("fast", decision.tier)
        assertEquals(0.0, decision.complexityScore)
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
        assertEquals("fast", decision.tier) // "thanks" is 1 word, no indicator
    }

    @Test
    fun `word count at exact threshold boundaries`() {
        // Exactly fastMaxWords (10 words) -> wordScore=0.0
        val atFast = (1..10).joinToString(" ") { "w$it" }
        val decFast = router.route(listOf(mapOf("role" to "user", "content" to atFast)))
        assertEquals(0.0, decFast.complexityScore) // no indicators, wordScore=0

        // Exactly qualityMinWords (50 words) -> wordScore=1.0
        val atQuality = (1..50).joinToString(" ") { "w$it" }
        val decQuality = router.route(listOf(mapOf("role" to "user", "content" to atQuality)))
        // wordScore=1.0, indicatorScore=0, score=0.5. balanced (0.3 <= 0.5 < 0.7)
        assertEquals("balanced", decQuality.tier)
    }

    // =========================================================================
    // Cross-SDK scoring formula — explicit score verification
    // =========================================================================

    @Test
    fun `score computation matches cross-SDK formula`() {
        // Default policy: fastMaxWords=10, qualityMinWords=50, indicators=7 common ones.
        // Default weights: lengthWeight=0.5, indicatorWeight=0.5, indicatorNormalizor=3.0,
        //                  fastThreshold=0.3, qualityThreshold=0.7.

        // 30 words with 1 indicator ("explain"). wordScore=(30-10)/40=0.5.
        // indicatorScore=1/3=0.333. score=0.5*0.5+0.333*0.5=0.25+0.167=0.417.
        val words = (1..29).joinToString(" ") { "w$it" }
        val content = "explain $words"
        val decision = router.route(listOf(mapOf("role" to "user", "content" to content)))

        val expectedWordScore = 20.0 / 40.0 // (30-10)/(50-10)
        val expectedIndicatorScore = 1.0 / 3.0
        val expectedScore = expectedWordScore * 0.5 + expectedIndicatorScore * 0.5
        assertEquals(expectedScore, decision.complexityScore, 0.001)
        assertEquals("balanced", decision.tier)
    }

    @Test
    fun `score is clamped to 0 and 1`() {
        // Empty query: all zeros.
        val emptyDec = router.route(listOf(mapOf("role" to "user", "content" to "")))
        assertEquals(0.0, emptyDec.complexityScore)

        // Very long query with max indicators.
        val longContent = "explain analyze compare " + (1..100).joinToString(" ") { "word$it" }
        val longDec = router.route(listOf(mapOf("role" to "user", "content" to longContent)))
        assertTrue(longDec.complexityScore <= 1.0)
        assertTrue(longDec.complexityScore >= 0.0)
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
        // Need a query that routes to quality (score >= 0.7).
        // ~30 words with 3 indicators: wordScore=(30-10)/40=0.5,
        // indicatorScore=3/3=1.0, score=0.5*0.5+1.0*0.5=0.75 -> quality.
        val content = "Can you explain the core differences between these approaches " +
            "then analyze the performance implications and compare them across " +
            "multiple scenarios with varying input sizes and constraints please"
        val messages = listOf(mapOf("role" to "user", "content" to content))
        val decision = router.route(messages)
        assertEquals("llama-3.2-3b", decision.modelName)
        assertEquals(listOf("phi-4-mini", "smollm-360m"), decision.fallbackChain)
    }

    @Test
    fun `fallback chain for balanced model includes both directions`() {
        // Need a query that routes to balanced (0.3 <= score < 0.7).
        // 37 words, no indicators. wordScore=(37-10)/40=0.675, score=0.3375.
        val content = "Tell me about the history of computing and how it evolved " +
            "over the last few decades with various technological breakthroughs " +
            "and innovations that changed the way people interact with machines " +
            "on a daily basis throughout modern civilization"
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

    @Test
    fun `more words increase complexity score`() {
        // 5 words (below threshold) vs 30 words (above threshold), no indicators.
        val short5 = router.route(listOf(mapOf("role" to "user", "content" to "one two three four five")))
        val words30 = (1..30).joinToString(" ") { "w$it" }
        val long30 = router.route(listOf(mapOf("role" to "user", "content" to words30)))
        assertTrue(
            long30.complexityScore > short5.complexityScore,
            "30-word query (${long30.complexityScore}) should score higher than 5-word (${short5.complexityScore})"
        )
    }

    @Test
    fun `more indicators increase complexity score`() {
        // Same word count but different number of indicator matches.
        // 15 words each, one with 1 indicator, one with 2 indicators.
        val oneIndicator = "explain the general purpose of this particular system and its features and outputs"
        val twoIndicators = "explain and analyze the general purpose of this particular system and its features"
        val dec1 = router.route(listOf(mapOf("role" to "user", "content" to oneIndicator)))
        val dec2 = router.route(listOf(mapOf("role" to "user", "content" to twoIndicators)))
        assertTrue(
            dec2.complexityScore > dec1.complexityScore,
            "Two indicators (${dec2.complexityScore}) should score higher than one (${dec1.complexityScore})"
        )
    }

    // =========================================================================
    // ScoringWeights — server-driven scoring
    // =========================================================================

    @Test
    fun `ScoringWeights defaults are correct`() {
        val weights = RoutingPolicy.ScoringWeights()
        assertEquals(0.5, weights.lengthWeight)
        assertEquals(0.5, weights.indicatorWeight)
        assertEquals(3.0, weights.indicatorNormalizor)
        assertEquals(0.3, weights.fastThreshold)
        assertEquals(0.7, weights.qualityThreshold)
        // Deprecated fields still have defaults for backward compatibility.
        assertEquals(0.0, weights.complexityBoost)
        assertEquals(100, weights.lengthNormalizor)
    }

    @Test
    fun `ScoringWeights serializes new fields to JSON`() {
        val weights = RoutingPolicy.ScoringWeights(
            lengthWeight = 0.6,
            indicatorWeight = 0.4,
            indicatorNormalizor = 5.0,
            fastThreshold = 0.25,
            qualityThreshold = 0.75,
        )
        val encoded = json.encodeToString(RoutingPolicy.ScoringWeights.serializer(), weights)
        assertTrue(encoded.contains("\"length_weight\":0.6"))
        assertTrue(encoded.contains("\"indicator_weight\":0.4"))
        assertTrue(encoded.contains("\"indicator_normalizor\":5.0"))
        assertTrue(encoded.contains("\"fast_threshold\":0.25"))
        assertTrue(encoded.contains("\"quality_threshold\":0.75"))
    }

    @Test
    fun `ScoringWeights deserializes old server format with defaults for new fields`() {
        // Old server response only has length_weight, complexity_boost, length_normalizor.
        val raw = """{"length_weight":0.8,"complexity_boost":0.25,"length_normalizor":120}"""
        val weights = json.decodeFromString(RoutingPolicy.ScoringWeights.serializer(), raw)
        assertEquals(0.8, weights.lengthWeight)
        assertEquals(0.25, weights.complexityBoost)
        assertEquals(120, weights.lengthNormalizor)
        // New fields use defaults.
        assertEquals(0.5, weights.indicatorWeight)
        assertEquals(3.0, weights.indicatorNormalizor)
        assertEquals(0.3, weights.fastThreshold)
        assertEquals(0.7, weights.qualityThreshold)
    }

    @Test
    fun `ScoringWeights deserializes new server format correctly`() {
        val raw = """{
            "length_weight": 0.6,
            "indicator_weight": 0.4,
            "indicator_normalizor": 5.0,
            "fast_threshold": 0.2,
            "quality_threshold": 0.8,
            "complexity_boost": 0.0,
            "length_normalizor": 100
        }""".trimIndent()
        val weights = json.decodeFromString(RoutingPolicy.ScoringWeights.serializer(), raw)
        assertEquals(0.6, weights.lengthWeight)
        assertEquals(0.4, weights.indicatorWeight)
        assertEquals(5.0, weights.indicatorNormalizor)
        assertEquals(0.2, weights.fastThreshold)
        assertEquals(0.8, weights.qualityThreshold)
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
        assertEquals(0.5, policy.scoringWeights.indicatorWeight)
        assertEquals(3.0, policy.scoringWeights.indicatorNormalizor)
        assertEquals(0.3, policy.scoringWeights.fastThreshold)
        assertEquals(0.7, policy.scoringWeights.qualityThreshold)
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
                lengthWeight = 0.6,
                indicatorWeight = 0.4,
                indicatorNormalizor = 5.0,
                fastThreshold = 0.25,
                qualityThreshold = 0.75,
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

    @Test
    fun `no user message routes to fast with zero score`() {
        val messages = listOf(mapOf("role" to "system", "content" to "You are helpful"))
        val decision = router.route(messages)
        assertEquals("fast", decision.tier)
        assertEquals(0.0, decision.complexityScore)
    }

    @Test
    fun `score is deterministic for same input`() {
        val messages = listOf(mapOf("role" to "user", "content" to "explain sorting algorithms"))
        val dec1 = router.route(messages)
        val dec2 = router.route(messages)
        assertEquals(dec1.complexityScore, dec2.complexityScore)
        assertEquals(dec1.tier, dec2.tier)
        assertEquals(dec1.modelName, dec2.modelName)
    }
}
