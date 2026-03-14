// Auto-generated from octomil-contracts. Do not edit.

enum class DeviceModelStatus(val code: String) {
    NOT_ASSIGNED("not_assigned"),
    ASSIGNED("assigned"),
    DOWNLOADING("downloading"),
    DOWNLOAD_FAILED("download_failed"),
    VERIFYING("verifying"),
    READY("ready"),
    LOADING("loading"),
    LOAD_FAILED("load_failed"),
    ACTIVE("active"),
    FALLBACK_ACTIVE("fallback_active"),
    DEPRECATED_ASSIGNED("deprecated_assigned");

    companion object {
        fun fromCode(code: String): DeviceModelStatus? =
            entries.firstOrNull { it.code == code }
    }
}
