package ai.edgeml.pairing.ui

import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [PairingActivity.Companion.extractPairingParams].
 *
 * Verifies deep link URI parsing and intent extra extraction.
 */
class PairingActivityTest {

    // =========================================================================
    // Deep link URI parsing
    // =========================================================================

    @Test
    fun `extractPairingParams parses deep link URI with token and host`() {
        val uri = Uri.parse("edgeml://pair?token=ABC123&host=192.168.1.100")
        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns uri
        every { intent.getStringExtra(any()) } returns null

        val result = PairingActivity.extractPairingParams(intent)

        assertNotNull(result)
        assertEquals("ABC123", result.first)
        assertEquals("192.168.1.100", result.second)
    }

    @Test
    fun `extractPairingParams parses deep link URI with full URL host`() {
        val uri = Uri.parse("edgeml://pair?token=XYZ789&host=https://api.edgeml.ai")
        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns uri
        every { intent.getStringExtra(any()) } returns null

        val result = PairingActivity.extractPairingParams(intent)

        assertNotNull(result)
        assertEquals("XYZ789", result.first)
        assertEquals("https://api.edgeml.ai", result.second)
    }

    @Test
    fun `extractPairingParams returns null when URI has no token`() {
        val uri = Uri.parse("edgeml://pair?host=192.168.1.100")
        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns uri
        every { intent.getStringExtra(any()) } returns null

        val result = PairingActivity.extractPairingParams(intent)

        assertNull(result)
    }

    @Test
    fun `extractPairingParams returns null when URI has no host`() {
        val uri = Uri.parse("edgeml://pair?token=ABC123")
        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns uri
        every { intent.getStringExtra(any()) } returns null

        val result = PairingActivity.extractPairingParams(intent)

        assertNull(result)
    }

    @Test
    fun `extractPairingParams returns null when URI is null and no extras`() {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns null
        every { intent.getStringExtra(any()) } returns null

        val result = PairingActivity.extractPairingParams(intent)

        assertNull(result)
    }

    // =========================================================================
    // Intent extras
    // =========================================================================

    @Test
    fun `extractPairingParams reads from intent extras when no URI`() {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns null
        every { intent.getStringExtra("ai.edgeml.pairing.EXTRA_TOKEN") } returns "TOKEN456"
        every { intent.getStringExtra("ai.edgeml.pairing.EXTRA_HOST") } returns "https://api.edgeml.ai"

        val result = PairingActivity.extractPairingParams(intent)

        assertNotNull(result)
        assertEquals("TOKEN456", result.first)
        assertEquals("https://api.edgeml.ai", result.second)
    }

    @Test
    fun `extractPairingParams returns null when extras missing token`() {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns null
        every { intent.getStringExtra("ai.edgeml.pairing.EXTRA_TOKEN") } returns null
        every { intent.getStringExtra("ai.edgeml.pairing.EXTRA_HOST") } returns "https://api.edgeml.ai"

        val result = PairingActivity.extractPairingParams(intent)

        assertNull(result)
    }

    @Test
    fun `extractPairingParams returns null when extras missing host`() {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns null
        every { intent.getStringExtra("ai.edgeml.pairing.EXTRA_TOKEN") } returns "TOKEN456"
        every { intent.getStringExtra("ai.edgeml.pairing.EXTRA_HOST") } returns null

        val result = PairingActivity.extractPairingParams(intent)

        assertNull(result)
    }

    // =========================================================================
    // URI takes priority over extras
    // =========================================================================

    @Test
    fun `extractPairingParams prefers URI over extras when both present`() {
        val uri = Uri.parse("edgeml://pair?token=URI_TOKEN&host=uri.host.com")
        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns uri
        every { intent.getStringExtra("ai.edgeml.pairing.EXTRA_TOKEN") } returns "EXTRA_TOKEN"
        every { intent.getStringExtra("ai.edgeml.pairing.EXTRA_HOST") } returns "extra.host.com"

        val result = PairingActivity.extractPairingParams(intent)

        assertNotNull(result)
        assertEquals("URI_TOKEN", result.first)
        assertEquals("uri.host.com", result.second)
    }

    @Test
    fun `extractPairingParams falls back to extras when URI is incomplete`() {
        // URI has token but no host
        val uri = Uri.parse("edgeml://pair?token=URI_TOKEN")
        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns uri
        every { intent.getStringExtra("ai.edgeml.pairing.EXTRA_TOKEN") } returns "EXTRA_TOKEN"
        every { intent.getStringExtra("ai.edgeml.pairing.EXTRA_HOST") } returns "extra.host.com"

        val result = PairingActivity.extractPairingParams(intent)

        assertNotNull(result)
        // Falls back to extras since URI was incomplete
        assertEquals("EXTRA_TOKEN", result.first)
        assertEquals("extra.host.com", result.second)
    }
}
