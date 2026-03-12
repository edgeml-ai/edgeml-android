package ai.octomil.runtime.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ModelRuntimeRegistryTest {

    @After
    fun tearDown() {
        ModelRuntimeRegistry.clear()
    }

    @Test
    fun `resolve returns null when no runtimes registered`() {
        assertNull(ModelRuntimeRegistry.resolve("any-model"))
    }

    @Test
    fun `resolve exact family match`() {
        ModelRuntimeRegistry.register("phi-4-mini") { _ -> StubRuntime() }
        assertNotNull(ModelRuntimeRegistry.resolve("phi-4-mini"))
    }

    @Test
    fun `resolve prefix match`() {
        ModelRuntimeRegistry.register("phi") { _ -> StubRuntime() }
        assertNotNull(ModelRuntimeRegistry.resolve("phi-4-mini"))
    }

    @Test
    fun `resolve prefers exact over prefix`() {
        var usedExact = false
        ModelRuntimeRegistry.register("phi-4-mini") { _ ->
            usedExact = true
            StubRuntime()
        }
        ModelRuntimeRegistry.register("phi") { _ -> StubRuntime() }
        ModelRuntimeRegistry.resolve("phi-4-mini")
        assert(usedExact) { "Should prefer exact family match" }
    }

    @Test
    fun `resolve falls back to default factory`() {
        ModelRuntimeRegistry.defaultFactory = { _ -> StubRuntime() }
        assertNotNull(ModelRuntimeRegistry.resolve("unknown-model"))
    }

    @Test
    fun `resolve returns null when default is null and no match`() {
        ModelRuntimeRegistry.register("phi") { _ -> StubRuntime() }
        assertNull(ModelRuntimeRegistry.resolve("llama-3"))
    }

    @Test
    fun `resolve is case insensitive`() {
        ModelRuntimeRegistry.register("PHI") { _ -> StubRuntime() }
        assertNotNull(ModelRuntimeRegistry.resolve("phi-4-mini"))
    }

    @Test
    fun `clear removes all registrations`() {
        ModelRuntimeRegistry.register("phi") { _ -> StubRuntime() }
        ModelRuntimeRegistry.defaultFactory = { _ -> StubRuntime() }
        ModelRuntimeRegistry.clear()
        assertNull(ModelRuntimeRegistry.resolve("phi"))
    }

    private class StubRuntime : ModelRuntime {
        override val capabilities = RuntimeCapabilities()
        override suspend fun run(request: RuntimeRequest) = RuntimeResponse(text = "stub")
        override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = emptyFlow()
        override fun close() {}
    }
}
