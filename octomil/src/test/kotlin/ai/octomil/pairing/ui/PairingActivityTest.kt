package ai.octomil.pairing.ui

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
 *
 * NOTE: Both [Uri] and [Intent] are Android framework classes whose methods are
 * no-ops in the local JVM test environment (android.jar stubs). We must mock
 * [Uri.getQueryParameter] and [Intent.data]/[Intent.getStringExtra] to simulate
 * real behaviour.
 */
class PairingActivityTest {

    // =========================================================================
    // Deep link URI parsing
    // =========================================================================

    @Test
    fun `extractPairingParams parses deep link URI with token and host`() {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.getQueryParameter("token") } returns "ABC123"
        every { uri.getQueryParameter("host") } returns "192.168.1.100"

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
        val uri = mockk<Uri>(relaxed = true)
        every { uri.getQueryParameter("token") } returns "XYZ789"
        every { uri.getQueryParameter("host") } returns "https://api.octomil.com"

        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns uri
        every { intent.getStringExtra(any()) } returns null

        val result = PairingActivity.extractPairingParams(intent)

        assertNotNull(result)
        assertEquals("XYZ789", result.first)
        assertEquals("https://api.octomil.com", result.second)
    }

    @Test
    fun `extractPairingParams returns null when URI has no token`() {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.getQueryParameter("token") } returns null
        every { uri.getQueryParameter("host") } returns "192.168.1.100"

        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns uri
        every { intent.getStringExtra(any()) } returns null

        val result = PairingActivity.extractPairingParams(intent)

        assertNull(result)
    }

    @Test
    fun `extractPairingParams returns null when URI has no host`() {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.getQueryParameter("token") } returns "ABC123"
        every { uri.getQueryParameter("host") } returns null

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
        every { intent.getStringExtra("ai.octomil.pairing.EXTRA_TOKEN") } returns "TOKEN456"
        every { intent.getStringExtra("ai.octomil.pairing.EXTRA_HOST") } returns "https://api.octomil.com"

        val result = PairingActivity.extractPairingParams(intent)

        assertNotNull(result)
        assertEquals("TOKEN456", result.first)
        assertEquals("https://api.octomil.com", result.second)
    }

    @Test
    fun `extractPairingParams returns null when extras missing token`() {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns null
        every { intent.getStringExtra("ai.octomil.pairing.EXTRA_TOKEN") } returns null
        every { intent.getStringExtra("ai.octomil.pairing.EXTRA_HOST") } returns "https://api.octomil.com"

        val result = PairingActivity.extractPairingParams(intent)

        assertNull(result)
    }

    @Test
    fun `extractPairingParams returns null when extras missing host`() {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns null
        every { intent.getStringExtra("ai.octomil.pairing.EXTRA_TOKEN") } returns "TOKEN456"
        every { intent.getStringExtra("ai.octomil.pairing.EXTRA_HOST") } returns null

        val result = PairingActivity.extractPairingParams(intent)

        assertNull(result)
    }

    // =========================================================================
    // URI takes priority over extras
    // =========================================================================

    @Test
    fun `extractPairingParams prefers URI over extras when both present`() {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.getQueryParameter("token") } returns "URI_TOKEN"
        every { uri.getQueryParameter("host") } returns "uri.host.com"

        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns uri
        every { intent.getStringExtra("ai.octomil.pairing.EXTRA_TOKEN") } returns "EXTRA_TOKEN"
        every { intent.getStringExtra("ai.octomil.pairing.EXTRA_HOST") } returns "extra.host.com"

        val result = PairingActivity.extractPairingParams(intent)

        assertNotNull(result)
        assertEquals("URI_TOKEN", result.first)
        assertEquals("uri.host.com", result.second)
    }

    @Test
    fun `extractPairingParams falls back to extras when URI is incomplete`() {
        // URI has token but no host
        val uri = mockk<Uri>(relaxed = true)
        every { uri.getQueryParameter("token") } returns "URI_TOKEN"
        every { uri.getQueryParameter("host") } returns null

        val intent = mockk<Intent>(relaxed = true)
        every { intent.data } returns uri
        every { intent.getStringExtra("ai.octomil.pairing.EXTRA_TOKEN") } returns "EXTRA_TOKEN"
        every { intent.getStringExtra("ai.octomil.pairing.EXTRA_HOST") } returns "extra.host.com"

        val result = PairingActivity.extractPairingParams(intent)

        assertNotNull(result)
        // Falls back to extras since URI was incomplete
        assertEquals("EXTRA_TOKEN", result.first)
        assertEquals("extra.host.com", result.second)
    }
}
