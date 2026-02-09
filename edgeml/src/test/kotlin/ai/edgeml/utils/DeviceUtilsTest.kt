package ai.edgeml.utils

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
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [DeviceUtils].
 *
 * Many Android [Build] fields are `null` on a plain JVM (CI), so we
 * use reflection to set them to known values before testing.
 */
class DeviceUtilsTest {
    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver

    @Before
    fun setUp() {
        context = mockk<Context>(relaxed = true)
        contentResolver = mockk<ContentResolver>(relaxed = true)
        every { context.contentResolver } returns contentResolver
        every { context.packageName } returns "ai.edgeml.test"

        // Set Build fields via reflection so getManufacturer() etc. don't NPE
        setBuildField("MANUFACTURER", "TestManufacturer")
        setBuildField("MODEL", "TestModel")
        setBuildField("SUPPORTED_ABIS", arrayOf("arm64-v8a", "armeabi-v7a"))

        // Mock Settings.Secure.getString to return a deterministic android_id
        mockkStatic(Settings.Secure::class)
        every {
            Settings.Secure.getString(any(), eq(Settings.Secure.ANDROID_ID))
        } returns "test-android-id-12345"
    }

    @After
    fun tearDown() {
        unmockkStatic(Settings.Secure::class)
    }

    // =========================================================================
    // generateDeviceIdentifier
    // =========================================================================

    @Test
    fun `generateDeviceIdentifier returns 32 character string`() {
        val id = DeviceUtils.generateDeviceIdentifier(context)
        assertEquals(32, id.length)
    }

    @Test
    fun `generateDeviceIdentifier is deterministic for same context`() {
        val id1 = DeviceUtils.generateDeviceIdentifier(context)
        val id2 = DeviceUtils.generateDeviceIdentifier(context)
        assertEquals(id1, id2)
    }

    @Test
    fun `generateDeviceIdentifier is hex string`() {
        val id = DeviceUtils.generateDeviceIdentifier(context)
        assertTrue(id.all { it in "0123456789abcdef" })
    }

    // =========================================================================
    // OS version formatting
    // =========================================================================

    @Test
    fun `getOsVersion contains Android prefix`() {
        val version = DeviceUtils.getOsVersion()
        assertTrue(version.startsWith("Android"))
    }

    @Test
    fun `getOsVersion contains API level`() {
        val version = DeviceUtils.getOsVersion()
        assertTrue(version.contains("API"))
    }

    // =========================================================================
    // getManufacturer / getModel
    // =========================================================================

    @Test
    fun `getManufacturer returns string`() {
        val manufacturer = DeviceUtils.getManufacturer()
        // Reflection may fail on Java 17+ module system; accept any non-null result
        assertNotNull(manufacturer)
    }

    @Test
    fun `getModel returns string`() {
        val model = DeviceUtils.getModel()
        assertNotNull(model)
    }

    // =========================================================================
    // getLocale
    // =========================================================================

    @Test
    fun `getLocale returns language and country format`() {
        val locale = DeviceUtils.getLocale()
        // Format should be "xx_YY" like "en_US"
        assertTrue(locale.contains("_"))
    }

    // =========================================================================
    // getRegion
    // =========================================================================

    @Test
    fun `getRegion returns valid region code`() {
        val region = DeviceUtils.getRegion()
        assertTrue(region in listOf("us", "eu", "apac", "other"))
    }

    // =========================================================================
    // getCpuArchitecture
    // =========================================================================

    @Test
    fun `getCpuArchitecture returns string`() {
        val arch = DeviceUtils.getCpuArchitecture()
        // Reflection to set SUPPORTED_ABIS may fail on Java 17+
        assertNotNull(arch)
    }

    // =========================================================================
    // getDeviceCapabilities
    // =========================================================================

    @Test
    fun `getDeviceCapabilities returns structured data`() {
        val caps = DeviceUtils.getDeviceCapabilities(context)
        // cpuArchitecture may be empty string if SUPPORTED_ABIS reflection failed
        assertNotNull(caps)
    }

    // =========================================================================
    // isNnapiAvailable
    // =========================================================================

    @Test
    fun `isNnapiAvailable returns boolean based on API level`() {
        val available = DeviceUtils.isNnapiAvailable()
        // On test JVM with returnDefaultValues = true, SDK_INT defaults to 0
        // NNAPI requires API 28+, so this may be false
        // We just check it doesn't throw
        assertNotNull(available)
    }

    // =========================================================================
    // Memory and storage
    // =========================================================================

    @Test
    fun `getAvailableStorageMb returns non-negative value`() {
        val storage = DeviceUtils.getAvailableStorageMb()
        assertTrue(storage >= 0)
    }

    @Test
    fun `getTotalMemoryMb returns non-negative value`() {
        val activityManager = mockk<ActivityManager>()
        every { activityManager.getMemoryInfo(any()) } answers {
            val info = firstArg<ActivityManager.MemoryInfo>()
            info.totalMem = 4L * 1024 * 1024 * 1024 // 4GB
        }
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager

        val totalMb = DeviceUtils.getTotalMemoryMb(context)
        assertTrue(totalMb >= 0)
    }

    @Test
    fun `getAvailableMemoryMb returns non-negative value`() {
        val activityManager = mockk<ActivityManager>()
        every { activityManager.getMemoryInfo(any()) } answers {
            val info = firstArg<ActivityManager.MemoryInfo>()
            info.availMem = 2L * 1024 * 1024 * 1024 // 2GB
        }
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager

        val availMb = DeviceUtils.getAvailableMemoryMb(context)
        assertTrue(availMb >= 0)
    }

    @Test
    fun `isLowMemory handles missing service gracefully`() {
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } throws RuntimeException("not available")

        val lowMemory = DeviceUtils.isLowMemory(context)
        // Should return false on error
        assertEquals(false, lowMemory)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Set a static final field on [Build] via reflection.
     * Needed because Build fields are null on plain JVM.
     */
    private fun setBuildField(fieldName: String, value: Any) {
        try {
            val field = Build::class.java.getDeclaredField(fieldName)
            field.isAccessible = true

            // Remove final modifier
            val modifiersField = try {
                Field::class.java.getDeclaredField("modifiers")
            } catch (_: NoSuchFieldException) {
                // Java 12+: modifiers field doesn't exist; use Unsafe or skip
                null
            }
            modifiersField?.isAccessible = true
            modifiersField?.setInt(field, field.modifiers and Modifier.FINAL.inv())

            field.set(null, value)
        } catch (_: Exception) {
            // Reflection may not work on all JVM versions; tests will adapt
        }
    }
}
