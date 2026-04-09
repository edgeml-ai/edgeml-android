package ai.octomil.sdk

import ai.octomil.sdk.FacadeResponses
import ai.octomil.responses.OctomilResponses
import ai.octomil.responses.OutputItem
import ai.octomil.responses.Response
import ai.octomil.responses.ResponseRequest
import ai.octomil.responses.InputItem
import ai.octomil.runtime.core.ModelRuntime
import ai.octomil.runtime.core.RuntimeCapabilities
import ai.octomil.runtime.core.RuntimeChunk
import ai.octomil.runtime.core.RuntimeRequest
import ai.octomil.runtime.core.RuntimeResponse
import ai.octomil.storage.SecureStorage
import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnifiedFacadeTest {

    private val context: Context = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(ai.octomil.Octomil)
        every { ai.octomil.Octomil.init(any()) } returns Unit

        mockkObject(SecureStorage.Companion)
        val mockStorage: SecureStorage = mockk(relaxed = true)
        every { SecureStorage.getInstance(any(), any()) } returns mockStorage

        mockkObject(DeviceContext.Companion)
        coEvery { DeviceContext.getOrCreateInstallationId(any()) } returns "test-install-id"
        coEvery { DeviceContext.restoreCachedToken(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(DeviceContext.Companion)
        unmockkObject(SecureStorage.Companion)
        unmockkObject(ai.octomil.Octomil)
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    @Test
    fun `constructor with publishableKey creates PublishableKey auth`() {
        val client = Octomil(context, publishableKey = "oct_pub_test_abc123")
        // If it doesn't throw, the auth config was created successfully
        assertTrue(true)
    }

    @Test
    fun `constructor with apiKey and orgId creates auth`() {
        val client = Octomil(context, apiKey = "sk_test_abc", orgId = "org_123")
        assertTrue(true)
    }

    @Test
    fun `constructor with apiKey but no orgId throws`() {
        assertFailsWith<IllegalArgumentException> {
            Octomil(context, apiKey = "sk_test_abc")
        }
    }

    @Test
    fun `constructor with explicit auth passes through`() {
        val auth = AuthConfig.BootstrapToken("my-token")
        val client = Octomil(context, auth = auth)
        assertTrue(true)
    }

    @Test
    fun `constructor rejects invalid publishable key prefix`() {
        assertFailsWith<IllegalArgumentException> {
            Octomil(context, publishableKey = "invalid_key_here")
        }
    }

    @Test
    fun `constructor throws when no auth provided`() {
        assertFailsWith<IllegalArgumentException> {
            Octomil(context)
        }
    }

    // =========================================================================
    // initialize()
    // =========================================================================

    @Test
    fun `initialize is idempotent`() = runTest {
        val client = Octomil(context, apiKey = "sk_test_abc", orgId = "org_123")
        client.initialize()
        client.initialize() // second call should not throw
        assertTrue(true)
    }

    // =========================================================================
    // responses before initialize
    // =========================================================================

    @Test
    fun `responses before initialize throws OctomilNotInitializedError`() {
        val client = Octomil(context, apiKey = "sk_test_abc", orgId = "org_123")
        assertFailsWith<OctomilNotInitializedError> {
            client.responses
        }
    }

    // =========================================================================
    // FacadeResponses convenience methods
    // =========================================================================

    @Test
    fun `FacadeResponses create with model and input delegates to underlying`() = runTest {
        val runtime = stubRuntime(RuntimeResponse(text = "Hello", finishReason = "stop"))
        val underlying = OctomilResponses(runtimeResolver = { runtime })
        val facade = FacadeResponses(underlying)

        val response = facade.create("test-model", "Hi there")

        assertEquals("Hello", response.outputText)
        assertEquals("stop", response.finishReason)
    }

    @Test
    fun `FacadeResponses create with ResponseRequest delegates to underlying`() = runTest {
        val runtime = stubRuntime(RuntimeResponse(text = "World", finishReason = "stop"))
        val underlying = OctomilResponses(runtimeResolver = { runtime })
        val facade = FacadeResponses(underlying)

        val request = ResponseRequest(model = "test-model", input = listOf(InputItem.text("Hi")))
        val response = facade.create(request)

        assertEquals("World", response.outputText)
    }

    // =========================================================================
    // Response.outputText
    // =========================================================================

    @Test
    fun `outputText concatenates text items`() {
        val response = Response(
            id = "resp_1",
            model = "test",
            output = listOf(
                OutputItem.Text("Hello "),
                OutputItem.Text("world"),
            ),
            finishReason = "stop",
        )

        assertEquals("Hello world", response.outputText)
    }

    @Test
    fun `outputText returns empty string for empty output`() {
        val response = Response(
            id = "resp_2",
            model = "test",
            output = emptyList(),
            finishReason = "stop",
        )

        assertEquals("", response.outputText)
    }

    @Test
    fun `outputText skips non-text items`() {
        val response = Response(
            id = "resp_3",
            model = "test",
            output = listOf(
                OutputItem.Text("Hello"),
                OutputItem.ToolCallItem(
                    ai.octomil.responses.ResponseToolCall(
                        id = "call_1",
                        name = "get_weather",
                        arguments = "{}",
                    )
                ),
                OutputItem.Text(" world"),
            ),
            finishReason = "stop",
        )

        assertEquals("Hello world", response.outputText)
    }

    // =========================================================================
    // OctomilNotInitializedError
    // =========================================================================

    @Test
    fun `OctomilNotInitializedError has correct message`() {
        val error = OctomilNotInitializedError()
        assertEquals(
            "Octomil client is not initialized. Call client.initialize() first.",
            error.message,
        )
    }

    @Test
    fun `OctomilNotInitializedError is an IllegalStateException`() {
        val error = OctomilNotInitializedError()
        assertTrue(error is IllegalStateException)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun stubRuntime(response: RuntimeResponse): ModelRuntime = object : ModelRuntime {
        override val capabilities = RuntimeCapabilities()
        override suspend fun run(request: RuntimeRequest) = response
        override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = flow {}
        override fun close() {}
    }
}
