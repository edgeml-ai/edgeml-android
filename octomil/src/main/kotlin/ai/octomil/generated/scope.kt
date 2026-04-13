package ai.octomil.generated

// Auto-generated from octomil-contracts. Do not edit.

enum class Scope(val code: String) {
    CATALOG_READ("catalog:read"),
    MODELS_READ("models:read"),
    MODELS_WRITE("models:write"),
    DEVICES_REGISTER("devices:register"),
    DEVICES_HEARTBEAT("devices:heartbeat"),
    CONTROL_REFRESH("control:refresh"),
    TELEMETRY_WRITE("telemetry:write"),
    ROLLOUTS_READ("rollouts:read"),
    ROLLOUTS_WRITE("rollouts:write"),
    BENCHMARKS_WRITE("benchmarks:write"),
    EVALS_WRITE("evals:write"),
    CLOUD_INFERENCE("cloud:inference"),
    CLOUD_CREDENTIALS("cloud:credentials");

    companion object {
        fun fromCode(code: String): Scope? =
            entries.firstOrNull { it.code == code }
    }
}
