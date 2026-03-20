package ai.octomil.runtime.routing

import ai.octomil.generated.MessageRole
import ai.octomil.runtime.core.GenerationConfig
import ai.octomil.runtime.core.ModelRuntime
import ai.octomil.runtime.core.RuntimeCapabilities
import ai.octomil.runtime.core.RuntimeChunk
import ai.octomil.runtime.core.RuntimeContentPart
import ai.octomil.runtime.core.RuntimeMessage
import ai.octomil.runtime.core.RuntimeRequest
import ai.octomil.runtime.core.RuntimeResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterModelRuntimeTest {

    @Test
    fun `auto policy uses local when available`() = runTest {
        val localRuntime = stubRuntime("local-response")
        val cloudRuntime = stubRuntime("cloud-response")

        val router = RouterModelRuntime(
            localFactory = { _ -> localRuntime },
            cloudFactory = { _ -> cloudRuntime },
            defaultPolicy = RoutingPolicy.Auto(),
        )

        val response = router.run(stubRequest())
        assertEquals("local-response", response.text)
    }

    @Test
    fun `auto policy falls back to cloud`() = runTest {
        val cloudRuntime = stubRuntime("cloud-response")

        val router = RouterModelRuntime(
            localFactory = null,
            cloudFactory = { _ -> cloudRuntime },
            defaultPolicy = RoutingPolicy.Auto(fallback = "cloud"),
        )

        val response = router.run(stubRequest())
        assertEquals("cloud-response", response.text)
    }

    @Test(expected = ai.octomil.errors.OctomilException::class)
    fun `local only throws when no local runtime`() = runTest {
        val router = RouterModelRuntime(
            localFactory = null,
            cloudFactory = { _ -> stubRuntime("cloud") },
            defaultPolicy = RoutingPolicy.LocalOnly,
        )

        router.run(stubRequest())
    }

    @Test
    fun `cloud only uses cloud runtime`() = runTest {
        val localRuntime = stubRuntime("local-response")
        val cloudRuntime = stubRuntime("cloud-response")

        val router = RouterModelRuntime(
            localFactory = { _ -> localRuntime },
            cloudFactory = { _ -> cloudRuntime },
            defaultPolicy = RoutingPolicy.CloudOnly,
        )

        val response = router.run(stubRequest())
        assertEquals("cloud-response", response.text)
    }

    @Test
    fun `fromMetadata parses local_only policy`() {
        val metadata = mapOf("routing.policy" to "local_only")
        val policy = RoutingPolicy.fromMetadata(metadata)
        assertEquals(RoutingPolicy.LocalOnly, policy)
    }

    @Test
    fun `fromMetadata parses cloud_only policy`() {
        val metadata = mapOf("routing.policy" to "cloud_only")
        val policy = RoutingPolicy.fromMetadata(metadata)
        assertEquals(RoutingPolicy.CloudOnly, policy)
    }

    @Test
    fun `fromMetadata parses auto policy with options`() {
        val metadata = mapOf(
            "routing.policy" to "auto",
            "routing.prefer_local" to "false",
            "routing.max_latency_ms" to "500",
            "routing.fallback" to "none",
        )
        val policy = RoutingPolicy.fromMetadata(metadata) as RoutingPolicy.Auto
        assertEquals(false, policy.preferLocal)
        assertEquals(500, policy.maxLatencyMs)
        assertEquals("none", policy.fallback)
    }

    @Test
    fun `fromMetadata returns null for null metadata`() {
        assertNull(RoutingPolicy.fromMetadata(null))
    }

    @Test
    fun `fromMetadata returns null for unknown policy`() {
        val metadata = mapOf("routing.policy" to "unknown")
        assertNull(RoutingPolicy.fromMetadata(metadata))
    }

    @Test(expected = ai.octomil.errors.OctomilException::class)
    fun `auto policy throws when no local and fallback disabled`() = runTest {
        val router = RouterModelRuntime(
            localFactory = null,
            cloudFactory = { _ -> stubRuntime("cloud") },
            defaultPolicy = RoutingPolicy.Auto(fallback = "none"),
        )

        router.run(stubRequest())
    }

    private fun stubRuntime(text: String): ModelRuntime = object : ModelRuntime {
        override val capabilities = RuntimeCapabilities()
        override suspend fun run(request: RuntimeRequest) = RuntimeResponse(text = text)
        override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = emptyFlow()
        override fun close() {}
    }

    private fun stubRequest() = RuntimeRequest(
        messages = listOf(RuntimeMessage(role = MessageRole.USER, parts = listOf(RuntimeContentPart.Text("test prompt")))),
        generationConfig = GenerationConfig(maxTokens = 100, temperature = 0.7f),
    )
}
