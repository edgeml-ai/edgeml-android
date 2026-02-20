package ai.edgeml.pairing

import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [DeepLinkHandler] URI parsing and intent handling.
 *
 * Uses MockK to construct [Uri] instances since `Uri.parse()` does not work
 * in plain JUnit tests without Robolectric.
 */
class DeepLinkHandlerTest {

    /**
     * Helper to build a mock [Uri] with the given scheme, host, and query parameters.
     */
    private fun mockUri(
        scheme: String?,
        host: String?,
        queryParams: Map<String, String?> = emptyMap(),
    ): Uri {
        val uri = mockk<Uri>()
        every { uri.scheme } returns scheme
        every { uri.host } returns host
        every { uri.getQueryParameter(any()) } returns null
        for ((key, value) in queryParams) {
            every { uri.getQueryParameter(key) } returns value
        }
        every { uri.toString() } returns buildString {
            append(scheme ?: "")
            append("://")
            append(host ?: "")
            if (queryParams.isNotEmpty()) {
                append("?")
                append(queryParams.entries.joinToString("&") { "${it.key}=${it.value ?: ""}" })
            }
        }
        return uri
    }

    // =========================================================================
    // parse() — valid pairing links
    // =========================================================================

    @Test
    fun `parse returns Pair action for valid edgeml pair URI with token and host`() {
        val uri = mockUri(
            scheme = "edgeml",
            host = "pair",
            queryParams = mapOf("token" to "abc123", "host" to "https://api.edgeml.io"),
        )

        val action = DeepLinkHandler.parse(uri)

        assertNotNull(action)
        assertIs<DeepLinkHandler.DeepLinkAction.Pair>(action)
        assertEquals("abc123", action.token)
        assertEquals("https://api.edgeml.io", action.host)
    }

    @Test
    fun `parse returns Pair action with default host when host parameter is missing`() {
        val uri = mockUri(
            scheme = "edgeml",
            host = "pair",
            queryParams = mapOf("token" to "mytoken"),
        )

        val action = DeepLinkHandler.parse(uri)

        assertNotNull(action)
        assertIs<DeepLinkHandler.DeepLinkAction.Pair>(action)
        assertEquals("mytoken", action.token)
        assertEquals(DeepLinkHandler.DEFAULT_HOST, action.host)
    }

    @Test
    fun `parse returns Pair action with default host when host parameter is blank`() {
        val uri = mockUri(
            scheme = "edgeml",
            host = "pair",
            queryParams = mapOf("token" to "abc", "host" to ""),
        )

        val action = DeepLinkHandler.parse(uri)

        assertNotNull(action)
        assertIs<DeepLinkHandler.DeepLinkAction.Pair>(action)
        assertEquals("abc", action.token)
        assertEquals(DeepLinkHandler.DEFAULT_HOST, action.host)
    }

    @Test
    fun `parse preserves complex token values`() {
        val uri = mockUri(
            scheme = "edgeml",
            host = "pair",
            queryParams = mapOf("token" to "a1b2c3-d4e5f6", "host" to "https://staging.edgeml.ai"),
        )

        val action = DeepLinkHandler.parse(uri)

        assertNotNull(action)
        assertIs<DeepLinkHandler.DeepLinkAction.Pair>(action)
        assertEquals("a1b2c3-d4e5f6", action.token)
        assertEquals("https://staging.edgeml.ai", action.host)
    }

    // =========================================================================
    // parse() — missing or invalid token
    // =========================================================================

    @Test
    fun `parse returns null when token parameter is missing`() {
        val uri = mockUri(
            scheme = "edgeml",
            host = "pair",
            queryParams = mapOf("host" to "https://api.edgeml.io"),
        )

        val action = DeepLinkHandler.parse(uri)

        assertNull(action)
    }

    @Test
    fun `parse returns null when token parameter is blank`() {
        val uri = mockUri(
            scheme = "edgeml",
            host = "pair",
            queryParams = mapOf("token" to "", "host" to "https://api.edgeml.io"),
        )

        val action = DeepLinkHandler.parse(uri)

        assertNull(action)
    }

    @Test
    fun `parse returns null when pair URI has no query parameters`() {
        val uri = mockUri(
            scheme = "edgeml",
            host = "pair",
        )

        val action = DeepLinkHandler.parse(uri)

        assertNull(action)
    }

    // =========================================================================
    // parse() — wrong scheme
    // =========================================================================

