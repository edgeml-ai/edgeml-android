package ai.octomil.responses

import ai.octomil.runtime.core.ModelRuntime
import ai.octomil.runtime.core.RuntimeCapabilities
import ai.octomil.runtime.core.RuntimeChunk
import ai.octomil.runtime.core.RuntimeRequest
import ai.octomil.runtime.core.RuntimeResponse
import ai.octomil.runtime.core.RuntimeUsage
import ai.octomil.runtime.routing.RouteEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Production-path integration tests.
 *
 * These exercise the full public request path (OctomilResponses.create/stream)
 * end-to-end to verify:
 * 1. Route metadata is present on every response
 * 2. Route events are emitted (privacy-safe, no content)
 * 3. Streaming fallback lockout (no fallback after first output)
 * 4. Deployment/experiment ref kinds recorded correctly
 */
class ProductionRoutingIntegrationTest {

    // =========================================================================
    // 1. Route metadata on non-streaming responses
    // =========================================================================

    @Test
    fun `create attaches route metadata to response`() = runTest {
        val runtime = stubRuntime(RuntimeResponse(text = "Hello", finishReason = "stop"))
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val response = responses.create(
            ResponseRequest(model = "gemma-2b", input = listOf(InputItem.text("Hi")))
        )

        assertNotNull(response.routeMetadata)
        assertNotNull(response.routeMetadata!!.execution)
        assertEquals("local", response.routeMetadata!!.execution!!.locality)
        assertEquals("sdk_runtime", response.routeMetadata!!.execution!!.mode)
        assertEquals("gemma-2b", response.routeMetadata!!.model.requested.ref)
        assertEquals("model", response.routeMetadata!!.model.requested.kind)
    }

    @Test
    fun `create with deployment ref records deployment kind in metadata`() = runTest {
        val runtime = stubRuntime(RuntimeResponse(text = "result", finishReason = "stop"))
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val response = responses.create(
            ResponseRequest(model = "deploy_abc123", input = listOf(InputItem.text("query")))
        )

        assertNotNull(response.routeMetadata)
        assertEquals("deployment", response.routeMetadata!!.model.requested.kind)
        assertEquals("deploy_abc123", response.routeMetadata!!.model.requested.ref)
    }

    @Test
    fun `create with experiment ref records experiment kind in metadata`() = runTest {
        val runtime = stubRuntime(RuntimeResponse(text = "result", finishReason = "stop"))
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val response = responses.create(
            ResponseRequest(model = "exp_v1/variant_a", input = listOf(InputItem.text("query")))
        )

        assertNotNull(response.routeMetadata)
        assertEquals("experiment", response.routeMetadata!!.model.requested.kind)
    }

    @Test
    fun `create with app ref records app kind in metadata`() = runTest {
        val runtime = stubRuntime(RuntimeResponse(text = "result", finishReason = "stop"))
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val response = responses.create(
            ResponseRequest(model = "@app/my-app/chat", input = listOf(InputItem.text("query")))
        )

        assertNotNull(response.routeMetadata)
        assertEquals("app", response.routeMetadata!!.model.requested.kind)
        assertEquals("@app/my-app/chat", response.routeMetadata!!.model.requested.ref)
    }

    // =========================================================================
    // 2. Route events emitted
    // =========================================================================

    @Test
    fun `create emits route event via listener`() = runTest {
        val events = mutableListOf<RouteEvent>()
        val runtime = stubRuntime(RuntimeResponse(text = "Hello", finishReason = "stop"))
        val responses = OctomilResponses(
            runtimeResolver = { runtime },
            routeEventListener = RouteEventListener { events.add(it) },
        )

        responses.create(
            ResponseRequest(model = "gemma-2b", input = listOf(InputItem.text("Hi")))
        )

        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("chat", event.capability)
        assertEquals("model", event.modelRefKind)
        assertFalse(event.fallbackUsed)
    }

    @Test
    fun `stream emits route event via listener`() = runTest {
        val events = mutableListOf<RouteEvent>()
        val runtime = streamingRuntime(listOf(RuntimeChunk(text = "Hello")))
        val responses = OctomilResponses(
            runtimeResolver = { runtime },
            routeEventListener = RouteEventListener { events.add(it) },
        )

        responses.stream(
            ResponseRequest(model = "gemma-2b", input = listOf(InputItem.text("Hi")))
        ).toList()

        assertEquals(1, events.size)
        assertEquals("chat", events.first().capability)
    }

    @Test
    fun `route event never contains prompt or output content`() = runTest {
        val events = mutableListOf<RouteEvent>()
        val runtime = stubRuntime(RuntimeResponse(text = "SECRET_OUTPUT", finishReason = "stop"))
        val responses = OctomilResponses(
            runtimeResolver = { runtime },
            routeEventListener = RouteEventListener { events.add(it) },
        )

        responses.create(
            ResponseRequest(model = "gemma-2b", input = listOf(InputItem.text("SECRET_PROMPT")))
        )

        assertEquals(1, events.size)
        val eventStr = events.first().toString()
        assertFalse(eventStr.contains("SECRET_PROMPT"), "Route event must not contain prompt")
        assertFalse(eventStr.contains("SECRET_OUTPUT"), "Route event must not contain output")
    }

