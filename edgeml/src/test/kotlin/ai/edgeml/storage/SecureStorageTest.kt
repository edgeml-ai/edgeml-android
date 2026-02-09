package ai.edgeml.storage

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SecureStorageTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var storage: SecureStorage

    // In-memory map backing the mock SharedPreferences
    private val prefsMap = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        editor = mockk<SharedPreferences.Editor>(relaxed = true)
        prefs = mockk<SharedPreferences>()

        // Wire editor to return itself for chaining
        every { editor.putString(any(), any()) } answers {
            prefsMap[firstArg()] = secondArg<String?>()
            editor
        }
        every { editor.putLong(any(), any()) } answers {
            prefsMap[firstArg()] = secondArg<Long>()
            editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            prefsMap[firstArg()] = secondArg<Boolean>()
            editor
        }
        every { editor.remove(any()) } answers {
            prefsMap.remove(firstArg<String>())
            editor
        }
        every { editor.clear() } answers {
            prefsMap.clear()
            editor
        }
        every { editor.apply() } answers { }

        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers {
            prefsMap[firstArg()] as? String ?: secondArg()
        }
        every { prefs.getLong(any(), any()) } answers {
            prefsMap[firstArg()] as? Long ?: secondArg()
        }
        every { prefs.getBoolean(any(), any()) } answers {
            prefsMap[firstArg()] as? Boolean ?: secondArg()
        }
        every { prefs.contains(any()) } answers {
            prefsMap.containsKey(firstArg<String>())
        }

        storage = SecureStorage.createForTesting(prefs, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        prefsMap.clear()
        SecureStorage.clearInstance()
    }

    // =========================================================================
    // String operations
    // =========================================================================

    @Test
    fun `putString and getString roundtrip`() = runTest(testDispatcher) {
        storage.putString("key1", "value1")
        val result = storage.getString("key1")
        assertEquals("value1", result)
    }

    @Test
    fun `getString returns null for missing key`() = runTest(testDispatcher) {
        val result = storage.getString("nonexistent")
        assertNull(result)
    }

    // =========================================================================
    // Long operations
    // =========================================================================

    @Test
    fun `putLong and getLong roundtrip`() = runTest(testDispatcher) {
        storage.putLong("count", 42L)
        val result = storage.getLong("count")
        assertEquals(42L, result)
    }

    @Test
    fun `getLong returns default for missing key`() = runTest(testDispatcher) {
        val result = storage.getLong("missing", 99L)
        assertEquals(99L, result)
    }

    // =========================================================================
    // Boolean operations
    // =========================================================================

    @Test
    fun `putBoolean and getBoolean roundtrip`() = runTest(testDispatcher) {
        storage.putBoolean("flag", true)
        val result = storage.getBoolean("flag")
        assertTrue(result)
    }

    @Test
    fun `getBoolean returns default for missing key`() = runTest(testDispatcher) {
        val result = storage.getBoolean("missing", false)
        assertFalse(result)
    }

    // =========================================================================
    // Contains / Remove / Clear
    // =========================================================================

    @Test
    fun `contains returns true for existing key`() = runTest(testDispatcher) {
        storage.putString("exists", "yes")
        assertTrue(storage.contains("exists"))
    }

    @Test
    fun `contains returns false for missing key`() = runTest(testDispatcher) {
        assertFalse(storage.contains("nope"))
    }

    @Test
    fun `remove deletes key`() = runTest(testDispatcher) {
        storage.putString("to_remove", "val")
        storage.remove("to_remove")
        assertNull(storage.getString("to_remove"))
    }

    @Test
    fun `clear removes all keys`() = runTest(testDispatcher) {
        storage.putString("a", "1")
        storage.putString("b", "2")
        storage.clear()
        assertNull(storage.getString("a"))
        assertNull(storage.getString("b"))
    }

    // =========================================================================
    // Device-specific convenience methods
    // =========================================================================

    @Test
    fun `server device ID roundtrip`() = runTest(testDispatcher) {
        assertNull(storage.getServerDeviceId())
        storage.setServerDeviceId("server-uuid-123")
        assertEquals("server-uuid-123", storage.getServerDeviceId())
    }

    @Test
    fun `client device identifier roundtrip`() = runTest(testDispatcher) {
        assertNull(storage.getClientDeviceIdentifier())
        storage.setClientDeviceIdentifier("device-abc")
        assertEquals("device-abc", storage.getClientDeviceIdentifier())
    }

    @Test
    fun `API token roundtrip`() = runTest(testDispatcher) {
        assertNull(storage.getApiToken())
        storage.setApiToken("token-xyz")
        assertEquals("token-xyz", storage.getApiToken())
    }

    @Test
    fun `device registered flag roundtrip`() = runTest(testDispatcher) {
        assertFalse(storage.isDeviceRegistered())
        storage.setDeviceRegistered(true)
        assertTrue(storage.isDeviceRegistered())
    }

    @Test
    fun `setDeviceRegistered stores timestamp when true`() = runTest(testDispatcher) {
        storage.setDeviceRegistered(true)

        // Verify putLong was called for the registration timestamp
        verify { editor.putLong(SecureStorage.KEY_REGISTRATION_TIMESTAMP, any()) }
    }

    @Test
    fun `model version roundtrip`() = runTest(testDispatcher) {
        assertNull(storage.getCurrentModelVersion())
        storage.setCurrentModelVersion("2.1.0")
        assertEquals("2.1.0", storage.getCurrentModelVersion())
    }

    @Test
    fun `model checksum roundtrip`() = runTest(testDispatcher) {
        assertNull(storage.getModelChecksum())
        storage.setModelChecksum("sha256-abc")
        assertEquals("sha256-abc", storage.getModelChecksum())
    }

    @Test
    fun `last sync timestamp roundtrip`() = runTest(testDispatcher) {
        assertEquals(0L, storage.getLastSyncTimestamp())
        storage.setLastSyncTimestamp(1000L)
        assertEquals(1000L, storage.getLastSyncTimestamp())
    }

    @Test
    fun `experiment ID roundtrip and clear`() = runTest(testDispatcher) {
        assertNull(storage.getExperimentId())
        storage.setExperimentId("exp-1")
        assertEquals("exp-1", storage.getExperimentId())

        storage.setExperimentId(null)
        assertNull(storage.getExperimentId())
    }

    @Test
    fun `variant defaults to 'default'`() = runTest(testDispatcher) {
        val variant = storage.getVariant()
        assertEquals("default", variant)
    }

    @Test
    fun `variant roundtrip`() = runTest(testDispatcher) {
        storage.setVariant("treatment_a")
        assertEquals("treatment_a", storage.getVariant())
    }

    // =========================================================================
    // Device policy JSON roundtrip
    // =========================================================================

    @Test
    fun `device policy returns null when not set`() = runTest(testDispatcher) {
        assertNull(storage.getDevicePolicy())
    }

    @Test
    fun `device policy JSON roundtrip`() = runTest(testDispatcher) {
        val policy = ai.edgeml.api.dto.DevicePolicyResponse(
            batteryThreshold = 30,
            networkPolicy = "wifi_only",
            samplingPolicy = "random",
            trainingWindow = "02:00-06:00",
        )
        storage.setDevicePolicy(policy)

        val retrieved = storage.getDevicePolicy()
        assertNotNull(retrieved)
        assertEquals(30, retrieved.batteryThreshold)
        assertEquals("wifi_only", retrieved.networkPolicy)
        assertEquals("random", retrieved.samplingPolicy)
        assertEquals("02:00-06:00", retrieved.trainingWindow)
    }

    @Test
    fun `device policy returns null for corrupt JSON`() = runTest(testDispatcher) {
        storage.putString(SecureStorage.KEY_DEVICE_POLICY, "not-json{{{")
        val result = storage.getDevicePolicy()
        assertNull(result)
    }

    // =========================================================================
    // Singleton management
    // =========================================================================

    @Test
    fun `clearInstance resets singleton`() {
        SecureStorage.clearInstance()
        // No exception means success - the singleton is reset
    }

    // =========================================================================
    // createForTesting factory
    // =========================================================================

    @Test
    fun `createForTesting creates functional instance`() = runTest(testDispatcher) {
        val testStorage = SecureStorage.createForTesting(prefs, testDispatcher)
        testStorage.putString("test", "value")
        assertEquals("value", testStorage.getString("test"))
    }
}
