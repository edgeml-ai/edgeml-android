package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class CloudProvider(val code: String) {
    OCTOMIL("octomil"),
    OPENAI("openai"),
    ANTHROPIC("anthropic"),
    GROQ("groq"),
    TOGETHER("together"),
    MOONSHOT("moonshot"),
    MINIMAX("minimax"),
    DEEPSEEK("deepseek"),
    UNKNOWN("unknown");

    companion object {
        fun fromCode(code: String): CloudProvider? =
            entries.firstOrNull { it.code == code }
    }
}
