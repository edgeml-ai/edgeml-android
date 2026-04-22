package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class FallbackTriggerStage(val code: String) {
    POLICY("policy"),
    PREPARE("prepare"),
    DOWNLOAD("download"),
    VERIFY("verify"),
    LOAD("load"),
    BENCHMARK("benchmark"),
    GATE("gate"),
    INFERENCE("inference"),
    TIMEOUT("timeout"),
    NOT_APPLICABLE("not_applicable");

    companion object {
        fun fromCode(code: String): FallbackTriggerStage? =
            entries.firstOrNull { it.code == code }
    }
}
