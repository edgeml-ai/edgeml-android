package ai.octomil.client

import ai.octomil.generated.DeviceClass
import ai.octomil.utils.DeviceUtils
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilitiesClientTest {

    private lateinit var context: Context
    private lateinit var capabilitiesClient: CapabilitiesClient

    @Before
    fun setUp() {
        context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        capabilitiesClient = CapabilitiesClient(context)

        mockkObject(DeviceUtils)
    }

    @After
    fun tearDown() {
        unmockkObject(DeviceUtils)
    }

    // =========================================================================
    // Device classification
    // =========================================================================

    @Test
    fun `classifyDevice returns FLAGSHIP for 8GB+ RAM`() {
        assertEquals(DeviceClass.FLAGSHIP, capabilitiesClient.classifyDevice(8 * 1024))
        assertEquals(DeviceClass.FLAGSHIP, capabilitiesClient.classifyDevice(12 * 1024))
    }

    @Test
    fun `classifyDevice returns HIGH for 6-8GB RAM`() {
        assertEquals(DeviceClass.HIGH, capabilitiesClient.classifyDevice(6 * 1024))
        assertEquals(DeviceClass.HIGH, capabilitiesClient.classifyDevice(7 * 1024))
    }

    @Test
    fun `classifyDevice returns MID for 4-6GB RAM`() {
        assertEquals(DeviceClass.MID, capabilitiesClient.classifyDevice(4 * 1024))
        assertEquals(DeviceClass.MID, capabilitiesClient.classifyDevice(5 * 1024))
    }

    @Test
    fun `classifyDevice returns LOW for under 4GB RAM`() {
        assertEquals(DeviceClass.LOW, capabilitiesClient.classifyDevice(2 * 1024))
        assertEquals(DeviceClass.LOW, capabilitiesClient.classifyDevice(0))
    }

    // =========================================================================
    // Runtime detection
    // =========================================================================

    @Test
    fun `detectRuntimes always includes tflite`() {
        every { DeviceUtils.isNnapiAvailable() } returns false
        every { DeviceUtils.isGpuAvailable() } returns false

        val runtimes = capabilitiesClient.detectRuntimes()
        assertTrue(runtimes.contains("tflite"))
    }

    @Test
    fun `detectRuntimes includes nnapi when available`() {
        every { DeviceUtils.isNnapiAvailable() } returns true
        every { DeviceUtils.isGpuAvailable() } returns false

        val runtimes = capabilitiesClient.detectRuntimes()
        assertTrue(runtimes.contains("nnapi"))
    }

    @Test
    fun `detectRuntimes includes gpu when available`() {
        every { DeviceUtils.isNnapiAvailable() } returns false
        every { DeviceUtils.isGpuAvailable() } returns true

        val runtimes = capabilitiesClient.detectRuntimes()
        assertTrue(runtimes.contains("gpu"))
    }

    @Test
    fun `detectRuntimes excludes nnapi when unavailable`() {
        every { DeviceUtils.isNnapiAvailable() } returns false
        every { DeviceUtils.isGpuAvailable() } returns false

        val runtimes = capabilitiesClient.detectRuntimes()
        assertFalse(runtimes.contains("nnapi"))
    }

    // =========================================================================
    // Accelerator detection
    // =========================================================================

    @Test
    fun `detectAccelerators always includes xnnpack`() {
        every { DeviceUtils.isNnapiAvailable() } returns false
        every { DeviceUtils.isGpuAvailable() } returns false

        val accel = capabilitiesClient.detectAccelerators()
        assertTrue(accel.contains("xnnpack"))
    }

    @Test
    fun `detectAccelerators includes nnapi when available`() {
        every { DeviceUtils.isNnapiAvailable() } returns true
        every { DeviceUtils.isGpuAvailable() } returns false

        val accel = capabilitiesClient.detectAccelerators()
        assertTrue(accel.contains("nnapi"))
    }

    @Test
    fun `detectAccelerators includes gpu when available`() {
        every { DeviceUtils.isNnapiAvailable() } returns false
        every { DeviceUtils.isGpuAvailable() } returns true

        val accel = capabilitiesClient.detectAccelerators()
        assertTrue(accel.contains("gpu"))
    }

    // =========================================================================
    // Full profile
    // =========================================================================

    @Test
    fun `current returns complete profile`() {
        every { DeviceUtils.getTotalMemoryMb(any()) } returns 8192L
        every { DeviceUtils.getAvailableStorageMb() } returns 4096L
        every { DeviceUtils.isNnapiAvailable() } returns true
        every { DeviceUtils.isGpuAvailable() } returns true

        val profile = capabilitiesClient.current()

        assertEquals(DeviceClass.FLAGSHIP, profile.deviceClass)
        assertEquals(8192L, profile.memoryMb)
        assertEquals(4096L, profile.storageMb)
        assertEquals("android", profile.platform)
        assertTrue(profile.availableRuntimes.contains("tflite"))
        assertTrue(profile.availableRuntimes.contains("nnapi"))
        assertTrue(profile.availableRuntimes.contains("gpu"))
        assertTrue(profile.accelerators.contains("xnnpack"))
        assertTrue(profile.accelerators.contains("nnapi"))
        assertTrue(profile.accelerators.contains("gpu"))
    }

    @Test
    fun `current returns LOW device profile for minimal hardware`() {
        every { DeviceUtils.getTotalMemoryMb(any()) } returns 2048L
        every { DeviceUtils.getAvailableStorageMb() } returns 512L
        every { DeviceUtils.isNnapiAvailable() } returns false
        every { DeviceUtils.isGpuAvailable() } returns false

        val profile = capabilitiesClient.current()

        assertEquals(DeviceClass.LOW, profile.deviceClass)
        assertEquals(2048L, profile.memoryMb)
        assertEquals(512L, profile.storageMb)
        assertEquals(listOf("tflite"), profile.availableRuntimes)
        assertEquals(listOf("xnnpack"), profile.accelerators)
    }
}
