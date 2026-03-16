package ai.octomil.android

import android.graphics.Bitmap
import android.net.Uri

/**
 * Platform-local attachment before conversion to portable [ContentPart].
 * Contains Android-specific references (Uri, Bitmap) that must NOT cross the wire.
 * Convert to [ContentPart.Image] via [AttachmentResolver] before request construction.
 */
data class LocalAttachment(
    val contentUri: Uri,
    val mediaType: String,
    val displayName: String? = null,
    val bitmap: Bitmap? = null,
)
