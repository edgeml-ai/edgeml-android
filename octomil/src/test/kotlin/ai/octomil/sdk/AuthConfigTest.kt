package ai.octomil.sdk

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthConfigTest {

    // =========================================================================
    // PublishableKey — valid keys
    // =========================================================================

    @Test
    fun `PublishableKey accepts key with oct_pub_test_ prefix`() {
        val config = AuthConfig.PublishableKey("oct_pub_test_abc123")
        assertEquals("oct_pub_test_abc123", config.key)
    }

    @Test
    fun `PublishableKey accepts key with oct_pub_live_ prefix`() {
        val config = AuthConfig.PublishableKey("oct_pub_live_abc123")
        assertEquals("oct_pub_live_abc123", config.key)
    }

    // =========================================================================
    // PublishableKey — rejected keys
    // =========================================================================

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
    fun `PublishableKey rejects bare oct_pub_ without environment`() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.PublishableKey("oct_pub_abc123")
        }
    }

    @Test
    fun `PublishableKey rejects oct_pub_ alone`() {
        assertFailsWith<IllegalArgumentException> {
            AuthConfig.PublishableKey("oct_pub_")
        }
    }

    // =========================================================================
    // PublishableKey — environment property
    // =========================================================================

    @Test
    fun `environment returns test for oct_pub_test_ key`() {
        val config = AuthConfig.PublishableKey("oct_pub_test_abc123")
        assertEquals("test", config.environment)
    }

    @Test
    fun `environment returns live for oct_pub_live_ key`() {
        val config = AuthConfig.PublishableKey("oct_pub_live_abc123")
        assertEquals("live", config.environment)
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
