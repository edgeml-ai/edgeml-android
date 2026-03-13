package ai.octomil.config

import ai.octomil.errors.OctomilException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Tests for the [OctomilConfig.apiKey] alias, [OctomilConfig.Builder.apiKey], and
 * [OctomilConfig.Builder.deviceId] builder methods.
 */
class ApiKeyConfigTest {

    @Test
    fun `apiKey property aliases deviceAccessToken`() {
        val config = OctomilConfig.Builder()
            .serverUrl("https://api.octomil.com")
            .deviceAccessToken("sk-test-12345")
            .orgId("org-123")
            .modelId("model-456")
            .build()

        assertEquals("sk-test-12345", config.apiKey)
        assertEquals(config.deviceAccessToken, config.apiKey)
    }

    @Test
    fun `builder apiKey sets deviceAccessToken`() {
        val config = OctomilConfig.Builder()
            .serverUrl("https://api.octomil.com")
            .apiKey("sk-primary-key")
            .orgId("org-123")
            .modelId("model-456")
            .build()

        assertEquals("sk-primary-key", config.deviceAccessToken)
        assertEquals("sk-primary-key", config.apiKey)
    }

    @Test
    fun `builder apiKey and deviceAccessToken are interchangeable`() {
        val configViaApiKey = OctomilConfig.Builder()
            .serverUrl("https://api.octomil.com")
            .apiKey("token-abc")
            .orgId("org-123")
            .modelId("model-456")
            .build()

        val configViaToken = OctomilConfig.Builder()
            .serverUrl("https://api.octomil.com")
            .deviceAccessToken("token-abc")
            .orgId("org-123")
            .modelId("model-456")
            .build()

        assertEquals(configViaApiKey.deviceAccessToken, configViaToken.deviceAccessToken)
        assertEquals(configViaApiKey.apiKey, configViaToken.apiKey)
    }

    @Test
    fun `apiKey last-one-wins when both set`() {
        val config = OctomilConfig.Builder()
            .serverUrl("https://api.octomil.com")
            .deviceAccessToken("old-token")
            .apiKey("new-api-key")
            .orgId("org-123")
            .modelId("model-456")
            .build()

        assertEquals("new-api-key", config.apiKey)
        assertEquals("new-api-key", config.deviceAccessToken)
    }

    @Test
    fun `blank apiKey fails validation`() {
        assertFailsWith<OctomilException> {
            OctomilConfig.Builder()
                .serverUrl("https://api.octomil.com")
                .apiKey("")
                .orgId("org-123")
                .modelId("model-456")
                .build()
        }
    }

    // =========================================================================
    // deviceId
    // =========================================================================

    @Test
    fun `deviceId defaults to null`() {
        val config = OctomilConfig.Builder()
            .serverUrl("https://api.octomil.com")
            .apiKey("sk-test")
            .orgId("org-123")
            .modelId("model-456")
            .build()

        assertNull(config.deviceId)
    }

    @Test
    fun `builder deviceId sets config property`() {
        val config = OctomilConfig.Builder()
            .serverUrl("https://api.octomil.com")
            .apiKey("sk-test")
            .orgId("org-123")
            .modelId("model-456")
            .deviceId("my-device-001")
            .build()

        assertEquals("my-device-001", config.deviceId)
    }

    @Test
    fun `builder deviceId accepts null to clear`() {
        val config = OctomilConfig.Builder()
            .serverUrl("https://api.octomil.com")
            .apiKey("sk-test")
            .orgId("org-123")
            .modelId("model-456")
            .deviceId("my-device-001")
            .deviceId(null)
            .build()

        assertNull(config.deviceId)
    }
}
