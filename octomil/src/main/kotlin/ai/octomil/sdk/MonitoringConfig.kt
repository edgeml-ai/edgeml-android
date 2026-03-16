package ai.octomil.sdk

data class MonitoringConfig(
    val enabled: Boolean = false,
    val heartbeatIntervalSeconds: Long = 300,
)
