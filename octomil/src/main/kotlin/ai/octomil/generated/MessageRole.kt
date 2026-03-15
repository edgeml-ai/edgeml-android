// Auto-generated from octomil-contracts. Do not edit.

enum class MessageRole(val code: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    companion object {
        fun fromCode(code: String): MessageRole? =
            entries.firstOrNull { it.code == code }
    }
}
