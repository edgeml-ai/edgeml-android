package ai.octomil.sdk

sealed class AuthConfig {
    data class PublishableKey(val key: String) : AuthConfig() {
        init {
            require(key.startsWith("oct_pub_")) { "Publishable key must start with 'oct_pub_'" }
        }
    }
    data class BootstrapToken(val token: String) : AuthConfig()
    data class Anonymous(val appId: String) : AuthConfig()
}
