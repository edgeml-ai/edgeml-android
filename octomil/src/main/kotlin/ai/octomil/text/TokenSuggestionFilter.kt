package ai.octomil.text

/**
 * Converts raw subword token predictions from an LLM into usable word suggestions.
 *
 * LLMs predict subword tokens (e.g. "Ġhello", "##ing") — this filter strips
 * leading-space markers, drops punctuation-only tokens, and deduplicates.
 */
internal object TokenSuggestionFilter {

    fun process(rawTokens: List<PredictionCandidate>): List<String> {
        return rawTokens
            .map { it.text.trimStart('\u0120', ' ') }  // strip Ġ and leading spaces
            .filter { it.length > 1 }
            .filter { it.first().isLetter() }
            .distinct()
            .take(3)
    }
}
