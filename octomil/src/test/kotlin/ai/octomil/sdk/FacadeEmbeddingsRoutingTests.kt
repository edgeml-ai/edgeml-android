package ai.octomil.sdk

import ai.octomil.client.EmbeddingClient
import ai.octomil.client.EmbeddingResult
import ai.octomil.client.EmbeddingUsage
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RoutingPolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Lifecycle-parity tests for ``client.embeddings`` app/policy.
 *
 *   - embeddings/create — bare ``model="..."`` overload still works
 *     unchanged for non-app callers (regression guard).
 *   - embeddings/app_policy_routing — `@app/` parses, `app=`
 *     conflict refused, `policy=private`/`local_only` refused
 *     (no on-device embeddings backend on Android), and the
 *     resolved canonical model id is what the client sees.
 */
class FacadeEmbeddingsRoutingTests {

    private val sample = EmbeddingResult(
        embeddings = listOf(listOf(0.1, 0.2, 0.3)),
        model = "nomic-embed-text",
        usage = EmbeddingUsage(promptTokens = 5, totalTokens = 5),
    )

    @Test
    fun `bare model embeddings still works (regression)`() = runTest {
        val client: EmbeddingClient = mockk {
            every { embed("nomic-embed-text", input = "hello") } returns sample
        }
        val facade = FacadeEmbeddings(client)
        val result = facade.create("nomic-embed-text", "hello")
        assertEquals(sample, result)
        verify(exactly = 1) { client.embed("nomic-embed-text", input = "hello") }
    }

    @Test
    fun `@app prefix resolves canonical model id and passes through to client`() = runTest {
        val client: EmbeddingClient = mockk {
            every { embed("nomic-embed-text", input = "hello") } returns sample
        }
        val facade = FacadeEmbeddings(client)
        val result = facade.create(
            model = "@app/myapp/nomic-embed-text",
            input = "hello",
        )
        assertEquals(sample, result)
        // Canonical model id (capability slot from the @app/ ref)
        // is what the underlying client sees, not the raw @app/ ref.
        verify(exactly = 1) { client.embed("nomic-embed-text", input = "hello") }
    }

    @Test
    fun `@app and explicit app= mismatch is refused`() {
        val client: EmbeddingClient = mockk()
        val facade = FacadeEmbeddings(client)
        try {
            facade.resolveEmbeddingsRoute(
                model = "@app/myapp/nomic-embed-text",
                app = "different",
                policy = null,
            )
            fail("expected app identity mismatch refusal")
        } catch (e: OctomilException) {
            assertTrue(e.message?.contains("does not match") == true)
        }
    }

    @Test
    fun `policy=private refused on Android embeddings`() {
        val client: EmbeddingClient = mockk()
        val facade = FacadeEmbeddings(client)
        try {
            facade.resolveEmbeddingsRoute(
                model = "nomic-embed-text",
                app = null,
                policy = RoutingPolicy.PRIVATE,
            )
            fail("expected private policy refusal")
        } catch (e: OctomilException) {
            assertTrue(e.message?.contains("private") == true)
        }
    }

    @Test
    fun `policy=local_only refused on Android embeddings`() {
        val client: EmbeddingClient = mockk()
        val facade = FacadeEmbeddings(client)
        try {
            facade.resolveEmbeddingsRoute(
                model = "nomic-embed-text",
                app = null,
                policy = RoutingPolicy.LOCAL_ONLY,
            )
            fail("expected local_only refusal")
        } catch (e: OctomilException) {
            assertTrue(e.message?.contains("local_only") == true)
        }
    }

    @Test
    fun `app= argument scopes resolution without an @app prefix`() {
        val client: EmbeddingClient = mockk()
        val facade = FacadeEmbeddings(client)
        val resolved = facade.resolveEmbeddingsRoute(
            model = "nomic-embed-text",
            app = "myapp",
            policy = RoutingPolicy.LOCAL_FIRST,
        )
        assertEquals("nomic-embed-text", resolved.canonicalModel)
        assertEquals("myapp", resolved.appSlug)
        assertEquals(RoutingPolicy.LOCAL_FIRST, resolved.policy)
    }
}
