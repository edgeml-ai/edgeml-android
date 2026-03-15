package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class DevicePlatform(val code: String) {
    IOS("ios"),
    ANDROID("android"),
    MACOS("macos"),
    LINUX("linux"),
    WINDOWS("windows"),
    BROWSER("browser");

    companion object {
        fun fromCode(code: String): DevicePlatform? =
            entries.firstOrNull { it.code == code }
    }
}
