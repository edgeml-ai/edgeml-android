package ai.octomil.sdk

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthConfigTest {

    // =========================================================================
    // PublishableKey
    // =========================================================================

    @Test
    fun `PublishableKey accepts key with oct_pub_ prefix`() {
        val config = AuthConfig.PublishableKey("oct_pub_abc123")
        assertEquals("oct_pub_abc123", config.key)
    }

    @Test
    fun `PublishableKey rejects key without oct_pub_ prefix`() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.PublishableKey("sk_test_abc123")
        }
    }

    @Test
    fun `PublishableKey rejects empty key`() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.PublishableKey("")
        }
    }

    @Test
    fun `PublishableKey rejects key with partial prefix`() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.PublishableKey("oct_pub")
        }
    }

    @Test
    fun `PublishableKey accepts key with only prefix`() {
        // "oct_pub_" alone is technically valid per the require check
        val config = AuthConfig.PublishableKey("oct_pub_")
        assertEquals("oct_pub_", config.key)
    }

    // =========================================================================
    // BootstrapToken
    // =========================================================================

    @Test
    fun `BootstrapToken accepts any string`() {
        val config = AuthConfig.BootstrapToken("any-token-value-here")
        assertEquals("any-token-value-here", config.token)
    }

    @Test
    fun `BootstrapToken accepts empty string`() {
        val config = AuthConfig.BootstrapToken("")
        assertEquals("", config.token)
    }

    // =========================================================================
    // Anonymous
    // =========================================================================

    @Test
    fun `Anonymous requires appId`() {
        val config = AuthConfig.Anonymous(appId = "com.example.myapp")
        assertEquals("com.example.myapp", config.appId)
    }

    @Test
    fun `Anonymous stores appId`() {
        val config = AuthConfig.Anonymous("app-id-xyz")
        assertEquals("app-id-xyz", config.appId)
    }
}
