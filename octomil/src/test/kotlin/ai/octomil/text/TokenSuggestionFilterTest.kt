package ai.octomil.text

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenSuggestionFilterTest {

    // =========================================================================
    // processWithScore
    // =========================================================================

    @Test
    fun `processWithScore strips BPE leading space marker`() {
        val raw = listOf("\u0120hello" to 0.9f)
        val result = TokenSuggestionFilter.processWithScore(raw)
        assertEquals(1, result.size)
        assertEquals("hello", result[0].first)
        assertEquals(0.9f, result[0].second)
    }

    @Test
    fun `processWithScore strips leading space`() {
        val raw = listOf(" world" to 0.8f)
        val result = TokenSuggestionFilter.processWithScore(raw)
        assertEquals(1, result.size)
        assertEquals("world", result[0].first)
    }

    @Test
    fun `processWithScore strips both space and BPE marker`() {
        val raw = listOf(" \u0120token" to 0.7f)
        val result = TokenSuggestionFilter.processWithScore(raw)
        assertEquals("token", result[0].first)
    }

    @Test
    fun `processWithScore filters single-char tokens`() {
        val raw = listOf(
            "\u0120a" to 0.9f,
            "\u0120be" to 0.8f,
        )
        val result = TokenSuggestionFilter.processWithScore(raw)
        assertEquals(1, result.size)
        assertEquals("be", result[0].first)
    }

    @Test
    fun `processWithScore filters tokens starting with non-letter`() {
        val raw = listOf(
            "123abc" to 0.9f,
            ".test" to 0.8f,
            "hello" to 0.7f,
        )
        val result = TokenSuggestionFilter.processWithScore(raw)
        assertEquals(1, result.size)
        assertEquals("hello", result[0].first)
    }

    @Test
    fun `processWithScore deduplicates keeping first occurrence`() {
        val raw = listOf(
            "\u0120hello" to 0.9f,
            "hello" to 0.5f,
            "\u0120world" to 0.8f,
        )
        val result = TokenSuggestionFilter.processWithScore(raw)
        assertEquals(2, result.size)
        assertEquals("hello", result[0].first)
        assertEquals(0.9f, result[0].second) // first occurrence score preserved
        assertEquals("world", result[1].first)
    }

    @Test
    fun `processWithScore preserves order and scores`() {
        val raw = listOf(
            "\u0120apple" to 0.95f,
            "\u0120banana" to 0.80f,
            "\u0120cherry" to 0.65f,
        )
        val result = TokenSuggestionFilter.processWithScore(raw)
        assertEquals(3, result.size)
        assertEquals("apple" to 0.95f, result[0])
        assertEquals("banana" to 0.80f, result[1])
        assertEquals("cherry" to 0.65f, result[2])
    }

    @Test
    fun `processWithScore returns empty for empty input`() {
        val result = TokenSuggestionFilter.processWithScore(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `processWithScore returns empty when all tokens are filtered`() {
        val raw = listOf(
            "1" to 0.9f,     // single char AND starts with non-letter
            ".x" to 0.8f,    // starts with non-letter
            "\u0120a" to 0.7f, // single char after strip
        )
        val result = TokenSuggestionFilter.processWithScore(raw)
        assertTrue(result.isEmpty())
    }

    // =========================================================================
    // process (strings only)
    // =========================================================================

    @Test
    fun `process returns strings without scores`() {
        val raw = listOf(
            "\u0120hello" to 0.9f,
            "\u0120world" to 0.8f,
        )
        val result = TokenSuggestionFilter.process(raw)
        assertEquals(listOf("hello", "world"), result)
    }

    @Test
    fun `process applies same filtering as processWithScore`() {
        val raw = listOf(
            "\u0120a" to 0.9f,     // filtered: single char
            "123" to 0.8f,         // filtered: starts with digit
            "\u0120hello" to 0.7f, // kept
            "hello" to 0.6f,       // filtered: duplicate
            "\u0120world" to 0.5f, // kept
        )
        val result = TokenSuggestionFilter.process(raw)
        assertEquals(listOf("hello", "world"), result)
    }
}
