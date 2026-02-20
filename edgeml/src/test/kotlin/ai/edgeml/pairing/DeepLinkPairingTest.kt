package ai.edgeml.pairing

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [DeepLinkPairing] API factory.
 *
 * The full pairing flow (executePairing) depends on network calls and Android
 * context, so it is tested via integration/E2E tests. These unit tests verify
 * the API client factory logic.
 */
class DeepLinkPairingTest {

    // =========================================================================
    // createPairingApi()
    // =========================================================================

    @Test
    fun `createPairingApi returns a non-null API instance`() {
        val api = DeepLinkPairing.createPairingApi("https://api.edgeml.ai")

        assertNotNull(api)
    }

    @Test
    fun `createPairingApi handles URL without trailing slash`() {
        val api = DeepLinkPairing.createPairingApi("https://api.edgeml.ai")

        assertNotNull(api)
    }

    @Test
    fun `createPairingApi handles URL with trailing slash`() {
        val api = DeepLinkPairing.createPairingApi("https://api.edgeml.ai/")

        assertNotNull(api)
    }

    @Test
    fun `createPairingApi handles localhost URL`() {
        val api = DeepLinkPairing.createPairingApi("http://localhost:8000")

        assertNotNull(api)
    }

    @Test
    fun `createPairingApi handles custom port URL`() {
        val api = DeepLinkPairing.createPairingApi("https://edgeml.internal.company.com:8443")

        assertNotNull(api)
    }

    // =========================================================================
    // DeepLinkAction.Pair data class
    // =========================================================================

    @Test
    fun `Pair action holds token and host correctly`() {
        val action = DeepLinkHandler.DeepLinkAction.Pair(
            token = "test-token-123",
            host = "https://staging.edgeml.ai",
        )

        assertTrue(action.token == "test-token-123")
        assertTrue(action.host == "https://staging.edgeml.ai")
    }
}
