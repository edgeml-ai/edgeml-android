package ai.octomil.responses

/**
 * Typed content part for multimodal messages.
 * Mirrors contract content_part.json schema.
 *
 * Each media part requires exactly one source: assetId, url, or data.
 * - assetId: Asset service reference (preferred for persisted messages)
 * - url: Externally-hosted content
 * - data: Base64 offline fallback (~10MB max, do not use for persisted chat history)
 *
 * [File] is retained for the responses/API layer (InputItem file attachments).
 * It is NOT used in ThreadMessage.contentParts or ChatTurnRequest.inputParts.
 */
sealed interface ContentPart {
    data class Text(val text: String) : ContentPart

    data class Image(
        val assetId: String? = null,
        val url: String? = null,
        val data: String? = null,
        val mediaType: String? = null,
        val detail: String = "auto",
    ) : ContentPart

    data class Audio(
        val assetId: String? = null,
        val url: String? = null,
        val data: String? = null,
        val mediaType: String? = null,
    ) : ContentPart

    data class File(
        val data: String,
        val mediaType: String,
        val filename: String? = null,
    ) : ContentPart

    data class Video(
        val assetId: String? = null,
        val url: String? = null,
        val data: String? = null,
        val mediaType: String? = null,
        val maxFrames: Int? = null,
    ) : ContentPart
}
