package ai.octomil.sdk

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class RegistrationState {
    PENDING,
    REGISTERED,
    FAILED
}

sealed class TokenState {
    object None : TokenState()
    data class Valid(val accessToken: String, val expiresAt: Long) : TokenState()
    object Expired : TokenState()
}

class DeviceContext(
    val installationId: String,
    val orgId: String? = null,
    val appId: String? = null,
) {
    @Volatile var serverDeviceId: String? = null
        internal set

    private val _registrationState = MutableStateFlow(RegistrationState.PENDING)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _tokenState = MutableStateFlow<TokenState>(TokenState.None)
    val tokenState: StateFlow<TokenState> = _tokenState.asStateFlow()

    val isRegistered: Boolean
        get() = _registrationState.value == RegistrationState.REGISTERED

    fun authHeaders(): Map<String, String>? {
        val state = _tokenState.value
        return when (state) {
            is TokenState.Valid -> mapOf("Authorization" to "Bearer ${state.accessToken}")
            else -> null
        }
    }

    /**
     * Telemetry resource attributes keyed by installationId.
     * Available immediately — no auth token required.
     * The backend accepts pre-registration events keyed by installationId.
     */
    fun telemetryResource(): Map<String, String> {
        val resource = mutableMapOf(
            "device.id" to installationId,
            "platform" to "android",
        )
        orgId?.let { resource["org.id"] = it }
        appId?.let { resource["app.id"] = it }
        return resource
    }

    internal fun updateRegistered(serverDeviceId: String, accessToken: String, expiresAt: Long) {
        this.serverDeviceId = serverDeviceId
        _tokenState.value = TokenState.Valid(accessToken, expiresAt)
        _registrationState.value = RegistrationState.REGISTERED
    }

    internal fun updateToken(accessToken: String, expiresAt: Long) {
        _tokenState.value = TokenState.Valid(accessToken, expiresAt)
    }

    internal fun markFailed() {
        _registrationState.value = RegistrationState.FAILED
    }

    internal fun markTokenExpired() {
        _tokenState.value = TokenState.Expired
    }

    /**
     * Restore a cached token into the token state flow.
     * Registration state remains PENDING — revalidated via heartbeat/token freshness.
     */
    internal fun restoreCachedToken(accessToken: String, expiresAt: Long) {
        if (expiresAt > System.currentTimeMillis()) {
            _tokenState.value = TokenState.Valid(accessToken, expiresAt)
        } else {
            _tokenState.value = TokenState.Expired
        }
        // registrationState stays PENDING — never trust stale disk state
    }

    companion object {
        private const val INSTALLATION_ID_KEY = "octomil_installation_id"
        internal const val CACHED_TOKEN_KEY = "octomil_cached_access_token"
        internal const val CACHED_TOKEN_EXPIRES_KEY = "octomil_cached_token_expires_at"

        suspend fun getOrCreateInstallationId(
            storage: ai.octomil.storage.SecureStorage,
        ): String {
            val existing = storage.getString(INSTALLATION_ID_KEY)
            if (existing != null) return existing
            val id = UUID.randomUUID().toString()
            storage.putString(INSTALLATION_ID_KEY, id)
            return id
        }

        /**
         * Restore cached token from secure storage into a DeviceContext.
         * Registration state is NOT restored — always starts PENDING on relaunch.
         */
        suspend fun restoreCachedToken(
            context: DeviceContext,
            storage: ai.octomil.storage.SecureStorage,
        ) {
            val token = storage.getString(CACHED_TOKEN_KEY) ?: return
            val expiresAt = storage.getLong(CACHED_TOKEN_EXPIRES_KEY, 0L)
            context.restoreCachedToken(token, expiresAt)
        }
    }
}
