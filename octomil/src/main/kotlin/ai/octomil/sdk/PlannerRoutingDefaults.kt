package ai.octomil.sdk

/**
 * Determines the default planner routing behavior for the Android SDK.
 *
 * Planner routing is ON by default when client auth/config credentials exist,
 * enabling server-side runtime plan resolution. When credentials are absent,
 * planner routing defaults to OFF and requests use direct/legacy routing
 * (local manifest/offline assets).
 *
 * Escape hatch: set `plannerRouting = false` in the [Octomil] constructor
 * to explicitly disable planner routing regardless of credentials.
 *
 * Privacy invariant: when routing policy is "private" or "local_only",
 * requests NEVER route to cloud regardless of planner state.
 */
object PlannerRoutingDefaults {

    /**
     * Resolve whether planner routing should be enabled.
     *
     * @param explicitOverride Caller-provided override (from constructor). `null` means "use default".
     * @param authConfig The authentication configuration.
     * @param serverUrl The server URL being used.
     * @return `true` if planner routing should be active.
     */
    fun resolve(
        explicitOverride: Boolean?,
        authConfig: AuthConfig,
        serverUrl: String,
    ): Boolean {
        // Explicit override always wins
        if (explicitOverride != null) return explicitOverride

        // Default: ON when credentials exist that can reach a planner server
        return hasCredentials(authConfig, serverUrl)
    }

    /**
     * Whether the given auth config has credentials that can reach a planner.
     *
     * PublishableKey, OrgApiKey, and BootstrapToken all carry server-reachable
     * credentials. Anonymous does not.
     */
    private fun hasCredentials(authConfig: AuthConfig, serverUrl: String): Boolean {
        if (serverUrl.isBlank()) return false
        return when (authConfig) {
            is AuthConfig.PublishableKey -> true
            is AuthConfig.OrgApiKey -> authConfig.apiKey.isNotBlank()
            is AuthConfig.BootstrapToken -> authConfig.token.isNotBlank()
            is AuthConfig.Anonymous -> false
        }
    }

    /**
     * Validates that a routing policy does not violate privacy constraints.
     *
     * @param routingPolicy The requested routing policy (e.g. "private", "local_only").
     * @param plannerEnabled Whether planner routing is currently enabled.
     * @return The effective routing policy to use. "private" and "local_only"
     *         policies are preserved as-is and will block cloud routing
     *         regardless of planner state.
     */
    fun validatePolicy(routingPolicy: String?, plannerEnabled: Boolean): String {
        return routingPolicy ?: if (plannerEnabled) "auto" else "local_first"
    }

    /**
     * Whether the given routing policy MUST block cloud routing.
     *
     * This is the privacy hard-stop: "private" and "local_only" policies
     * NEVER route to cloud, regardless of planner state, credentials, or
     * server plan response.
     */
    fun isCloudBlocked(routingPolicy: String?): Boolean {
        return routingPolicy in listOf("private", "local_only")
    }
}
