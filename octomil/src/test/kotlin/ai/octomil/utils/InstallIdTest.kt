package ai.octomil.utils

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstallIdTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private val prefsMap = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        InstallId.resetCache()

        editor = mockk<SharedPreferences.Editor>(relaxed = true)
        prefs = mockk<SharedPreferences>()

        every { editor.putString(any(), any()) } answers {
            prefsMap[firstArg()] = secondArg<String?>()
            editor
        }
        every { editor.apply() } answers { }
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers {
            prefsMap[firstArg()] as? String ?: secondArg()
        }
    }

    @After
    fun tearDown() {
        prefsMap.clear()
        InstallId.resetCache()
    }

    // =========================================================================
    // Generation
    // =========================================================================

    @Test
    fun `generates UUID on first call`() {
        val id = InstallId.getOrCreate(prefs)
        assertNotNull(id)
        assertTrue(id.isNotEmpty(), "Install ID should not be empty")
        // UUID format: 8-4-4-4-12 hex chars
        assertTrue(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
            "Expected UUID format, got: $id")
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    @Test
    fun `persists to SharedPreferences`() {
        val id = InstallId.getOrCreate(prefs)
        assertEquals(id, prefsMap[InstallId.PREFS_KEY])
    }

    @Test
    fun `reads existing value from SharedPreferences`() {
        prefsMap[InstallId.PREFS_KEY] = "existing-install-id-12345"
        val id = InstallId.getOrCreate(prefs)
        assertEquals("existing-install-id-12345", id)
    }

    // =========================================================================
    // Stability
    // =========================================================================

    @Test
    fun `stable across calls`() {
        val first = InstallId.getOrCreate(prefs)
        val second = InstallId.getOrCreate(prefs)
        assertEquals(first, second)
    }

    @Test
    fun `stable after cache reset`() {
        val first = InstallId.getOrCreate(prefs)
        InstallId.resetCache()
        val second = InstallId.getOrCreate(prefs)
        assertEquals(first, second)
    }

    // =========================================================================
    // Cache Behavior
    // =========================================================================

    @Test
    fun `cache avoids repeated SharedPreferences reads`() {
        val first = InstallId.getOrCreate(prefs)
        // Overwrite the stored value -- cached value should still be returned
        prefsMap[InstallId.PREFS_KEY] = "overwritten-value"
        val second = InstallId.getOrCreate(prefs)
        assertEquals(first, second, "Cached value should be returned even after SharedPreferences changes")
    }

    @Test
    fun `resetCache forces re-read from SharedPreferences`() {
        InstallId.getOrCreate(prefs)
        InstallId.resetCache()
        prefsMap[InstallId.PREFS_KEY] = "new-value-after-reset"
        val second = InstallId.getOrCreate(prefs)
        assertEquals("new-value-after-reset", second)
    }

    @Test
    fun `getCached returns null before initialization`() {
        assertNull(InstallId.getCached())
    }

    @Test
    fun `getCached returns value after initialization`() {
        val id = InstallId.getOrCreate(prefs)
        assertEquals(id, InstallId.getCached())
    }

    // =========================================================================
    // Empty Value Handling
    // =========================================================================

    @Test
    fun `handles empty stored value`() {
        prefsMap[InstallId.PREFS_KEY] = ""
        val id = InstallId.getOrCreate(prefs)
        assertTrue(id.isNotEmpty(), "Should generate new ID when stored value is empty")
        assertTrue(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun `handles null stored value`() {
        // prefsMap has no entry for the key
        val id = InstallId.getOrCreate(prefs)
        assertTrue(id.isNotEmpty())
        verify { editor.putString(InstallId.PREFS_KEY, id) }
    }
}