    // =========================================================================
    // 3. Streaming route metadata
    // =========================================================================

    @Test
    fun `stream attaches route metadata to final response`() = runTest {
        val runtime = streamingRuntime(listOf(
            RuntimeChunk(text = "Hello"),
            RuntimeChunk(text = " world"),
        ))
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val events = responses.stream(
            ResponseRequest(model = "gemma-2b", input = listOf(InputItem.text("Hi")))
        ).toList()

        val done = events.filterIsInstance<ResponseStreamEvent.Done>().first()
        assertNotNull(done.response.routeMetadata)
        assertEquals("local", done.response.routeMetadata!!.execution!!.locality)
        assertEquals("sdk_runtime", done.response.routeMetadata!!.execution!!.mode)
    }

    // =========================================================================
    // 4. Streaming fallback lockout (tested via CandidateAttemptRunner)
    // =========================================================================

    @Test
    fun `streaming fallback lockout - no fallback after first token`() = runTest {
        // This test validates the rule: once first output token is emitted
        // in a streaming request, no fallback is allowed.
        val runner = ai.octomil.runtime.routing.CandidateAttemptRunner(
            fallbackAllowed = true,
            streaming = true,
        )

        var outputEmitted = false
        val result = runner.runWithInference(
            candidates = listOf(
                ai.octomil.runtime.planner.RuntimeCandidatePlan(
                    locality = "local", priority = 0, confidence = 1.0,
                    reason = "local", engine = "litert",
                ),
                ai.octomil.runtime.planner.RuntimeCandidatePlan(
                    locality = "cloud", priority = 1, confidence = 0.9,
                    reason = "cloud", engine = "cloud",
                ),
            ),
            firstOutputEmitted = { outputEmitted },
        ) { _, _ ->
            outputEmitted = true  // Simulate first token emitted
            throw RuntimeException("stream error after first token")
        }

        // Should NOT have fallen back to cloud candidate
        assertFalse(result.fallbackUsed)
        assertEquals(1, result.attempts.size)
        assertEquals("inference_error_after_first_output", result.attempts.first().reason.code)
    }

    @Test
    fun `streaming fallback allowed before first token`() = runTest {
        val runner = ai.octomil.runtime.routing.CandidateAttemptRunner(
            fallbackAllowed = true,
            streaming = true,
        )

        val result = runner.runWithInference(
            candidates = listOf(
                ai.octomil.runtime.planner.RuntimeCandidatePlan(
                    locality = "local", priority = 0, confidence = 1.0,
                    reason = "local", engine = "litert",
                ),
                ai.octomil.runtime.planner.RuntimeCandidatePlan(
                    locality = "cloud", priority = 1, confidence = 0.9,
                    reason = "cloud", engine = "cloud",
                ),
            ),
            firstOutputEmitted = { false },  // No output emitted yet
        ) { candidate, _ ->
            if (candidate.locality == "local") {
                throw RuntimeException("model load failed before any output")
            }
            "cloud response"
        }

        // SHOULD have fallen back to cloud
        assertTrue(result.fallbackUsed)
        assertEquals("cloud response", result.value)
        assertEquals("cloud", result.selectedAttempt?.locality)
    }

    // =========================================================================
    // 5. Contract conformance for deployment/experiment refs
    // =========================================================================

    @Test
    fun `deployment ref kind is deployment`() {
        val ref = ai.octomil.runtime.routing.ModelRefParser.parse("deploy_abc123")
        assertEquals("deployment", ref.kind)
        assertEquals("deploy_abc123", ref.ref)
    }

    @Test
    fun `experiment ref kind is experiment`() {
        val ref = ai.octomil.runtime.routing.ModelRefParser.parse("exp_v1/variant_a")
        assertEquals("experiment", ref.kind)
        assertEquals("exp_v1/variant_a", ref.ref)
    }

    @Test
    fun `app ref kind is app`() {
        val ref = ai.octomil.runtime.routing.ModelRefParser.parse("@app/my-app/chat")
        assertEquals("app", ref.kind)
        assertEquals("@app/my-app/chat", ref.ref)
    }

    @Test
    fun `capability ref kind is capability`() {
        val ref = ai.octomil.runtime.routing.ModelRefParser.parse("@capability/embeddings")
        assertEquals("capability", ref.kind)
        assertEquals("@capability/embeddings", ref.ref)
    }

    @Test
    fun `plain model ref kind is model`() {
        val ref = ai.octomil.runtime.routing.ModelRefParser.parse("gemma-2b")
        assertEquals("model", ref.kind)
        assertEquals("gemma-2b", ref.ref)
    }

    // =========================================================================
    // 6. Private policy enforcement
    // =========================================================================