    @Test
    fun `parse returns null for non-edgeml scheme`() {
        val uri = mockUri(
            scheme = "https",
            host = "pair",
            queryParams = mapOf("token" to "abc"),
        )

        val action = DeepLinkHandler.parse(uri)

        assertNull(action)
    }

    @Test
    fun `parse returns null for http scheme`() {
        val uri = mockUri(
            scheme = "http",
            host = "pair",
            queryParams = mapOf("token" to "abc"),
        )

        val action = DeepLinkHandler.parse(uri)

        assertNull(action)
    }

    @Test
    fun `parse returns null for null scheme`() {
        val uri = mockUri(
            scheme = null,
            host = "pair",
            queryParams = mapOf("token" to "abc"),
        )

        val action = DeepLinkHandler.parse(uri)

        assertNull(action)
    }

    // =========================================================================
    // parse() — unknown action
    // =========================================================================

    @Test
    fun `parse returns Unknown for unrecognized edgeml action`() {
        val uri = mockUri(
            scheme = "edgeml",
            host = "settings",
            queryParams = mapOf("page" to "general"),
        )

        val action = DeepLinkHandler.parse(uri)

        assertNotNull(action)
        assertIs<DeepLinkHandler.DeepLinkAction.Unknown>(action)
        assertEquals(uri, action.uri)
    }

    @Test
    fun `parse returns Unknown for edgeml scheme with different host`() {
        val uri = mockUri(
            scheme = "edgeml",
            host = "deploy",
            queryParams = mapOf("model" to "mobilenet"),
        )

        val action = DeepLinkHandler.parse(uri)

        assertNotNull(action)
        assertIs<DeepLinkHandler.DeepLinkAction.Unknown>(action)
        assertEquals(uri, action.uri)
    }

    @Test
    fun `parse returns Unknown for edgeml scheme with null host`() {
        val uri = mockUri(
            scheme = "edgeml",
            host = null,
        )

        val action = DeepLinkHandler.parse(uri)

        assertNotNull(action)
        assertIs<DeepLinkHandler.DeepLinkAction.Unknown>(action)
    }

    // =========================================================================
    // handleIntent()
    // =========================================================================

    @Test
    fun `handleIntent extracts URI from intent and parses valid pairing link`() {
        val uri = mockUri(
            scheme = "edgeml",
            host = "pair",
            queryParams = mapOf("token" to "intent-token", "host" to "https://intent.edgeml.ai"),
        )
        val intent = mockk<Intent>()
        every { intent.data } returns uri

        val action = DeepLinkHandler.handleIntent(intent)

        assertNotNull(action)
        assertIs<DeepLinkHandler.DeepLinkAction.Pair>(action)
        assertEquals("intent-token", action.token)
        assertEquals("https://intent.edgeml.ai", action.host)
    }

    @Test
    fun `handleIntent returns null when intent has no data URI`() {
        val intent = mockk<Intent>()
        every { intent.data } returns null

        val action = DeepLinkHandler.handleIntent(intent)

        assertNull(action)
    }

    @Test
    fun `handleIntent returns null for non-edgeml intent`() {
        val uri = mockUri(
            scheme = "https",
            host = "example.com",
        )
        val intent = mockk<Intent>()
        every { intent.data } returns uri

        val action = DeepLinkHandler.handleIntent(intent)

        assertNull(action)
    }

    @Test
    fun `handleIntent returns Unknown for unrecognized edgeml intent`() {
        val uri = mockUri(
            scheme = "edgeml",
            host = "unknown-action",
        )
        val intent = mockk<Intent>()
        every { intent.data } returns uri

        val action = DeepLinkHandler.handleIntent(intent)

        assertNotNull(action)
        assertIs<DeepLinkHandler.DeepLinkAction.Unknown>(action)
    }

    // =========================================================================
    // Constants
    // =========================================================================

    @Test
    fun `SCHEME constant is edgeml`() {
        assertEquals("edgeml", DeepLinkHandler.SCHEME)
    }

    @Test
    fun `DEFAULT_HOST constant points to production API`() {
        assertEquals("https://api.edgeml.ai", DeepLinkHandler.DEFAULT_HOST)
    }

    @Test
    fun `MANIFEST_INTENT_FILTER contains required XML elements`() {
        val filter = DeepLinkHandler.MANIFEST_INTENT_FILTER
        assert(filter.contains("android.intent.action.VIEW"))
        assert(filter.contains("android.intent.category.DEFAULT"))
        assert(filter.contains("android.intent.category.BROWSABLE"))
        assert(filter.contains("android:scheme=\"edgeml\""))
        assert(filter.contains("android:host=\"pair\""))
    }
}
