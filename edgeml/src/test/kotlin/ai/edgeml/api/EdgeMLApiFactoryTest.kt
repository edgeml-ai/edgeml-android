package ai.edgeml.api

import ai.edgeml.testConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
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

    // =========================================================================
    // Retry interceptor
    // =========================================================================

    private fun clientWithRetry(maxRetries: Int, baseDelayMs: Long = 10L): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(EdgeMLApiFactory.createRetryInterceptor(maxRetries, baseDelayMs))
            .build()

    @Test
    fun `retry interceptor succeeds on first attempt for 200`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val client = clientWithRetry(maxRetries = 3)
        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build(),
        ).execute()

        assertEquals(200, response.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `retry interceptor retries on 503 then succeeds`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val client = clientWithRetry(maxRetries = 3, baseDelayMs = 1L)
        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build(),
        ).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retry interceptor retries on 429 then succeeds`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val client = clientWithRetry(maxRetries = 2, baseDelayMs = 1L)
        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build(),
        ).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retry interceptor returns error after exhausting retries`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        val client = clientWithRetry(maxRetries = 2, baseDelayMs = 1L)
        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build(),
        ).execute()

        assertEquals(500, response.code)
        // 1 initial + 2 retries = 3 total requests
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `retry interceptor does not retry on 400`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad request"))

        val client = clientWithRetry(maxRetries = 3, baseDelayMs = 1L)
        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build(),
        ).execute()

        assertEquals(400, response.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `retry interceptor does not retry on 404`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val client = clientWithRetry(maxRetries = 3, baseDelayMs = 1L)
        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build(),
        ).execute()

        assertEquals(404, response.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `retry interceptor with maxRetries=0 does not retry`() {
        server.enqueue(MockResponse().setResponseCode(503))

        val client = clientWithRetry(maxRetries = 0, baseDelayMs = 1L)
        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build(),
        ).execute()

        assertEquals(503, response.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `retry interceptor respects Retry-After header`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val start = System.currentTimeMillis()
        val client = clientWithRetry(maxRetries = 1, baseDelayMs = 1L)
        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build(),
        ).execute()

        val elapsed = System.currentTimeMillis() - start

        assertEquals(200, response.code)
        // Retry-After: 1 means 1 second delay
        assertTrue(elapsed >= 900, "Expected at least ~1s delay for Retry-After, got ${elapsed}ms")
    }
}
