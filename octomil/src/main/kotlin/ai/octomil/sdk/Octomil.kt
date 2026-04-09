package ai.octomil.sdk

import ai.octomil.client.EmbeddingClient
import ai.octomil.config.OctomilConfig
import ai.octomil.responses.InputItem
import ai.octomil.responses.OctomilResponses
import ai.octomil.responses.Response
import ai.octomil.responses.ResponseRequest
import ai.octomil.responses.ResponseStreamEvent
import ai.octomil.storage.SecureStorage
import android.content.Context
import kotlinx.coroutines.flow.Flow

class Octomil(
    private val context: Context,
    publishableKey: String? = null,
    apiKey: String? = null,
    orgId: String? = null,
    auth: AuthConfig? = null,
    private val serverUrl: String = OctomilConfig.DEFAULT_SERVER_URL,
) {
    private var initialized = false
    private val authConfig: AuthConfig
    private var _responses: OctomilResponses? = null
    private var _embeddings: FacadeEmbeddings? = null

    init {
        authConfig = when {
            auth != null -> auth
            publishableKey != null -> AuthConfig.PublishableKey(publishableKey)
            apiKey != null && orgId != null -> AuthConfig.OrgApiKey(apiKey, orgId)
            apiKey != null -> throw IllegalArgumentException(
                "orgId is required when using apiKey authentication"
            )
            else -> throw IllegalArgumentException(
                "One of publishableKey, apiKey + orgId, or auth must be provided"
            )
        }
    }

    suspend fun initialize() {
        if (initialized) return

        // Wire runtime registries (LLM, speech, etc.)
        ai.octomil.Octomil.init(context)

        // Wire auth into DeviceContext so OctomilResponses has credentials
        // for telemetry, cloud transport, and device registration.
        val appCtx = context.applicationContext
        val storage = SecureStorage.getInstance(appCtx)
        val installationId = DeviceContext.getOrCreateInstallationId(storage)
        val resolvedOrgId = when (authConfig) {
            is AuthConfig.PublishableKey -> null  // resolved server-side from key
            is AuthConfig.OrgApiKey -> authConfig.orgId
            is AuthConfig.BootstrapToken -> null
            is AuthConfig.Anonymous -> null
        }
        val appId = when (authConfig) {
            is AuthConfig.Anonymous -> authConfig.appId
            else -> null
        }
        val deviceContext = DeviceContext(
            installationId = installationId,
            orgId = resolvedOrgId,
            appId = appId,
        )
        DeviceContext.restoreCachedToken(deviceContext, storage)

        // Create OctomilResponses with the wired device context
        _responses = OctomilResponses(deviceContext = deviceContext)

        // Create EmbeddingClient for the embeddings namespace
        val embeddingApiKey = when (val a = authConfig) {
            is AuthConfig.OrgApiKey -> a.apiKey
            is AuthConfig.PublishableKey -> a.key
            is AuthConfig.BootstrapToken -> a.token
            is AuthConfig.Anonymous -> ""
        }
        _embeddings = FacadeEmbeddings(EmbeddingClient(serverUrl, embeddingApiKey))

        initialized = true
    }

    val responses: FacadeResponses
        get() {
            if (!initialized) throw OctomilNotInitializedError()
            return FacadeResponses(_responses!!)
        }

    val embeddings: FacadeEmbeddings
        get() {
            if (!initialized) throw OctomilNotInitializedError()
            return _embeddings!!
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
