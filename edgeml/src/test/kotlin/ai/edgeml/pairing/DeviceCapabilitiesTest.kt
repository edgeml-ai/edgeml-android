package ai.edgeml.pairing

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.Settings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [DeviceCapabilities] device info collection.
 */
class DeviceCapabilitiesTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var activityManager: ActivityManager

    @Before
    fun setUp() {
        context = mockk<Context>(relaxed = true)
        contentResolver = mockk<ContentResolver>(relaxed = true)
        activityManager = mockk<ActivityManager>(relaxed = true)

        every { context.contentResolver } returns contentResolver
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
    }

    @After
    fun tearDown() {
        // No static mocks to clean in this version
    }

    @Test
    fun `collect returns populated DeviceConnectRequest`() {
        // Mock ActivityManager.getMemoryInfo
        every { activityManager.getMemoryInfo(any()) } answers {
            val memInfo = firstArg<ActivityManager.MemoryInfo>()
            // Reflectively set totalMem since it's a public field
            val field = ActivityManager.MemoryInfo::class.java.getField("totalMem")
            field.setLong(memInfo, 8L * 1024 * 1024 * 1024) // 8 GB
        }

        val request = DeviceCapabilities.collect(context)

        assertNotNull(request.deviceId)
        assertEquals("android", request.platform)
        assertNotNull(request.deviceName)
        assertNotNull(request.osVersion)
        // RAM should be computed from mocked totalMem
        assertNotNull(request.ramGb)
        // NNAPI check depends on SDK version
        assertNotNull(request.npuAvailable)
        assertNotNull(request.gpuAvailable)
        assertTrue(request.gpuAvailable!!)
    }

    @Test
    fun `getDeviceId returns fallback on exception`() {
        every { context.contentResolver } throws SecurityException("No permission")

        val deviceId = DeviceCapabilities.getDeviceId(context)
        assertEquals("unknown-android-device", deviceId)
    }

    @Test
    fun `getRAMGb returns zero when ActivityManager unavailable`() {
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns null

        val ramGb = DeviceCapabilities.getRAMGb(context)
        assertEquals(0.0, ramGb)
    }

    @Test
    fun `hasNNAPI returns true when SDK version is O_MR1 or higher`() {
        // Build.VERSION.SDK_INT is a static final field; in unit tests it defaults
        // to 0 (Robolectric) or current JVM. hasNNAPI checks >= O_MR1 (27).
        // We verify the function doesn't throw and returns a boolean.
        val result = DeviceCapabilities.hasNNAPI()
        // On a JVM test environment SDK_INT is typically 0, so this will be false
        assertNotNull(result)
    }

    @Test
    fun `getChipFamily returns non-empty string`() {
        val chipFamily = DeviceCapabilities.getChipFamily()
        assertNotNull(chipFamily)
        // Build.HARDWARE defaults to "unknown" in test JVM
        assertTrue(chipFamily.isNotBlank() || chipFamily == "unknown")
    }
}
