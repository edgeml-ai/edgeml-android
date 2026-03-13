// Auto-generated from octomil-contracts. Do not edit.
package ai.octomil.generated

enum class AuthType(val code: String) {
    ORG_API_KEY("org_api_key"),
    DEVICE_TOKEN("device_token"),
    SERVICE_TOKEN("service_token");

    companion object {
        fun fromCode(code: String): AuthType? =
            entries.firstOrNull { it.code == code }
    }
}
