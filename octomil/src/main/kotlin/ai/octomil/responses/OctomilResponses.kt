package ai.octomil.responses

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.MessageRole
import ai.octomil.manifest.ModelCatalogService
import ai.octomil.manifest.ModelRef
import ai.octomil.runtime.core.GenerationConfig
import ai.octomil.runtime.core.ModelRuntime
import ai.octomil.runtime.core.ModelRuntimeRegistry
import ai.octomil.runtime.core.RuntimeChunk
import ai.octomil.runtime.core.RuntimeContentPart
import ai.octomil.runtime.core.RuntimeMessage
import ai.octomil.runtime.core.RuntimeRequest
import ai.octomil.runtime.core.RuntimeResponse
import ai.octomil.runtime.core.RuntimeToolCall
import ai.octomil.runtime.core.RuntimeToolDef
import ai.octomil.runtime.core.RuntimeUsage
import ai.octomil.runtime.planner.RouteMetadata
import ai.octomil.runtime.planner.RuntimePlanner
import ai.octomil.runtime.planner.RuntimeCandidatePlan
import ai.octomil.runtime.planner.RuntimePlanResponse
import ai.octomil.runtime.routing.CandidateAttemptRunner
import ai.octomil.runtime.routing.FallbackTrigger
import ai.octomil.runtime.routing.ModelRefParser
import ai.octomil.runtime.routing.RequestRouter
import ai.octomil.runtime.routing.RequestRoutingContext
import ai.octomil.runtime.routing.RouteAttempt
import ai.octomil.runtime.routing.RouteEvent
import ai.octomil.runtime.routing.RoutingDecisionResult
import ai.octomil.runtime.routing.defaultOutputQualityEvaluators
import ai.octomil.sdk.DeviceContext
import ai.octomil.wrapper.TelemetryQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * Callback interface for route event telemetry.
 *
 * Implementations receive privacy-safe route events after every routed
 * request. NEVER receives prompt, input, output, audio, filePath, or content.
 */
fun interface RouteEventListener {
    fun onRouteEvent(event: RouteEvent)
}

