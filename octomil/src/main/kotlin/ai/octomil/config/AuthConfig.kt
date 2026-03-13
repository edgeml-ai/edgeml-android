package ai.octomil.config

import ai.octomil.generated.AuthType

/**
 * Authentication configuration for the Octomil SDK.
 *
 * Use one of the sealed subclasses to configure how the SDK authenticates:
 *
 * ```kotlin
 * // Organization API key (for server-side, CLI, CI/CD)
 * val auth = AuthConfig.OrgApiKey(
 *     apiKey = "edg_...",
 *     orgId = "org_123",
 * )
 *
 * // Device token (for edge devices with bootstrap flow)
 * val auth = AuthConfig.DeviceToken(
 *     deviceId = "dev_abc",
 *     bootstrapToken = "jwt...",
 * )
 *
 * val config = OctomilConfig.Builder()
 *     .auth(auth)
 *     .modelId("model-123")
 *     .build()
 * ```
 */
sealed class AuthConfig {

    /** The bearer token used for API requests. */
    abstract val token: String

    /** The organization ID (empty for device token auth). */
    abstract val orgId: String

    /** The server URL. */
    abstract val serverUrl: String

    /** The auth type enum value. */
    abstract val authType: AuthType

    /**
     * Organization-scoped API key authentication.
     *
     * Used by server-side SDKs, CLI tools, and CI/CD pipelines.
     *
     * @property apiKey API key with `edg_` prefix.
     * @property orgId Organization identifier.
     * @property serverUrl Base URL of the Octomil server.
     */
    data class OrgApiKey(
        val apiKey: String,
        override val orgId: String,
        override val serverUrl: String = OctomilConfig.DEFAULT_SERVER_URL,
    ) : AuthConfig() {
        override val token: String get() = apiKey
        override val authType: AuthType get() = AuthType.ORG_API_KEY
    }

    /**
     * Short-lived device token authentication.
     *
     * Used by edge devices that go through a bootstrap/registration flow.
     *
     * @property deviceId Stable device identifier.
     * @property bootstrapToken Short-lived JWT from the bootstrap flow.
     * @property serverUrl Base URL of the Octomil server.
     */
    data class DeviceToken(
        val deviceId: String,
        val bootstrapToken: String,
        override val serverUrl: String = OctomilConfig.DEFAULT_SERVER_URL,
    ) : AuthConfig() {
        override val token: String get() = bootstrapToken
        override val orgId: String get() = ""
        override val authType: AuthType get() = AuthType.DEVICE_TOKEN
    }
}
