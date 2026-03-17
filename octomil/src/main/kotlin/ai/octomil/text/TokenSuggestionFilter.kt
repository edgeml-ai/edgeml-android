package ai.octomil.text

/**
 * Filters raw token predictions from an LLM into clean word suggestions.
 *
 * Handles BPE artifacts (leading Ġ / space), deduplication, and non-word
 * filtering. Used internally by [OctomilTextPredictions].
 */
internal object TokenSuggestionFilter {

    /**
     * Filter raw token predictions into clean text suggestions.
     *
     * @return Filtered suggestions as plain strings (no scores).
     */
    fun process(rawTokens: List<Pair<String, Float>>): List<String> =
        processWithScore(rawTokens).map { it.first }

    /**
     * Filter raw token predictions, preserving scores.
     *
     * Strips BPE leading space markers (Ġ), filters non-words,
     * removes duplicates, and returns (text, score) pairs.
     */
    fun processWithScore(rawTokens: List<Pair<String, Float>>): List<Pair<String, Float>> {
        val seen = mutableSetOf<String>()
        return rawTokens
            .map { (text, score) -> text.trimStart('\u0120', ' ') to score }
            .filter { (text, _) -> text.length > 1 }
            .filter { (text, _) -> text.first().isLetter() }
            .filter { (text, _) -> seen.add(text) }
    }
}
