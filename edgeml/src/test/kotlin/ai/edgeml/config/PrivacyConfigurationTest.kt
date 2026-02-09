package ai.edgeml.config

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyConfigurationTest {

    @Test
    fun `default configuration has staggered updates enabled`() {
        val config = PrivacyConfiguration.DEFAULT
        assertTrue(config.enableStaggeredUpdates)
        assertFalse(config.enableDifferentialPrivacy)
        assertEquals(0L, config.minUploadDelayMs)
        assertEquals(300_000L, config.maxUploadDelayMs)
    }

    @Test
    fun `high privacy configuration has DP enabled with strong epsilon`() {
        val config = PrivacyConfiguration.HIGH_PRIVACY
        assertTrue(config.enableStaggeredUpdates)
        assertTrue(config.enableDifferentialPrivacy)
        assertEquals(0.5, config.dpEpsilon)
        assertEquals(60_000L, config.minUploadDelayMs)
        assertEquals(600_000L, config.maxUploadDelayMs)
    }

    @Test
    fun `disabled configuration has no privacy enhancements`() {
        val config = PrivacyConfiguration.DISABLED
        assertFalse(config.enableStaggeredUpdates)
        assertFalse(config.enableDifferentialPrivacy)
        assertEquals(0L, config.minUploadDelayMs)
        assertEquals(0L, config.maxUploadDelayMs)
    }

    @Test
    fun `moderate configuration has balanced settings`() {
        val config = PrivacyConfiguration.MODERATE
        assertTrue(config.enableStaggeredUpdates)
        assertTrue(config.enableDifferentialPrivacy)
        assertEquals(1.0, config.dpEpsilon)
        assertEquals(0L, config.minUploadDelayMs)
        assertEquals(300_000L, config.maxUploadDelayMs)
    }

    @Test
    fun `randomUploadDelay returns zero when staggered updates disabled`() {
        val config = PrivacyConfiguration(enableStaggeredUpdates = false)
        assertEquals(0L, config.randomUploadDelay())
    }

    @Test
    fun `randomUploadDelay returns value within configured range`() {
        val config = PrivacyConfiguration(
            enableStaggeredUpdates = true,
            minUploadDelayMs = 100,
            maxUploadDelayMs = 200,
        )

        repeat(100) {
            val delay = config.randomUploadDelay()
            assertTrue(delay in 100..200, "Delay $delay should be in [100, 200]")
        }
    }

    @Test
    fun `randomUploadDelay returns exact min when range is zero`() {
        val config = PrivacyConfiguration(
            enableStaggeredUpdates = true,
            minUploadDelayMs = 500,
            maxUploadDelayMs = 500,
        )

        assertEquals(500L, config.randomUploadDelay())
    }

    @Test
    fun `custom configuration preserves all values`() {
        val config = PrivacyConfiguration(
            enableStaggeredUpdates = false,
            minUploadDelayMs = 42,
            maxUploadDelayMs = 9999,
            enableDifferentialPrivacy = true,
            dpEpsilon = 3.14,
            dpClippingNorm = 2.5,
        )

        assertFalse(config.enableStaggeredUpdates)
        assertEquals(42L, config.minUploadDelayMs)
        assertEquals(9999L, config.maxUploadDelayMs)
        assertTrue(config.enableDifferentialPrivacy)
        assertEquals(3.14, config.dpEpsilon)
        assertEquals(2.5, config.dpClippingNorm)
    }

    @Test
    fun `default constructor uses expected defaults`() {
        val config = PrivacyConfiguration()
        assertTrue(config.enableStaggeredUpdates)
        assertEquals(0L, config.minUploadDelayMs)
        assertEquals(300_000L, config.maxUploadDelayMs)
        assertFalse(config.enableDifferentialPrivacy)
        assertEquals(1.0, config.dpEpsilon)
        assertEquals(1.0, config.dpClippingNorm)
    }
}
