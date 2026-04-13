package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class OauthProvider(val code: String) {
    GOOGLE("google"),
    APPLE("apple"),
    GITHUB("github"),
    MICROSOFT("microsoft"),
    OKTA("okta");

    companion object {
        fun fromCode(code: String): OauthProvider? =
            entries.firstOrNull { it.code == code }
    }
}
