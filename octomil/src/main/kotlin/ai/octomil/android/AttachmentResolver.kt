package ai.octomil.android

import android.content.Context
import android.util.Base64
import ai.octomil.responses.ContentPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Converts platform-local [LocalAttachment] to portable [ContentPart.Image].
 *
 * Current implementation: reads contentUri -> base64 -> Image(data=..., mediaType=...).
 * Future: upload to asset service -> Image(assetId=...).
 */
class AttachmentResolver(private val context: Context) {

    /**
     * Resolve a local attachment to a portable content part.
     * Reads the content URI and encodes as base64 data.
     *
     * @throws IllegalArgumentException if the content URI cannot be read
     * @throws IllegalStateException if the resolved data exceeds 10MB
     */
    suspend fun resolve(attachment: LocalAttachment): ContentPart.Image =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(attachment.contentUri)
                ?.use { it.readBytes() }
                ?: throw IllegalArgumentException(
                    "Cannot read content URI: ${attachment.contentUri}"
                )

            require(bytes.size <= MAX_DATA_BYTES) {
                "Attachment size ${bytes.size} exceeds maximum $MAX_DATA_BYTES bytes"
            }

            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)

            ContentPart.Image(
                data = encoded,
                mediaType = attachment.mediaType,
            )
        }

    companion object {
        /** ~10MB limit matching contract content_part.json maxLength */
        const val MAX_DATA_BYTES = 10 * 1024 * 1024
    }
}
