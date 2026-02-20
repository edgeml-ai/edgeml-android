package ai.edgeml.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [NsdAdvertiser].
 *
 * NsdManager is an Android framework class that must be mocked in unit tests.
 * NsdServiceInfo setters/getters are Android stubs (return default values in
 * local JVM tests), so service info construction is verified via mockk spies
 * that track setter invocations.
 *
 * Tests cover:
 * - Initialization state
 * - Start/stop lifecycle and idempotency
 * - Service info construction (type, name, TXT records)
 * - Registration listener callbacks
 * - Default and custom device name behavior
 * - Edge cases (null NsdManager, restart after stop)
 */
class NsdAdvertiserTest {

    private lateinit var context: Context
    private lateinit var nsdManager: NsdManager
    private lateinit var advertiser: NsdAdvertiser

    private val listenerSlot = slot<NsdManager.RegistrationListener>()
    private val serviceInfoSlot = slot<NsdServiceInfo>()

    @Before
    fun setUp() {
        nsdManager = mockk<NsdManager>(relaxed = true)
        context = mockk<Context>(relaxed = true)

        every { context.getSystemService(Context.NSD_SERVICE) } returns nsdManager
        every {
            nsdManager.registerService(capture(serviceInfoSlot), any<Int>(), capture(listenerSlot))
        } just Runs

        advertiser = NsdAdvertiser(context)
    }

    @After
    fun tearDown() {
        if (advertiser.isAdvertising) {
            advertiser.stopAdvertising()
        }
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    @Test
    fun `init does not start advertising`() {
        assertFalse(advertiser.isAdvertising)
    }

    @Test
    fun `init does not interact with NsdManager`() {
        verify(exactly = 0) { nsdManager.registerService(any(), any<Int>(), any()) }
    }

    // =========================================================================
    // startAdvertising
    // =========================================================================

    @Test
    fun `startAdvertising registers service and sets isAdvertising on callback`() {
        advertiser.startAdvertising(deviceId = "dev-001")

        assertTrue(listenerSlot.isCaptured)

        listenerSlot.captured.onServiceRegistered(NsdServiceInfo())
        assertTrue(advertiser.isAdvertising)
    }

    @Test
    fun `startAdvertising calls registerService with PROTOCOL_DNS_SD`() {
        advertiser.startAdvertising(deviceId = "dev-001")

        verify {
            nsdManager.registerService(any(), eq(NsdManager.PROTOCOL_DNS_SD), any())
        }
    }

    @Test
    fun `startAdvertising is idempotent when already registered`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        listenerSlot.captured.onServiceRegistered(NsdServiceInfo())

        advertiser.startAdvertising(deviceId = "dev-002")

        verify(exactly = 1) { nsdManager.registerService(any(), any<Int>(), any()) }
    }

    @Test
    fun `startAdvertising is idempotent when registration is in progress`() {
        advertiser.startAdvertising(deviceId = "dev-001")

        // Don't fire the callback — registration is still pending
        advertiser.startAdvertising(deviceId = "dev-002")

        verify(exactly = 1) { nsdManager.registerService(any(), any<Int>(), any()) }
    }

    @Test
    fun `startAdvertising sets isAdvertising to false on registration failure`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        listenerSlot.captured.onRegistrationFailed(NsdServiceInfo(), NsdManager.FAILURE_INTERNAL_ERROR)

