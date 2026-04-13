package ai.octomil.manifest

import ai.octomil.generated.MessageRole
import ai.octomil.runtime.core.ModelRuntime
import ai.octomil.runtime.core.RuntimeCapabilities
import ai.octomil.runtime.core.RuntimeChunk
import ai.octomil.runtime.core.RuntimeContentPart
import ai.octomil.runtime.core.RuntimeMessage
import ai.octomil.runtime.core.RuntimeRequest
import ai.octomil.runtime.core.RuntimeResponse
import ai.octomil.runtime.routing.RoutingPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogRoutingTest {

    @Test
    fun `catalog AUTO preserves legacy local-first internal routing`() {
        val policy = catalogRoutingPolicy(ai.octomil.generated.RoutingPolicy.AUTO)

        assertEquals(
            RoutingPolicy.Auto(preferLocal = true, fallback = "cloud"),
            policy,
        )
    }

    @Test
    fun `catalog runtime keeps AUTO local-first when both runtimes exist`() = runTest {
        val runtime = buildCatalogRuntime(
            policy = ai.octomil.generated.RoutingPolicy.AUTO,
            localFactory = { _ -> StubRuntime("local-response") },
            cloudFactory = { _ -> StubRuntime("cloud-response") },
        )

        val response = runtime.run(
            RuntimeRequest(
                messages = listOf(
                    RuntimeMessage(
                        role = MessageRole.USER,
                        parts = listOf(RuntimeContentPart.Text("test")),
                    ),
                ),
            ),
        )

        assertEquals("local-response", response.text)
    }
}

private class StubRuntime(
    private val text: String,
) : ModelRuntime {
    override val capabilities: RuntimeCapabilities =
        RuntimeCapabilities(supportsToolCalls = true, supportsStreaming = true)

    override suspend fun run(request: RuntimeRequest): RuntimeResponse =
        RuntimeResponse(text = text)

    override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> =
        flowOf(RuntimeChunk(text = text))

    override fun close() {}
}