class OctomilResponses(
    private val runtimeResolver: ((String) -> ModelRuntime?)? = null,
    private val catalogProvider: (() -> ModelCatalogService?)? = null,
    private val deviceContext: DeviceContext? = null,
    private val routeEventListener: RouteEventListener? = null,
    private val runtimePlanner: RuntimePlanner? = null,
) {
    private val responseCache = LinkedHashMap<String, Response>(100, 0.75f, true)
    private val requestRouter = RequestRouter()

    suspend fun create(request: ResponseRequest): Response {
        val effectiveRequest = applyConversationChaining(request)
        val runtimeRequest = buildRuntimeRequest(effectiveRequest)

        val routingContext = buildRoutingContext(request, streaming = false)
        val routed = requestRouter.resolveWithInference(
            context = routingContext,
            outputQualityEvaluators = defaultOutputQualityEvaluators(),
            candidatesForDecision = { decision ->
                buildProductionCandidates(decision, routingContext, request.model)
            },
        ) { _, attempt ->
            val selectedRuntime = resolveRuntimeForAttempt(request, attempt)
            selectedRuntime.run(runtimeRequest)
        }
        val routingDecision = routed.decision
        val attemptResult = routed.attemptResult

        val runtimeResponse = attemptResult.value ?: throw (
            attemptResult.error ?: OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "No runtime for model: ${request.model}",
            )
        )

        val routeMetadata = routingDecision.routeMetadata.copy(
            execution = routingDecision.routeMetadata.execution?.copy(
                locality = attemptResult.selectedAttempt?.locality ?: routingDecision.locality,
                mode = attemptResult.selectedAttempt?.mode ?: routingDecision.mode,
                engine = attemptResult.selectedAttempt?.engine ?: routingDecision.engine,
            ),
            fallback = routingDecision.routeMetadata.fallback.copy(
                used = attemptResult.fallbackUsed,
                trigger = attemptResult.fallbackTrigger,
            ),
            attempts = attemptResult.attempts,
        )

        val response = buildResponse(request.model, runtimeResponse, routeMetadata)
        responseCache[response.id] = response

        emitRouteEvent(routingDecision.copy(routeMetadata = routeMetadata), response.id, inferCapability(request))

        return response
    }

    fun stream(request: ResponseRequest): Flow<ResponseStreamEvent> = flow {
        val effectiveRequest = applyConversationChaining(request)
        val runtimeRequest = buildRuntimeRequest(effectiveRequest)

        val routingContext = buildRoutingContext(request, streaming = true)
        val routingDecision = requestRouter.resolve(routingContext)

        val candidates = buildProductionCandidates(routingDecision, routingContext, request.model)
        val attemptRunner = CandidateAttemptRunner(
            fallbackAllowed = RequestRouter.isFallbackAllowed(request.routing?.code),
            streaming = true,
        )
        val readiness = attemptRunner.run(candidates)
        val initialAttempt = readiness.selectedAttempt ?: run {
            throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "No runtime for model: ${request.model}",
            )
        }
        val responseId = generateId()
        val textParts = mutableListOf<String>()
        val toolCallBuffers = mutableMapOf<Int, ToolCallBuffer>()
        var lastUsage: RuntimeUsage? = null
        var chunkIndex = 0
        var firstOutputEmitted = false
        var fallbackUsed = false
        var fallbackTrigger = readiness.fallbackTrigger
        var selectedAttempt = initialAttempt
        val routeAttempts = readiness.attempts.toMutableList()

        try {
            collectRuntimeStream(
                runtime = resolveRuntimeForAttempt(request, selectedAttempt),
                runtimeRequest = runtimeRequest,
                model = request.model,
                textParts = textParts,
                toolCallBuffers = toolCallBuffers,
                chunkIndexProvider = { chunkIndex },
                chunkIndexConsumer = { chunkIndex = it },
                firstOutputConsumer = { firstOutputEmitted = true },
                usageConsumer = { lastUsage = it },
                emitEvent = { emit(it) },
            )
        } catch (error: Throwable) {
            if (!attemptRunner.shouldFallbackAfterInferenceError(firstOutputEmitted)) {
                throw error
            }
            val selectedIndex = selectedAttempt.index
            val fallbackCandidates = candidates.drop(selectedIndex + 1)
            if (fallbackCandidates.isEmpty()) throw error
            val fallbackReadiness = CandidateAttemptRunner(fallbackAllowed = false, streaming = true).run(fallbackCandidates)
            val fallbackAttempt = fallbackReadiness.selectedAttempt ?: throw error
            routeAttempts += fallbackReadiness.attempts
            fallbackUsed = true
            fallbackTrigger = fallbackTrigger ?: FallbackTrigger(
                code = "inference_error_before_first_output",
                stage = "inference",
                message = error.message ?: "stream failed before first output",
            )
            selectedAttempt = fallbackAttempt
            collectRuntimeStream(
                runtime = resolveRuntimeForAttempt(request, fallbackAttempt),
                runtimeRequest = runtimeRequest,
                model = request.model,
                textParts = textParts,
                toolCallBuffers = toolCallBuffers,
                chunkIndexProvider = { chunkIndex },
                chunkIndexConsumer = { chunkIndex = it },
                firstOutputConsumer = { firstOutputEmitted = true },
                usageConsumer = { lastUsage = it },
                emitEvent = { emit(it) },
            )
        }

        val output = mutableListOf<OutputItem>()
        val fullText = textParts.joinToString("")
        if (fullText.isNotEmpty()) {
            output.add(OutputItem.Text(fullText))
        }
        for ((_, buffer) in toolCallBuffers.entries.sortedBy { it.key }) {
            output.add(
                OutputItem.ToolCallItem(
                    ResponseToolCall(
                        id = buffer.id ?: generateId(),
                        name = buffer.name ?: "",
                        arguments = buffer.arguments.toString(),
                    )
                )
            )
        }

        val finishReason = if (toolCallBuffers.isNotEmpty()) "tool_calls" else "stop"
        val usage = lastUsage?.let {
            ResponseUsage(it.promptTokens, it.completionTokens, it.totalTokens)
        }

        val routeMetadata = routingDecision.routeMetadata.copy(
            execution = routingDecision.routeMetadata.execution?.copy(
                locality = selectedAttempt.locality,
                mode = selectedAttempt.mode,
                engine = selectedAttempt.engine,
            ),
            fallback = routingDecision.routeMetadata.fallback.copy(
                used = fallbackUsed || readiness.fallbackUsed,
                trigger = fallbackTrigger,
            ),
            attempts = routeAttempts,
        )

        val response = Response(
            id = responseId,
            model = request.model,
            output = output,
            finishReason = finishReason,
            usage = usage,
            routeMetadata = routeMetadata,
        )
        responseCache[response.id] = response

        emitRouteEvent(routingDecision.copy(routeMetadata = routeMetadata), response.id, inferCapability(request))

        emit(ResponseStreamEvent.Done(response))
    }

    private fun buildRoutingContext(request: ResponseRequest, streaming: Boolean): RequestRoutingContext {
        val policy = request.routing?.code ?: "local_first"
        val selection = runtimePlanner?.resolve(
            model = request.model,
            capability = inferCapability(request),
            routingPolicy = policy,
            allowNetwork = request.routing?.code != "private",
        )
        val plan = selection?.let {
            RuntimePlanResponse(
                model = request.model,
                capability = inferCapability(request),
                policy = policy,
                candidates = listOf(
                    RuntimeCandidatePlan(
                        locality = it.locality,
                        priority = 0,
                        confidence = 1.0,
                        reason = it.reason.ifEmpty { "runtime planner selection" },
                        engine = it.engine,
                        artifact = it.artifact,
                    )
                ),
                fallbackCandidates = it.fallbackCandidates,
                fallbackAllowed = RequestRouter.isFallbackAllowed(request.routing?.code),
                serverGeneratedAt = it.source,
            )
        }

        return RequestRoutingContext(
            model = request.model,
            capability = inferCapability(request),
            streaming = streaming,
            routingPolicy = request.routing?.code,
            cachedPlan = plan,
        )
    }

    private fun buildProductionCandidates(
        decision: RoutingDecisionResult,
        context: RequestRoutingContext,
        model: String,
    ): List<RuntimeCandidatePlan> =
        if (context.cachedPlan != null) {
            RequestRouter.candidatesFromPlan(context.cachedPlan)
        } else if (decision.attemptResult.attempts.isNotEmpty()) {
            decision.attemptResult.attempts.map {
                RuntimeCandidatePlan(
                    locality = it.locality,
                    priority = it.index,
                    confidence = 1.0,
                    reason = it.reason.message,
                    engine = it.engine,
                )
            }
        } else if (shouldPreferLocalRuntimeWithoutPlan(context.routingPolicy) && hasLocalRuntime(model)) {
            listOf(attemptCandidate(model, locality = "local", engine = "registered"))
        } else {
            listOf(attemptCandidate(model, decision.locality, decision.engine))
        }

    private fun attemptCandidate(model: String, locality: String = "local", engine: String? = "registered"): RuntimeCandidatePlan =
        RuntimeCandidatePlan(
            locality = locality,
            priority = 0,
            confidence = 1.0,
            reason = "registered model runtime",
            engine = engine,
        )

    private fun shouldPreferLocalRuntimeWithoutPlan(policy: String?): Boolean =
        when (policy) {
            "cloud_only", "cloud_first" -> false
            else -> true
        }

    private fun hasLocalRuntime(model: String): Boolean =
        runtimeResolver?.invoke(model) != null || ModelRuntimeRegistry.resolve(model) != null

    private fun resolveRuntimeForAttempt(request: ResponseRequest, attempt: RouteAttempt): ModelRuntime =
        resolveRuntime(request.model, request.modelRef)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ResponseStreamEvent>.collectRuntimeStream(
        runtime: ModelRuntime,
        runtimeRequest: RuntimeRequest,
        model: String,
        textParts: MutableList<String>,
        toolCallBuffers: MutableMap<Int, ToolCallBuffer>,
        chunkIndexProvider: () -> Int,
        chunkIndexConsumer: (Int) -> Unit,
        firstOutputConsumer: () -> Unit,
        usageConsumer: (RuntimeUsage) -> Unit,
        emitEvent: suspend (ResponseStreamEvent) -> Unit,
    ) {
        runtime.stream(runtimeRequest).collect { chunk ->
            chunk.text?.let { text ->
                firstOutputConsumer()
                textParts.add(text)
                emitEvent(ResponseStreamEvent.TextDelta(text))
            }

            chunk.toolCallDelta?.let { delta ->
                firstOutputConsumer()
                val buffer = toolCallBuffers.getOrPut(delta.index) { ToolCallBuffer() }
                if (delta.id != null) buffer.id = delta.id
                if (delta.name != null) buffer.name = delta.name
                if (delta.argumentsDelta != null) buffer.arguments.append(delta.argumentsDelta)
                emitEvent(
                    ResponseStreamEvent.ToolCallDelta(
                        index = delta.index,
                        id = delta.id,
                        name = delta.name,
                        argumentsDelta = delta.argumentsDelta,
                    )
                )
            }

            try {
                TelemetryQueue.shared?.reportInferenceChunkProduced(
                    modelId = model,
                    chunkIndex = chunkIndexProvider(),
                )
            } catch (_: Exception) {
                // Telemetry must never crash the streaming flow
            }
            chunkIndexConsumer(chunkIndexProvider() + 1)
            if (chunk.usage != null) usageConsumer(chunk.usage)
        }
    }

    private fun resolveRuntime(model: String, ref: ModelRef? = null): ModelRuntime {
        // 1. ModelRef via catalog (capability-based or ID-based)
        if (ref != null) {
            val catalog = catalogProvider?.invoke()
            if (catalog != null) {
                val runtime = catalog.runtimeForRef(ref)
                if (runtime != null) return runtime
            }
        }

        // 2. Custom resolver
        runtimeResolver?.invoke(model)?.let { return it }

        // 3. Capability lookup via catalog when model string is empty (capability-only request)
        if (model.isEmpty() && ref is ModelRef.Capability) {
            throw OctomilException(
                OctomilErrorCode.RUNTIME_UNAVAILABLE,
                "No runtime for capability ${ref.value}. Configure a model with this capability in AppManifest.",
            )
        }

        // 4. Registry lookup by model ID
        ModelRuntimeRegistry.resolve(model)?.let { return it }

        throw OctomilException(OctomilErrorCode.RUNTIME_UNAVAILABLE, "No ModelRuntime registered for model: $model")
    }

    private fun applyConversationChaining(request: ResponseRequest): ResponseRequest {
        if (request.previousResponseId == null) return request
        val previous = responseCache[request.previousResponseId] ?: return request

        val assistantText = previous.output
            .filterIsInstance<OutputItem.Text>()
            .joinToString("") { it.text }
        val assistantToolCalls = previous.output
            .filterIsInstance<OutputItem.ToolCallItem>()
            .map { it.toolCall }

        val assistantItem = InputItem.Assistant(
            content = if (assistantText.isNotEmpty()) listOf(ContentPart.Text(assistantText)) else null,
            toolCalls = assistantToolCalls.ifEmpty { null },
        )

        return request.copy(input = listOf(assistantItem) + request.input)
    }

    private fun buildRuntimeRequest(request: ResponseRequest): RuntimeRequest {
        val messages = mutableListOf<RuntimeMessage>()

        // Prepend instructions as system message
        request.instructions?.let {
            messages.add(RuntimeMessage(role = MessageRole.SYSTEM, parts = listOf(RuntimeContentPart.Text(it))))
        }

        for (item in request.input) {
            when (item) {
                is InputItem.System -> messages.add(RuntimeMessage(
                    role = MessageRole.SYSTEM,
                    parts = listOf(RuntimeContentPart.Text(item.content))
                ))
                is InputItem.User -> {
                    val parts = item.content.map { part ->
                        when (part) {
                            is ContentPart.Text -> RuntimeContentPart.Text(part.text)
                            is ContentPart.Image -> RuntimeContentPart.Image(
                                data = (part.data ?: "").toByteArray(),
                                mediaType = part.mediaType ?: "image/png",
                            )
                            is ContentPart.Audio -> RuntimeContentPart.Audio(
                                data = (part.data ?: "").toByteArray(),
                                mediaType = part.mediaType ?: "audio/wav",
                            )
                            is ContentPart.Video -> RuntimeContentPart.Video(
                                data = (part.data ?: "").toByteArray(),
                                mediaType = part.mediaType ?: "video/mp4",
                            )
                            is ContentPart.File -> {
                                val mt = part.mediaType.lowercase()
                                when {
                                    mt.startsWith("image/") -> RuntimeContentPart.Image(data = part.data.toByteArray(), mediaType = part.mediaType)
                                    mt.startsWith("audio/") -> RuntimeContentPart.Audio(data = part.data.toByteArray(), mediaType = part.mediaType)
                                    mt.startsWith("video/") -> RuntimeContentPart.Video(data = part.data.toByteArray(), mediaType = part.mediaType)
                                    else -> RuntimeContentPart.Text("[file: unsupported type ${part.mediaType}]")
                                }
                            }
                        }
                    }
                    messages.add(RuntimeMessage(role = MessageRole.USER, parts = parts))
                }
                is InputItem.Assistant -> {
                    val parts = mutableListOf<RuntimeContentPart>()
                    item.content?.forEach { p ->
                        if (p is ContentPart.Text) parts.add(RuntimeContentPart.Text(p.text))
                    }
                    item.toolCalls?.forEach { call ->
                        parts.add(RuntimeContentPart.Text("{\"tool_call\": {\"name\": \"${call.name}\", \"arguments\": ${call.arguments}}}"))
                    }
                    if (parts.isEmpty()) parts.add(RuntimeContentPart.Text(""))
                    messages.add(RuntimeMessage(role = MessageRole.ASSISTANT, parts = parts))
                }
                is InputItem.ToolResult -> messages.add(RuntimeMessage(
                    role = MessageRole.TOOL,
                    parts = listOf(RuntimeContentPart.Text(item.content))
                ))
            }
        }

        val toolDefs = if (request.tools.isNotEmpty()) {
            request.tools.map { tool ->
                RuntimeToolDef(
                    name = tool.function.name,
                    description = tool.function.description,
                    parametersSchema = tool.function.parameters?.toString(),
                )
            }
        } else {
            null
        }

        val jsonSchema = when (val fmt = request.responseFormat) {
            is ResponseFormat.JsonSchema -> fmt.schema
            is ResponseFormat.JsonObject -> "{}"
            is ResponseFormat.Text -> null
        }

        return RuntimeRequest(
            messages = messages,
            generationConfig = GenerationConfig(
                maxTokens = request.maxOutputTokens ?: 512,
                temperature = request.temperature ?: 0.7f,
                topP = request.topP ?: 1.0f,
                stop = request.stop,
            ),
            toolDefinitions = toolDefs,
            jsonSchema = jsonSchema,
        )
    }

    private fun buildResponse(
        model: String,
        runtimeResponse: RuntimeResponse,
        routeMetadata: RouteMetadata? = null,
    ): Response {
        val output = mutableListOf<OutputItem>()

        if (runtimeResponse.text.isNotEmpty()) {
            output.add(OutputItem.Text(runtimeResponse.text))
        }

        runtimeResponse.toolCalls?.forEach { call ->
            output.add(
                OutputItem.ToolCallItem(
                    ResponseToolCall(
                        id = call.id,
                        name = call.name,
                        arguments = call.arguments,
                    )
                )
            )
        }

        val finishReason = if (runtimeResponse.toolCalls?.isNotEmpty() == true) {
            "tool_calls"
        } else {
            runtimeResponse.finishReason
        }

        val usage = runtimeResponse.usage?.let {
            ResponseUsage(it.promptTokens, it.completionTokens, it.totalTokens)
        }

        return Response(
            id = generateId(),
            model = model,
            output = output,
            finishReason = finishReason,
            usage = usage,
            routeMetadata = routeMetadata,
        )
    }

    /**
     * Infer the capability from a request.
     *
     * Defaults to "chat" for most requests. Future: audio/embeddings
     * capabilities will be inferred from content parts.
     */
    private fun inferCapability(request: ResponseRequest): String {
        return "chat"
    }

    /**
     * Emit a privacy-safe route event.
     *
     * NEVER includes prompt, input, output, audio, filePath, or content.
     */
    private fun emitRouteEvent(
        decision: RoutingDecisionResult,
        responseId: String,
        capability: String,
    ) {
        try {
            val event = RouteEvent.from(decision, responseId, capability)
            routeEventListener?.onRouteEvent(event)
            TelemetryQueue.shared?.reportRouteEvent(event)
        } catch (_: Exception) {
            // Telemetry must never crash the request flow
        }
    }

    private fun generateId(): String =
        "resp_${UUID.randomUUID().toString().replace("-", "").take(16)}"

    private class ToolCallBuffer {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}
