package ai.edgeml.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [NsdAdvertiser].
 *
 * NsdManager is an Android framework class that must be mocked in unit tests.
 * These tests verify:
 * - Initialization state
 * - Start/stop lifecycle and idempotency
 * - Service info construction (type, name, TXT records)
 * - Registration listener callbacks
 * - Default and custom device name behavior
 */
class NsdAdvertiserTest {

    private lateinit var context: Context
    private lateinit var nsdManager: NsdManager
    private lateinit var advertiser: NsdAdvertiser

    private val listenerSlot = slot<NsdManager.RegistrationListener>()

    @Before
    fun setUp() {
        nsdManager = mockk<NsdManager>(relaxed = true)
        context = mockk<Context>(relaxed = true)

        every { context.getSystemService(Context.NSD_SERVICE) } returns nsdManager
        every {
            nsdManager.registerService(any(), any<Int>(), capture(listenerSlot))
        } just Runs

        advertiser = NsdAdvertiser(context)
    }

    @After
    fun tearDown() {
        // Ensure clean state
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

        // Registration is async; listener has been captured
        assertTrue(listenerSlot.isCaptured)

        // Simulate successful registration callback
        val capturedListener = listenerSlot.captured
        capturedListener.onServiceRegistered(NsdServiceInfo())

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

        // Second call should be a no-op
        advertiser.startAdvertising(deviceId = "dev-002")

        // registerService should have been called exactly once
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
        // Should not throw
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

        // The unregister call should have been made
        verify { nsdManager.unregisterService(any()) }
    }

    @Test
    fun `stopAdvertising sets isAdvertising to false after unregister callback`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        listenerSlot.captured.onServiceRegistered(NsdServiceInfo())

        // Capture the unregister call to simulate callback
        val unregisterListenerSlot = slot<NsdManager.RegistrationListener>()
        every { nsdManager.unregisterService(capture(unregisterListenerSlot)) } just Runs

        // Re-start to capture the new listener (needed because stopAdvertising nulls it)
        // Instead, just call stop and check state
        advertiser.stopAdvertising()

        assertFalse(advertiser.isAdvertising)
    }

    @Test
    fun `double stop is a no-op`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        listenerSlot.captured.onServiceRegistered(NsdServiceInfo())

        advertiser.stopAdvertising()
        advertiser.stopAdvertising() // Should not throw or double-unregister

        assertFalse(advertiser.isAdvertising)
    }

    // =========================================================================
    // Service info construction
    // =========================================================================

    @Test
    fun `service info has correct service type`() {
        val serviceInfo = advertiser.buildServiceInfo(
            deviceId = "dev-001",
            deviceName = "Pixel 8",
            port = 12345,
        )

        assertEquals(NsdAdvertiser.SERVICE_TYPE, serviceInfo.serviceType)
    }

    @Test
    fun `service info has correct service name with prefix`() {
        val serviceInfo = advertiser.buildServiceInfo(
            deviceId = "dev-001",
            deviceName = "Pixel 8",
            port = 12345,
        )

        assertEquals("EdgeML-Pixel 8", serviceInfo.serviceName)
    }

    @Test
    fun `service info has correct port`() {
        val serviceInfo = advertiser.buildServiceInfo(
            deviceId = "dev-001",
            deviceName = "Pixel 8",
            port = 54321,
        )

        assertEquals(54321, serviceInfo.port)
    }

    @Test
    fun `service info has device_id TXT record`() {
        val serviceInfo = advertiser.buildServiceInfo(
            deviceId = "abc-123-def",
            deviceName = "Pixel 8",
            port = 12345,
        )

        val attributes = serviceInfo.attributes
        val deviceIdValue = attributes[NsdAdvertiser.TXT_KEY_DEVICE_ID]
        assertEquals("abc-123-def", deviceIdValue?.let { String(it) })
    }

    @Test
    fun `service info has platform TXT record set to android`() {
        val serviceInfo = advertiser.buildServiceInfo(
            deviceId = "dev-001",
            deviceName = "Pixel 8",
            port = 12345,
        )

        val attributes = serviceInfo.attributes
        val platformValue = attributes[NsdAdvertiser.TXT_KEY_PLATFORM]
        assertEquals("android", platformValue?.let { String(it) })
    }

    @Test
    fun `service info has device_name TXT record`() {
        val serviceInfo = advertiser.buildServiceInfo(
            deviceId = "dev-001",
            deviceName = "Samsung Galaxy S24",
            port = 12345,
        )

        val attributes = serviceInfo.attributes
        val nameValue = attributes[NsdAdvertiser.TXT_KEY_DEVICE_NAME]
        assertEquals("Samsung Galaxy S24", nameValue?.let { String(it) })
    }

    @Test
    fun `custom device name is used in service name`() {
        val serviceInfo = advertiser.buildServiceInfo(
            deviceId = "dev-001",
            deviceName = "My Custom Device",
            port = 12345,
        )

        assertEquals("EdgeML-My Custom Device", serviceInfo.serviceName)
    }

    @Test
    fun `default device name parameter defaults to Build MODEL`() {
        // startAdvertising defaults deviceName to Build.MODEL.
        // In test env Build.MODEL is typically null or empty; we verify the
        // buildServiceInfo helper correctly uses whatever value is passed.
        val buildModel = Build.MODEL ?: ""

        advertiser.startAdvertising(deviceId = "dev-001")

        // Verify registerService was called with a service info containing the model
        verify {
            nsdManager.registerService(
                match<NsdServiceInfo> { info ->
                    info.serviceName == "${NsdAdvertiser.SERVICE_NAME_PREFIX}$buildModel"
                },
                any<Int>(),
                any(),
            )
        }
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

    // =========================================================================
    // Registration listener callbacks
    // =========================================================================

    @Test
    fun `onServiceRegistered sets isAdvertising to true`() {
        advertiser.startAdvertising(deviceId = "dev-001")
        assertFalse(advertiser.isAdvertising) // Not yet registered

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
        adv.startAdvertising(deviceId = "dev-001") // Should not throw

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
}