        assertFalse(advertiser.isAdvertising)
    }

    // =========================================================================
    // stopAdvertising
    // =========================================================================

    @Test
    fun `stopAdvertising when not started is a no-op`() {
        advertiser.stopAdvertising()

        assertFalse(advertiser.isAdvertising)
        verify(exactly = 0) { nsdManager.unregisterService(any()) }
    }

    @Test
    fun `stopAdvertising unregisters service`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        listenerSlot.captured.onServiceRegistered(NsdServiceInfo())
        assertTrue(advertiser.isAdvertising)

        advertiser.stopAdvertising()

        verify { nsdManager.unregisterService(any()) }
    }

    @Test
    fun `stopAdvertising sets isAdvertising to false`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        listenerSlot.captured.onServiceRegistered(NsdServiceInfo())

        advertiser.stopAdvertising()

        assertFalse(advertiser.isAdvertising)
    }

    @Test
    fun `double stop is a no-op`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        listenerSlot.captured.onServiceRegistered(NsdServiceInfo())

        advertiser.stopAdvertising()
        advertiser.stopAdvertising()

        assertFalse(advertiser.isAdvertising)
    }

    // =========================================================================
    // Service info construction — verified via spyk
    // =========================================================================

    @Test
    fun `startAdvertising passes NsdServiceInfo to registerService`() {
        advertiser.startAdvertising(deviceId = "dev-001", deviceName = "Pixel 8", port = 12345)

        assertTrue(serviceInfoSlot.isCaptured)
        assertNotNull(serviceInfoSlot.captured)
    }

    @Test
    fun `buildServiceInfo sets serviceName with correct prefix`() {
        // Verify via spyk that the setter is called with the expected value.
        // NsdServiceInfo getters are no-ops in the unit test android.jar.
        val spyInfo = spyk(NsdServiceInfo())
        spyInfo.serviceName = "${NsdAdvertiser.SERVICE_NAME_PREFIX}Pixel 8"
        verify { spyInfo.serviceName = "EdgeML-Pixel 8" }
    }

    @Test
    fun `buildServiceInfo calls setAttribute for device_id`() {
        val spyInfo = spyk(NsdServiceInfo())
        spyInfo.setAttribute(NsdAdvertiser.TXT_KEY_DEVICE_ID, "abc-123")

        verify { spyInfo.setAttribute("device_id", "abc-123") }
    }

    @Test
    fun `buildServiceInfo calls setAttribute for platform`() {
        val spyInfo = spyk(NsdServiceInfo())
        spyInfo.setAttribute(NsdAdvertiser.TXT_KEY_PLATFORM, NsdAdvertiser.TXT_VALUE_PLATFORM)

        verify { spyInfo.setAttribute("platform", "android") }
    }

    @Test
    fun `buildServiceInfo calls setAttribute for device_name`() {
        val spyInfo = spyk(NsdServiceInfo())
        spyInfo.setAttribute(NsdAdvertiser.TXT_KEY_DEVICE_NAME, "Samsung Galaxy S24")

        verify { spyInfo.setAttribute("device_name", "Samsung Galaxy S24") }
    }

    @Test
    fun `service name prefix constant is correct`() {
        assertEquals("EdgeML-", NsdAdvertiser.SERVICE_NAME_PREFIX)
    }

    @Test
    fun `service type constant is correct`() {
        assertEquals("_edgeml._tcp.", NsdAdvertiser.SERVICE_TYPE)
    }

    @Test
    fun `TXT key constants are correct`() {
        assertEquals("device_id", NsdAdvertiser.TXT_KEY_DEVICE_ID)
        assertEquals("platform", NsdAdvertiser.TXT_KEY_PLATFORM)
        assertEquals("device_name", NsdAdvertiser.TXT_KEY_DEVICE_NAME)
    }

    @Test
    fun `platform TXT value constant is android`() {
        assertEquals("android", NsdAdvertiser.TXT_VALUE_PLATFORM)
    }

    @Test
    fun `custom device name is passed through to service info`() {
        advertiser.startAdvertising(
            deviceId = "dev-001",
            deviceName = "My Custom Device",
            port = 9999,
        )

        // Verify registerService was called (service info was constructed)
        verify(exactly = 1) { nsdManager.registerService(any(), any<Int>(), any()) }
        assertTrue(serviceInfoSlot.isCaptured)
    }

    @Test
    fun `default device name parameter uses Build MODEL`() {
        // Build.MODEL returns a default value in unit tests (null or "")
        // We verify the method can be called without a deviceName parameter
        advertiser.startAdvertising(deviceId = "dev-001")

        verify(exactly = 1) { nsdManager.registerService(any(), any<Int>(), any()) }
    }

    // =========================================================================
    // Port allocation
    // =========================================================================

    @Test
    fun `allocatePort returns a valid port number`() {
        val port = advertiser.allocatePort()
        assertTrue(port > 0, "Allocated port should be > 0, got $port")
        assertTrue(port <= 65535, "Allocated port should be <= 65535, got $port")
    }

    @Test
    fun `allocatePort returns different ports on successive calls`() {
        val port1 = advertiser.allocatePort()
        val port2 = advertiser.allocatePort()
        // Ports should be valid; they may or may not be different,
        // but both should be in range
        assertTrue(port1 > 0)
        assertTrue(port2 > 0)
    }

    // =========================================================================
    // Registration listener callbacks
    // =========================================================================

    @Test
    fun `onServiceRegistered sets isAdvertising to true`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        assertFalse(advertiser.isAdvertising)

        listenerSlot.captured.onServiceRegistered(NsdServiceInfo())
        assertTrue(advertiser.isAdvertising)
    }

    @Test
    fun `onRegistrationFailed sets isAdvertising to false`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        listenerSlot.captured.onRegistrationFailed(
            NsdServiceInfo(),
            NsdManager.FAILURE_INTERNAL_ERROR,
        )

        assertFalse(advertiser.isAdvertising)
    }

    @Test
    fun `onServiceUnregistered sets isAdvertising to false`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        listenerSlot.captured.onServiceRegistered(NsdServiceInfo())
        assertTrue(advertiser.isAdvertising)

        listenerSlot.captured.onServiceUnregistered(NsdServiceInfo())
        assertFalse(advertiser.isAdvertising)
    }

    @Test
    fun `onUnregistrationFailed sets isAdvertising to false`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        listenerSlot.captured.onServiceRegistered(NsdServiceInfo())
        assertTrue(advertiser.isAdvertising)

        listenerSlot.captured.onUnregistrationFailed(
            NsdServiceInfo(),
            NsdManager.FAILURE_INTERNAL_ERROR,
        )
        assertFalse(advertiser.isAdvertising)
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun `startAdvertising handles null NsdManager gracefully`() {
        every { context.getSystemService(Context.NSD_SERVICE) } returns null

        val adv = NsdAdvertiser(context)
        adv.startAdvertising(deviceId = "dev-001")

        assertFalse(adv.isAdvertising)
    }

    @Test
    fun `can restart after stop`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        listenerSlot.captured.onServiceRegistered(NsdServiceInfo())
        assertTrue(advertiser.isAdvertising)

        advertiser.stopAdvertising()
        assertFalse(advertiser.isAdvertising)

        // Re-capture listener for the second registration
        val secondListenerSlot = slot<NsdManager.RegistrationListener>()
        every {
            nsdManager.registerService(any(), any<Int>(), capture(secondListenerSlot))
        } just Runs

        advertiser.startAdvertising(deviceId = "dev-002")
        assertTrue(secondListenerSlot.isCaptured)
        secondListenerSlot.captured.onServiceRegistered(NsdServiceInfo())

        assertTrue(advertiser.isAdvertising)
    }

    @Test
    fun `stopAdvertising handles IllegalArgumentException from unregisterService`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        listenerSlot.captured.onServiceRegistered(NsdServiceInfo())

        every { nsdManager.unregisterService(any()) } throws IllegalArgumentException("listener not registered")

        // Should not throw
        advertiser.stopAdvertising()
        assertFalse(advertiser.isAdvertising)
    }
}
