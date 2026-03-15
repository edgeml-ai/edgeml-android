package ai.octomil.chat

import ai.octomil.responses.ContentPart

/**
 * Validates content parts and derives text content.
 * Implements contract rules for source mutual exclusion and content derivation.
 */
object ContentPartValidation {

    /**
     * Validate that each media part has exactly one source (assetId, url, or data).
     * Text parts are always valid. File parts are excluded (API layer only).
     *
     * @throws IllegalArgumentException if any part has zero or multiple sources
     */
    fun validate(parts: List<ContentPart>) {
        parts.forEachIndexed { index, part ->
            when (part) {
                is ContentPart.Text -> {} // always valid
                is ContentPart.Image -> validateSources(
                    index, "Image", part.assetId, part.url, part.data
                )
                is ContentPart.Audio -> validateSources(
                    index, "Audio", part.assetId, part.url, part.data
                )
                is ContentPart.Video -> validateSources(
                    index, "Video", part.assetId, part.url, part.data
                )
                is ContentPart.File -> {} // API layer only, not validated here
            }
        }
    }

    /**
     * Derive content text from parts.
     * Concatenates all [ContentPart.Text] parts in order, separated by newline.
     * Returns null if no text parts exist.
     */
    fun deriveContent(parts: List<ContentPart>): String? {
        val texts = parts.filterIsInstance<ContentPart.Text>().map { it.text }
        return if (texts.isEmpty()) null else texts.joinToString("\n")
    }

    private fun validateSources(
        index: Int,
        typeName: String,
        assetId: String?,
        url: String?,
        data: String?,
    ) {
        val sourceCount = listOfNotNull(assetId, url, data).size
        require(sourceCount == 1) {
            "$typeName part at index $index must have exactly one source " +
                "(assetId, url, or data), found $sourceCount"
        }
    }
}
