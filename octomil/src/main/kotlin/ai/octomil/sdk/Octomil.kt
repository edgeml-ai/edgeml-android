package ai.octomil.sdk

import ai.octomil.responses.InputItem
import ai.octomil.responses.OctomilResponses
import ai.octomil.responses.Response
import ai.octomil.responses.ResponseRequest
import ai.octomil.responses.ResponseStreamEvent
import android.content.Context
import kotlinx.coroutines.flow.Flow

class Octomil(
    private val context: Context,
    publishableKey: String? = null,
    apiKey: String? = null,
    orgId: String? = null,
    auth: AuthConfig? = null,
) {
    private var initialized = false
    private val authConfig: AuthConfig

    init {
        authConfig = when {
            auth != null -> auth
            publishableKey != null -> AuthConfig.PublishableKey(publishableKey)
            apiKey != null -> AuthConfig.BootstrapToken(apiKey)
            else -> throw IllegalArgumentException(
                "One of publishableKey, apiKey, or auth must be provided"
            )
        }
    }

    suspend fun initialize() {
        if (initialized) return
        ai.octomil.Octomil.init(context)
        initialized = true
    }

    val responses: FacadeResponses
        get() {
            if (!initialized) throw OctomilNotInitializedError()
            return FacadeResponses(ai.octomil.Octomil.responses)
        }
}

class FacadeResponses(private val underlying: OctomilResponses) {

    suspend fun create(model: String, input: String): Response {
        val request = ResponseRequest(
            model = model,
            input = listOf(InputItem.text(input)),
        )
        return underlying.create(request)
    }

    fun stream(model: String, input: String): Flow<ResponseStreamEvent> {
        val request = ResponseRequest(
            model = model,
            input = listOf(InputItem.text(input)),
        )
        return underlying.stream(request)
    }

    suspend fun create(request: ResponseRequest): Response = underlying.create(request)

    fun stream(request: ResponseRequest): Flow<ResponseStreamEvent> = underlying.stream(request)
}
