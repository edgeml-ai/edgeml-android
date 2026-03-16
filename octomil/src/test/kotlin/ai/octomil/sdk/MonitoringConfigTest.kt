package ai.octomil.sdk

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MonitoringConfigTest {

    @Test
    fun `default enabled is false`() {
        val config = MonitoringConfig()
        assertFalse(config.enabled)
    }

    @Test
    fun `default heartbeatIntervalSeconds is 300`() {
        val config = MonitoringConfig()
        assertEquals(300L, config.heartbeatIntervalSeconds)
    }

    @Test
    fun `custom values are stored`() {
        val config = MonitoringConfig(enabled = true, heartbeatIntervalSeconds = 60)
        assertEquals(true, config.enabled)
        assertEquals(60L, config.heartbeatIntervalSeconds)
    }
}
