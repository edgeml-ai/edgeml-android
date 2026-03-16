package ai.octomil.sdk

sealed class AuthConfig {
    data class PublishableKey(val key: String) : AuthConfig() {
        init {
            require(key.startsWith("oct_pub_test_") || key.startsWith("oct_pub_live_")) {
                "Publishable key must start with 'oct_pub_test_' or 'oct_pub_live_'"
            }
        }

        /** The environment scope extracted from the key prefix: "test" or "live". */
        val environment: String
            get() = when {
                key.startsWith("oct_pub_test_") -> "test"
                key.startsWith("oct_pub_live_") -> "live"
                else -> error("Invalid key prefix") // unreachable after init validation
            }
    }
    data class BootstrapToken(val token: String) : AuthConfig()
    data class Anonymous(val appId: String) : AuthConfig()
}
