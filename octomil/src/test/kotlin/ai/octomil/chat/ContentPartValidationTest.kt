package ai.octomil.chat

import ai.octomil.responses.ContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentPartValidationTest {

    // ── validate() ──

    @Test
    fun `validate accepts text-only parts`() {
        ContentPartValidation.validate(listOf(ContentPart.Text("hello")))
    }

    @Test
    fun `validate accepts image with assetId only`() {
        ContentPartValidation.validate(listOf(
            ContentPart.Image(assetId = "asset_123"),
        ))
    }

    @Test
    fun `validate accepts image with url and mediaType`() {
        ContentPartValidation.validate(listOf(
            ContentPart.Image(url = "https://example.com/img.jpg", mediaType = "image/jpeg"),
        ))
    }

    @Test
    fun `validate accepts image with data and mediaType`() {
        ContentPartValidation.validate(listOf(
            ContentPart.Image(data = "base64data", mediaType = "image/png"),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validate rejects image with both assetId and data`() {
        ContentPartValidation.validate(listOf(
            ContentPart.Image(assetId = "asset_123", data = "base64data", mediaType = "image/png"),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validate rejects image with both assetId and url`() {
        ContentPartValidation.validate(listOf(
            ContentPart.Image(assetId = "asset_123", url = "https://example.com/img.jpg"),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validate rejects image with all three sources`() {
        ContentPartValidation.validate(listOf(
            ContentPart.Image(
                assetId = "asset_123",
                url = "https://example.com/img.jpg",
                data = "base64data",
                mediaType = "image/jpeg",
            ),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validate rejects image with no source`() {
        ContentPartValidation.validate(listOf(
            ContentPart.Image(),
        ))
    }

    @Test
    fun `validate accepts audio with assetId only`() {
        ContentPartValidation.validate(listOf(
            ContentPart.Audio(assetId = "asset_456"),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validate rejects audio with dual source`() {
        ContentPartValidation.validate(listOf(
            ContentPart.Audio(assetId = "asset_456", data = "base64audio"),
        ))
    }

    @Test
    fun `validate accepts video with data and mediaType`() {
        ContentPartValidation.validate(listOf(
            ContentPart.Video(data = "base64video", mediaType = "video/mp4"),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validate rejects video with dual source`() {
        ContentPartValidation.validate(listOf(
            ContentPart.Video(url = "https://example.com/vid.mp4", data = "base64video"),
        ))
    }

    @Test
    fun `validate accepts mixed text and image`() {
        ContentPartValidation.validate(listOf(
            ContentPart.Text("describe this"),
            ContentPart.Image(assetId = "asset_789"),
        ))
    }

    @Test
    fun `validate skips File parts`() {
        ContentPartValidation.validate(listOf(
            ContentPart.File(data = "data", mediaType = "application/pdf"),
        ))
    }

    // ── deriveContent() ──

    @Test
    fun `deriveContent concatenates text parts with newline`() {
        val result = ContentPartValidation.deriveContent(listOf(
            ContentPart.Text("hello"),
            ContentPart.Text("world"),
        ))
        assertEquals("hello\nworld", result)
    }

    @Test
    fun `deriveContent returns single text part as-is`() {
        val result = ContentPartValidation.deriveContent(listOf(
            ContentPart.Text("hello"),
        ))
        assertEquals("hello", result)
    }

    @Test
    fun `deriveContent ignores non-text parts`() {
        val result = ContentPartValidation.deriveContent(listOf(
            ContentPart.Text("describe this"),
            ContentPart.Image(assetId = "asset_123"),
        ))
        assertEquals("describe this", result)
    }

    @Test
    fun `deriveContent returns null when no text parts`() {
        val result = ContentPartValidation.deriveContent(listOf(
            ContentPart.Image(assetId = "asset_123"),
        ))
        assertNull(result)
    }

    @Test
    fun `deriveContent returns null for empty list`() {
        val result = ContentPartValidation.deriveContent(emptyList())
        assertNull(result)
    }

    @Test
    fun `deriveContent preserves text order among mixed parts`() {
        val result = ContentPartValidation.deriveContent(listOf(
            ContentPart.Text("first"),
            ContentPart.Image(assetId = "img"),
            ContentPart.Text("second"),
            ContentPart.Audio(assetId = "aud"),
            ContentPart.Text("third"),
        ))
        assertEquals("first\nsecond\nthird", result)
    }
}
