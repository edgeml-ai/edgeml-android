package ai.edgeml.api

import ai.edgeml.testConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EdgeMLApiFactoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // =========================================================================
    // Auth interceptor
    // =========================================================================

    @Test
    fun `auth interceptor adds Bearer token header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val config = testConfig(
            serverUrl = server.url("/").toString().trimEnd('/'),
            deviceAccessToken = "my-secret-token",
            orgId = "org-42",
        )
        val api = EdgeMLApiFactory.create(config)

        // Make a blocking call using a simple retrofit call
        kotlinx.coroutines.runBlocking { api.healthCheck() }

        val recorded = server.takeRequest()
        assertEquals("Bearer my-secret-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `auth interceptor adds X-Org-Id header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val config = testConfig(
            serverUrl = server.url("/").toString().trimEnd('/'),
            orgId = "org-99",
        )
        val api = EdgeMLApiFactory.create(config)

        kotlinx.coroutines.runBlocking { api.healthCheck() }

        val recorded = server.takeRequest()
        assertEquals("org-99", recorded.getHeader("X-Org-Id"))
    }

    // =========================================================================
    // User-Agent header
    // =========================================================================

    @Test
    fun `User-Agent header is present`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val config = testConfig(
            serverUrl = server.url("/").toString().trimEnd('/'),
        )
        val api = EdgeMLApiFactory.create(config)

        kotlinx.coroutines.runBlocking { api.healthCheck() }

        val recorded = server.takeRequest()
        val userAgent = recorded.getHeader("User-Agent")
        assertNotNull(userAgent)
        assertTrue(userAgent.contains("EdgeML-Android-SDK"))
    }

    // =========================================================================
    // Factory creates valid API
    // =========================================================================

    @Test
    fun `create returns non-null API instance`() {
        val config = testConfig(
            serverUrl = server.url("/").toString().trimEnd('/'),
        )
        val api = EdgeMLApiFactory.create(config)
        assertNotNull(api)
    }

    // =========================================================================
    // Request path
    // =========================================================================

    @Test
    fun `healthCheck hits correct endpoint`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val config = testConfig(
            serverUrl = server.url("/").toString().trimEnd('/'),
        )
        val api = EdgeMLApiFactory.create(config)

        kotlinx.coroutines.runBlocking { api.healthCheck() }

        val recorded = server.takeRequest()
        assertEquals("/health", recorded.path)
    }
}
