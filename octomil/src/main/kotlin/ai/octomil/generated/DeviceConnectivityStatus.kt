package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class DeviceConnectivityStatus(val code: String) {
    ONLINE("online"),
    STALE("stale"),
    OFFLINE("offline"),
    REVOKED("revoked"),
    ERROR("error");

    companion object {
        fun fromCode(code: String): DeviceConnectivityStatus? =
            entries.firstOrNull { it.code == code }
    }
}
