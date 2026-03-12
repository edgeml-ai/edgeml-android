package ai.octomil.runtime.routing

import ai.octomil.runtime.core.ModelRuntime
import ai.octomil.runtime.core.RuntimeCapabilities
import ai.octomil.runtime.core.RuntimeChunk
import ai.octomil.runtime.core.RuntimeFactory
import ai.octomil.runtime.core.RuntimeRequest
import ai.octomil.runtime.core.RuntimeResponse
import kotlinx.coroutines.flow.Flow

/**
 * ADVANCED — MAY: Runtime router that selects between local and cloud runtimes
 * based on a [RoutingPolicy].
 *
 * The router evaluates the policy for each request and dispatches to the appropriate
 * runtime. With [RoutingPolicy.Auto], it prefers local inference and falls back to
 * cloud when no local runtime is available.
 */
internal class RouterModelRuntime(
    private val localFactory: RuntimeFactory? = null,
    private val cloudFactory: RuntimeFactory? = null,
    private val defaultPolicy: RoutingPolicy = RoutingPolicy.Auto(),
) : ModelRuntime {
    override val capabilities = RuntimeCapabilities(
        supportsToolCalls = true,
        supportsStreaming = true,
    )

    override suspend fun run(request: RuntimeRequest): RuntimeResponse {
        return selectRuntime(request).run(request)
    }

    override fun stream(request: RuntimeRequest): Flow<RuntimeChunk> {
        return selectRuntime(request).stream(request)
    }

    private fun selectRuntime(request: RuntimeRequest): ModelRuntime {
        val policy = defaultPolicy
        return when (policy) {
            is RoutingPolicy.LocalOnly -> {
                localFactory?.invoke("local")
                    ?: throw IllegalStateException("No local runtime available")
            }
            is RoutingPolicy.CloudOnly -> {
                cloudFactory?.invoke("cloud")
                    ?: throw IllegalStateException("No cloud runtime available")
            }
            is RoutingPolicy.Auto -> {
                val local = localFactory?.invoke("local")
                if (local != null) {
                    local
                } else if (policy.fallback == "cloud") {
                    cloudFactory?.invoke("cloud")
                        ?: throw IllegalStateException("No cloud runtime for fallback")
                } else {
                    throw IllegalStateException("No local runtime and fallback disabled")
                }
            }
        }
    }

    override fun close() {}
}
