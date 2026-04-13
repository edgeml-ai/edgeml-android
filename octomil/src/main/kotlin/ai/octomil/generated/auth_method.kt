package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class AuthMethod(val code: String) {
    PASSWORD("password"),
    PASSKEY("passkey"),
    OAUTH_GOOGLE("oauth_google"),
    OAUTH_APPLE("oauth_apple"),
    OAUTH_GITHUB("oauth_github"),
    SSO_SAML("sso_saml"),
    DEV_LOGIN("dev_login");

    companion object {
        fun fromCode(code: String): AuthMethod? =
            entries.firstOrNull { it.code == code }
    }
}
