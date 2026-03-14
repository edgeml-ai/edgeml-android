// Auto-generated from octomil-contracts. Do not edit.

enum class NetworkType(val code: String) {
    WIFI("wifi"),
    CELLULAR("cellular"),
    ETHERNET("ethernet"),
    NONE("none");

    companion object {
        fun fromCode(code: String): NetworkType? =
            entries.firstOrNull { it.code == code }
    }
}