    @Test
    fun `private routing policy does not use cloud fallback`() = runTest {
        val runner = ai.octomil.runtime.routing.CandidateAttemptRunner(
            fallbackAllowed = false,
        )
        val result = runner.run(
            candidates = listOf(
                ai.octomil.runtime.planner.RuntimeCandidatePlan(
                    locality = "local", priority = 0, confidence = 1.0,
                    reason = "private policy", engine = "litert",
                ),
            ),
        )
        // With private policy, fallback should not be allowed
        assertFalse(result.fallbackUsed)
    }

    @Test
    fun `isFallbackAllowed returns false for private and local_only policies`() {
        assertFalse(ai.octomil.runtime.routing.RequestRouter.isFallbackAllowed("private"))
        assertFalse(ai.octomil.runtime.routing.RequestRouter.isFallbackAllowed("local_only"))
        assertTrue(ai.octomil.runtime.routing.RequestRouter.isFallbackAllowed("local_first"))
        assertTrue(ai.octomil.runtime.routing.RequestRouter.isFallbackAllowed("cloud_first"))
        assertTrue(ai.octomil.runtime.routing.RequestRouter.isFallbackAllowed("cloud_only"))
        assertTrue(ai.octomil.runtime.routing.RequestRouter.isFallbackAllowed(null))
    }

    // =========================================================================
    // 7. local_first fallback: local fails then cloud
    // =========================================================================

    @Test
    fun `local_first fallback - local fails then cloud succeeds`() = runTest {
        val runner = ai.octomil.runtime.routing.CandidateAttemptRunner(
            fallbackAllowed = true,
        )
        val result = runner.runWithInference(
            candidates = listOf(
                ai.octomil.runtime.planner.RuntimeCandidatePlan(
                    locality = "local", priority = 0, confidence = 1.0,
                    reason = "local", engine = "litert",
                ),
                ai.octomil.runtime.planner.RuntimeCandidatePlan(
                    locality = "cloud", priority = 1, confidence = 0.9,
                    reason = "cloud", engine = "cloud",
                ),
            ),
        ) { candidate, _ ->
            if (candidate.locality == "local") {
                throw RuntimeException("model not loaded")
            }
            "cloud response"
        }

        assertTrue(result.fallbackUsed)
        assertEquals("cloud response", result.value)
        assertEquals("cloud", result.selectedAttempt?.locality)
        assertEquals(2, result.attempts.size)
    }

    // =========================================================================
    // 8. cloud_first behavior
    // =========================================================================

    @Test
    fun `cloud_first policy selects cloud as primary`() {
        val context = ai.octomil.runtime.routing.RequestRoutingContext(
            model = "gemma-2b",
            routingPolicy = "cloud_first",
        )
        val router = ai.octomil.runtime.routing.RequestRouter()
        val decision = router.resolve(context)

        // Without a plan, cloud_first falls through to hosted gateway
        assertEquals("cloud", decision.locality)
        assertEquals("hosted_gateway", decision.mode)
    }

    // =========================================================================
    // 9. Capability ref via model parser (covered above in section 4)
    // =========================================================================

    // =========================================================================
    // 10. Route metadata present on streamed responses with all ref types
    // =========================================================================

    @Test
    fun `stream with deployment ref attaches correct model ref kind`() = runTest {
        val runtime = streamingRuntime(listOf(RuntimeChunk(text = "result")))
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val events = responses.stream(
            ResponseRequest(model = "deploy_xyz789", input = listOf(InputItem.text("query")))
        ).toList()

        val done = events.filterIsInstance<ResponseStreamEvent.Done>().first()
        assertNotNull(done.response.routeMetadata)
        assertEquals("deployment", done.response.routeMetadata!!.model.requested.kind)
        assertEquals("deploy_xyz789", done.response.routeMetadata!!.model.requested.ref)
    }

    @Test
    fun `stream with app ref attaches correct model ref kind`() = runTest {
        val runtime = streamingRuntime(listOf(RuntimeChunk(text = "result")))
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val events = responses.stream(
            ResponseRequest(model = "@app/my-app/chat", input = listOf(InputItem.text("query")))
        ).toList()

        val done = events.filterIsInstance<ResponseStreamEvent.Done>().first()
        assertNotNull(done.response.routeMetadata)
        assertEquals("app", done.response.routeMetadata!!.model.requested.kind)
    }

    @Test
    fun `stream with experiment ref attaches correct model ref kind`() = runTest {
        val runtime = streamingRuntime(listOf(RuntimeChunk(text = "result")))
        val responses = OctomilResponses(runtimeResolver = { runtime })

        val events = responses.stream(
            ResponseRequest(model = "exp_abc/variant_b", input = listOf(InputItem.text("query")))
        ).toList()

        val done = events.filterIsInstance<ResponseStreamEvent.Done>().first()
        assertNotNull(done.response.routeMetadata)
        assertEquals("experiment", done.response.routeMetadata!!.model.requested.kind)
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

    private fun streamingRuntime(chunks: List<RuntimeChunk>): ModelRuntime = object : ModelRuntime {
        override val capabilities = RuntimeCapabilities()
        override suspend fun run(request: RuntimeRequest) = RuntimeResponse(text = "")
        override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> = flow {
            chunks.forEach { emit(it) }
        }
        override fun close() {}
    }
}
