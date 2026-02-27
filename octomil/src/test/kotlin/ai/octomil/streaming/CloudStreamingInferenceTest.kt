package ai.octomil.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudStreamingInferenceTest {

    // =========================================================================
    // StreamToken
    // =========================================================================

    @Test
    fun `StreamToken defaults`() {
        val token = StreamToken(token = "hello", done = false)
        assertEquals("hello", token.token)
        assertFalse(token.done)
        assertNull(token.provider)
        assertNull(token.latencyMs)
        assertNull(token.sessionId)
    }

    @Test
    fun `StreamToken all fields`() {
        val token = StreamToken(
            token = "world",
            done = true,
            provider = "ollama",
            latencyMs = 42.5,
            sessionId = "abc-123",
        )
        assertEquals("world", token.token)
        assertTrue(token.done)
        assertEquals("ollama", token.provider)
        assertEquals(42.5, token.latencyMs!!, 0.01)
        assertEquals("abc-123", token.sessionId)
    }

    // =========================================================================
    // SSE Parsing
    // =========================================================================

    @Test
    fun `parseSSELine normal token`() {
        val line = """data: {"token": "The", "done": false, "provider": "ollama"}"""
        val token = CloudStreamingClient.parseSSELine(line)
        assertNotNull(token)
        assertEquals("The", token!!.token)
        assertFalse(token.done)
        assertEquals("ollama", token.provider)
    }

    @Test
    fun `parseSSELine done token`() {
        val line = """data: {"done": true, "latency_ms": 1234.5, "session_id": "abc-123"}"""
        val token = CloudStreamingClient.parseSSELine(line)
        assertNotNull(token)
        assertTrue(token!!.done)
        assertEquals("", token.token)
        assertEquals(1234.5, token.latencyMs!!, 0.01)
        assertEquals("abc-123", token.sessionId)
    }

    @Test
    fun `parseSSELine empty line returns null`() {
        assertNull(CloudStreamingClient.parseSSELine(""))
        assertNull(CloudStreamingClient.parseSSELine("   "))
    }

    @Test
    fun `parseSSELine non-data line returns null`() {
        assertNull(CloudStreamingClient.parseSSELine("event: message"))
        assertNull(CloudStreamingClient.parseSSELine("id: 1"))
        assertNull(CloudStreamingClient.parseSSELine(": comment"))
    }

    @Test
    fun `parseSSELine empty data returns null`() {
        assertNull(CloudStreamingClient.parseSSELine("data:"))
        assertNull(CloudStreamingClient.parseSSELine("data:   "))
    }

    @Test
    fun `parseSSELine invalid JSON returns null`() {
        assertNull(CloudStreamingClient.parseSSELine("data: not-json"))
    }

    @Test
    fun `parseSSELine with whitespace`() {
        val line = """  data: {"token": "x", "done": false}  """
        val token = CloudStreamingClient.parseSSELine(line)
        assertNotNull(token)
        assertEquals("x", token!!.token)
    }

    @Test
    fun `parseSSELine missing token field defaults to empty string`() {
        val line = """data: {"done": false}"""
        val token = CloudStreamingClient.parseSSELine(line)
        assertNotNull(token)
        assertEquals("", token!!.token)
        assertFalse(token.done)
    }
}
