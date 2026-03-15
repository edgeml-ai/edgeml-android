package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class PrincipalType(val code: String) {
    USER("user"),
    ORG_API_CLIENT("org_api_client"),
    DEVICE("device"),
    SERVICE_WORKER("service_worker");

    companion object {
        fun fromCode(code: String): PrincipalType? =
            entries.firstOrNull { it.code == code }
    }
}
