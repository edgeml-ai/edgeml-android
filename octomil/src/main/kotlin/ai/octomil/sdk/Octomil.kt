package ai.octomil.sdk

import ai.octomil.audio.AudioSpeech
import ai.octomil.client.EmbeddingClient
import ai.octomil.config.OctomilConfig
import ai.octomil.generated.RoutingPolicy
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
    /**
     * Explicit planner routing override.
     *
     * - `null` (default): planner routing is ON when auth credentials exist,
     *   OFF otherwise.
     * - `true`: force planner routing ON.
     * - `false`: force planner routing OFF (direct/legacy routing only).
     *
     * Privacy invariant: "private" and "local_only" routing policies NEVER
     * route to cloud regardless of this setting.
     */
    plannerRouting: Boolean? = null,
) {
    private var initialized = false
    private val authConfig: AuthConfig
    internal val plannerEnabled: Boolean
    private var _responses: OctomilResponses? = null
    private var _embeddings: FacadeEmbeddings? = null
    private var _audio: FacadeAudio? = null

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

        plannerEnabled = PlannerRoutingDefaults.resolve(
            explicitOverride = plannerRouting,
            authConfig = authConfig,
            serverUrl = serverUrl,
        )
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

        // Create RuntimePlanner when planner routing is enabled
        val runtimePlanner = if (plannerEnabled) {
            val plannerApiKey = when (val a = authConfig) {
                is AuthConfig.OrgApiKey -> a.apiKey
                is AuthConfig.PublishableKey -> a.key
                is AuthConfig.BootstrapToken -> a.token
                is AuthConfig.Anonymous -> null
            }
            if (plannerApiKey != null) {
                val plannerClient = ai.octomil.runtime.planner.RuntimePlannerClient(
                    baseUrl = serverUrl,
                    apiKey = plannerApiKey,
                )
                ai.octomil.runtime.planner.RuntimePlanner(
                    context = appCtx,
                    client = plannerClient,
                )
            } else {
                null
            }
        } else {
            null
        }

        // Create OctomilResponses with the wired device context and planner
        _responses = OctomilResponses(
            deviceContext = deviceContext,
            runtimePlanner = runtimePlanner,
        )

        // Create EmbeddingClient for the embeddings namespace
        val embeddingApiKey = when (val a = authConfig) {
            is AuthConfig.OrgApiKey -> a.apiKey
            is AuthConfig.PublishableKey -> a.key
            is AuthConfig.BootstrapToken -> a.token
            is AuthConfig.Anonymous -> ""
        }
        _embeddings = FacadeEmbeddings(EmbeddingClient(serverUrl, embeddingApiKey))

        _audio = FacadeAudio(
            speechProvider = { ai.octomil.Octomil.audio.speech },
            transcriptionsProvider = { ai.octomil.Octomil.audio.transcriptions },
        )

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

    /**
     * Public audio surface. Mirrors the iOS `client.audio` namespace
     * with `audio.speech` and `audio.transcriptions` sub-resources.
     * Each sub-resource owns its own prepare / warmup / create
     * lifecycle and honors `app=` / `policy=` identity gates.
     */
    val audio: FacadeAudio
        get() {
            if (!initialized) throw OctomilNotInitializedError()
            return _audio!!
        }

    /**
     * Top-level warmup convenience. Walks the audio sub-resources
     * that need on-disk artifacts and warms each in turn so the
     * caller doesn't have to thread per-capability warmup itself.
     */
    suspend fun warmup(
        speechModel: String? = null,
        speechApp: String? = null,
        speechPolicy: RoutingPolicy? = null,
    ) {
        if (!initialized) throw OctomilNotInitializedError()
        if (speechModel != null) {
            _audio!!.speech.warmup(model = speechModel, app = speechApp, policy = speechPolicy)
        }
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

/**
 * Public audio facade. Holds the active [AudioSpeech] (delegating to
 * [TtsRuntimeRegistry]) and the existing [ai.octomil.audio.AudioTranscriptions]
 * resource. Both sub-resources own their own prepare/warmup state.
 */
class FacadeAudio internal constructor(
    speechProvider: () -> AudioSpeech,
    transcriptionsProvider: () -> ai.octomil.audio.AudioTranscriptions,
) {
    val speech: AudioSpeech by lazy { speechProvider() }
    val transcriptions: ai.octomil.audio.AudioTranscriptions by lazy { transcriptionsProvider() }
}
