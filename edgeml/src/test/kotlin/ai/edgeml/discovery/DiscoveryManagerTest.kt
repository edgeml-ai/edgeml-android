package ai.edgeml.discovery

import android.content.Context
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [DiscoveryManager].
 *
 * Validates the high-level discovery lifecycle by mocking the underlying
 * [NsdAdvertiser]. Tests cover:
 * - start/stop delegation to the advertiser
 * - isDiscoverable reflects advertiser state
 * - stop when not discoverable is a no-op
 */
class DiscoveryManagerTest {

    private lateinit var context: Context
    private lateinit var advertiser: NsdAdvertiser
    private lateinit var discoveryManager: DiscoveryManager

    @Before
    fun setUp() {
        context = mockk<Context>(relaxed = true)
        advertiser = mockk<NsdAdvertiser>(relaxed = true)
        discoveryManager = DiscoveryManager(context, advertiser)
    }

    // =========================================================================
    // isDiscoverable
    // =========================================================================

    @Test
    fun `isDiscoverable is false initially`() {
        every { advertiser.isAdvertising } returns false
        assertFalse(discoveryManager.isDiscoverable)
    }

    @Test
    fun `isDiscoverable reflects advertiser isAdvertising true`() {
        every { advertiser.isAdvertising } returns true
        assertTrue(discoveryManager.isDiscoverable)
    }

    @Test
    fun `isDiscoverable reflects advertiser isAdvertising false`() {
        every { advertiser.isAdvertising } returns false
        assertFalse(discoveryManager.isDiscoverable)
    }

    // =========================================================================
    // startDiscoverable
    // =========================================================================

    @Test
    fun `startDiscoverable delegates to advertiser startAdvertising`() {
        every { advertiser.startAdvertising(any(), any(), any()) } just Runs

        discoveryManager.startDiscoverable(deviceId = "test-device-id")

        verify { advertiser.startAdvertising(deviceId = "test-device-id", deviceName = any()) }
    }

    @Test
    fun `startDiscoverable passes custom device name`() {
        every { advertiser.startAdvertising(any(), any(), any()) } just Runs

        discoveryManager.startDiscoverable(
            deviceId = "test-device-id",
            deviceName = "My Pixel",
        )

        verify {
            advertiser.startAdvertising(
                deviceId = "test-device-id",
                deviceName = "My Pixel",
            )
        }
    }

    // =========================================================================
    // stopDiscoverable
    // =========================================================================

    @Test
    fun `stopDiscoverable delegates to advertiser stopAdvertising`() {
        every { advertiser.stopAdvertising() } just Runs

        discoveryManager.stopDiscoverable()

        verify { advertiser.stopAdvertising() }
    }

    @Test
    fun `stopDiscoverable when not discoverable is a no-op`() {
        every { advertiser.isAdvertising } returns false
        every { advertiser.stopAdvertising() } just Runs

        // Should not throw
        discoveryManager.stopDiscoverable()

        verify { advertiser.stopAdvertising() }
    }

    // =========================================================================
    // Full lifecycle
    // =========================================================================

    @Test
    fun `start then stop lifecycle`() {
        every { advertiser.startAdvertising(any(), any(), any()) } just Runs
        every { advertiser.stopAdvertising() } just Runs
        every { advertiser.isAdvertising } returnsMany listOf(false, true, false)

        assertFalse(discoveryManager.isDiscoverable)

        discoveryManager.startDiscoverable(deviceId = "dev-123")
        assertTrue(discoveryManager.isDiscoverable)

        discoveryManager.stopDiscoverable()
        assertFalse(discoveryManager.isDiscoverable)

        verify(exactly = 1) { advertiser.startAdvertising(any(), any(), any()) }
        verify(exactly = 1) { advertiser.stopAdvertising() }
    }
}
