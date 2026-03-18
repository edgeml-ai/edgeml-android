package ai.octomil.audio

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AudioTranscriptionsTest {

    // =========================================================================
    // Enum wire values match contract exactly
    // =========================================================================

    @Test
    fun `TranscriptionResponseFormat wire values match contract`() {
        val expected = listOf("text", "json", "verbose_json", "srt", "vtt")
        val actual = TranscriptionResponseFormat.entries.map { it.wire }
        assertEquals(expected, actual)
    }

    @Test
    fun `TimestampGranularity wire values match contract`() {
        val expected = listOf("word", "segment")
        val actual = TimestampGranularity.entries.map { it.wire }
        assertEquals(expected, actual)
    }

    // =========================================================================
    // TranscriptionOptions preserves all contract fields
    // =========================================================================

    @Test
    fun `TranscriptionOptions preserves all fields`() {
        val opts = TranscriptionOptions(
            language = "en-US",
            responseFormat = TranscriptionResponseFormat.JSON,
            timestampGranularities = listOf(TimestampGranularity.WORD, TimestampGranularity.SEGMENT),
        )
        assertEquals("en-US", opts.language)
        assertEquals(TranscriptionResponseFormat.JSON, opts.responseFormat)
        assertEquals(2, opts.timestampGranularities.size)
        assertTrue(opts.timestampGranularities.contains(TimestampGranularity.WORD))
        assertTrue(opts.timestampGranularities.contains(TimestampGranularity.SEGMENT))
    }

    @Test
    fun `TranscriptionOptions defaults are correct`() {
        val opts = TranscriptionOptions()
        assertNull(opts.language)
        assertEquals(TranscriptionResponseFormat.TEXT, opts.responseFormat)
        assertTrue(opts.timestampGranularities.isEmpty())
    }

    // =========================================================================
    // TranscriptionSegment has confidence field
    // =========================================================================

    @Test
    fun `TranscriptionSegment includes confidence`() {
        val segment = TranscriptionSegment(
            startMs = 0,
            endMs = 1000,
            text = "hello",
            confidence = 0.95f,
        )
        assertEquals(0L, segment.startMs)
        assertEquals(1000L, segment.endMs)
        assertEquals("hello", segment.text)
        assertEquals(0.95f, segment.confidence!!, 0.001f)
    }

    @Test
    fun `TranscriptionSegment confidence is optional`() {
        val segment = TranscriptionSegment(startMs = 0, endMs = 500, text = "test")
        assertNull(segment.confidence)
    }

    // =========================================================================
    // TranscriptionResult has all contract fields
    // =========================================================================

    @Test
    fun `TranscriptionResult carries all contract fields`() {
        val result = TranscriptionResult(
            text = "hello world",
            language = "en",
            durationMs = 2500,
            segments = listOf(
                TranscriptionSegment(startMs = 0, endMs = 1200, text = "hello", confidence = 0.9f),
                TranscriptionSegment(startMs = 1200, endMs = 2500, text = "world"),
            ),
        )
        assertEquals("hello world", result.text)
        assertEquals("en", result.language)
        assertEquals(2500L, result.durationMs)
        assertEquals(2, result.segments.size)
        assertNotNull(result.segments[0].confidence)
        assertNull(result.segments[1].confidence)
    }

    // =========================================================================
    // Validation: rejects unsupported responseFormat
    // =========================================================================

    @Test
    fun `validateOptions accepts TEXT`() {
        val transcriptions = createTranscriptions()
        transcriptions.validateOptions(TranscriptionOptions(responseFormat = TranscriptionResponseFormat.TEXT))
        // no exception = pass
    }

    @Test
    fun `validateOptions accepts JSON`() {
        val transcriptions = createTranscriptions()
        transcriptions.validateOptions(TranscriptionOptions(responseFormat = TranscriptionResponseFormat.JSON))
        // no exception = pass
    }

    @Test
    fun `validateOptions rejects VERBOSE_JSON`() {
        assertUnsupportedFormat(TranscriptionResponseFormat.VERBOSE_JSON)
    }

    @Test
    fun `validateOptions rejects SRT`() {
        assertUnsupportedFormat(TranscriptionResponseFormat.SRT)
    }

    @Test
    fun `validateOptions rejects VTT`() {
        assertUnsupportedFormat(TranscriptionResponseFormat.VTT)
    }

    // =========================================================================
    // Validation: rejects timestampGranularities
    // =========================================================================

    @Test
    fun `validateOptions rejects non-empty timestampGranularities`() {
        val transcriptions = createTranscriptions()
        try {
            transcriptions.validateOptions(
                TranscriptionOptions(timestampGranularities = listOf(TimestampGranularity.WORD)),
            )
            fail("Expected OctomilException")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.UNSUPPORTED_MODALITY, e.errorCode)
            assertTrue(e.message!!.contains("timestamp_granularities"))
        }
    }

    @Test
    fun `validateOptions accepts empty timestampGranularities`() {
        val transcriptions = createTranscriptions()
        transcriptions.validateOptions(TranscriptionOptions(timestampGranularities = emptyList()))
        // no exception = pass
    }

    // =========================================================================
    // Validation: language is accepted (not rejected)
    // =========================================================================

    @Test
    fun `validateOptions accepts language hint`() {
        val transcriptions = createTranscriptions()
        transcriptions.validateOptions(TranscriptionOptions(language = "ja"))
        // no exception = pass
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun createTranscriptions(): AudioTranscriptions {
        // contextProvider and resolver don't matter for validation tests
        return AudioTranscriptions(
            contextProvider = { null },
            resolver = ai.octomil.ModelResolver { _, _ -> null },
        )
    }

    private fun assertUnsupportedFormat(format: TranscriptionResponseFormat) {
        val transcriptions = createTranscriptions()
        try {
            transcriptions.validateOptions(TranscriptionOptions(responseFormat = format))
            fail("Expected OctomilException for format ${format.wire}")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.UNSUPPORTED_MODALITY, e.errorCode)
            assertTrue(
                "Message should contain format name '${format.wire}': ${e.message}",
                e.message!!.contains(format.wire),
            )
        }
    }
}
