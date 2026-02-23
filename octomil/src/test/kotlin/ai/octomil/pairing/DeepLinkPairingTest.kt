package ai.octomil.pairing

import org.junit.Test
import retrofit2.Retrofit
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
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
    // createPairingApi() — property verification
    // =========================================================================

    @Test
    fun `createPairingApi returns a non-null API instance`() {
        val api = DeepLinkPairing.createPairingApi("https://api.octomil.com")
        assertNotNull(api)
    }

    @Test
    fun `createPairingApi base URL matches input without trailing slash`() {
        val api = DeepLinkPairing.createPairingApi("https://api.octomil.com")
        val retrofit = extractRetrofit(api)

        assertEquals("https://api.octomil.com/", retrofit.baseUrl().toString())
    }

    @Test
    fun `createPairingApi base URL matches input with trailing slash`() {
        val api = DeepLinkPairing.createPairingApi("https://api.octomil.com/")
        val retrofit = extractRetrofit(api)

        assertEquals("https://api.octomil.com/", retrofit.baseUrl().toString())
    }

    @Test
    fun `createPairingApi base URL matches localhost`() {
        val api = DeepLinkPairing.createPairingApi("http://localhost:8000")
        val retrofit = extractRetrofit(api)

        assertEquals("http://localhost:8000/", retrofit.baseUrl().toString())
    }

    @Test
    fun `createPairingApi base URL matches custom port URL`() {
        val api = DeepLinkPairing.createPairingApi("https://octomil.internal.company.com:8443")
        val retrofit = extractRetrofit(api)

        assertEquals("https://octomil.internal.company.com:8443/", retrofit.baseUrl().toString())
    }

    @Test
    fun `createPairingApi configures OkHttpClient timeouts`() {
        val api = DeepLinkPairing.createPairingApi("https://api.octomil.com")
        val retrofit = extractRetrofit(api)
        val client = retrofit.callFactory() as okhttp3.OkHttpClient

        assertEquals(30, client.connectTimeoutMillis / 1000)
        assertEquals(60, client.readTimeoutMillis / 1000)
        assertEquals(60, client.writeTimeoutMillis / 1000)
        assertTrue(client.retryOnConnectionFailure)
    }

    // =========================================================================
    // DeepLinkAction.Pair data class
    // =========================================================================

    @Test
    fun `Pair action holds token and host correctly`() {
        val action = DeepLinkHandler.DeepLinkAction.Pair(
            token = "test-token-123",
            host = "https://staging.octomil.com",
        )

        assertEquals("test-token-123", action.token)
        assertEquals("https://staging.octomil.com", action.host)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Extract the Retrofit instance backing a Retrofit-generated API proxy.
     *
     * Retrofit creates JDK dynamic proxies whose InvocationHandler holds a
     * reference to the Retrofit instance that created them.
     */
    private fun extractRetrofit(api: Any): Retrofit {
        val handler = Proxy.getInvocationHandler(api)
        val field = handler.javaClass.declaredFields.firstOrNull { f ->
            Retrofit::class.java.isAssignableFrom(f.type)
        } ?: throw NoSuchFieldException(
            "No Retrofit field found in ${handler.javaClass.name}; fields: ${
                handler.javaClass.declaredFields.map { it.name }
            }"
        )
        field.isAccessible = true
        return field.get(handler) as Retrofit
    }
}
