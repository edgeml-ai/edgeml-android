// Auto-generated from octomil-contracts. Do not edit.
package ai.octomil.generated

enum class DeviceClass(val code: String) {
    FLAGSHIP("flagship"),
    HIGH("high"),
    MID("mid"),
    LOW("low");

    companion object {
        fun fromCode(code: String): DeviceClass? =
            entries.firstOrNull { it.code == code }
    }
}
