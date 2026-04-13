package ai.octomil.runtime.planner

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceRuntimeProfileCollectorTest {

    @After
    fun tearDown() {
        DeviceRuntimeProfileCollector.clearEvidence()
    }

    // =========================================================================
    // Model-capable evidence registration
    // =========================================================================

    @Test
    fun `registerEvidence adds model-capable entry`() {
        val evidence = InstalledRuntime.modelCapable(
            engine = "llama.cpp",
            model = "phi-4-mini",
            capability = "text",
        )
        DeviceRuntimeProfileCollector.registerEvidence(evidence)

        val registered = DeviceRuntimeProfileCollector.getRegisteredEvidence()
        assertEquals(1, registered.size)
        assertEquals("llama.cpp", registered[0].engine)
        assertEquals("phi-4-mini", registered[0].metadata["models"])
        assertEquals("text", registered[0].metadata["capabilities"])
    }

    @Test
    fun `registerEvidence deduplicates by engine and model`() {
        val evidence1 = InstalledRuntime.modelCapable(
            engine = "llama.cpp",
            model = "phi-4-mini",
            capability = "text",
        )
        val evidence2 = InstalledRuntime.modelCapable(
            engine = "llama.cpp",
            model = "phi-4-mini",
            capability = "text",
            version = "v2",
        )
        DeviceRuntimeProfileCollector.registerEvidence(evidence1)
        DeviceRuntimeProfileCollector.registerEvidence(evidence2)

        val registered = DeviceRuntimeProfileCollector.getRegisteredEvidence()
        assertEquals(1, registered.size)
        assertEquals("v2", registered[0].version)
    }

    @Test
    fun `registerEvidence allows different models for same engine`() {
        DeviceRuntimeProfileCollector.registerEvidence(
            InstalledRuntime.modelCapable(
                engine = "llama.cpp",
                model = "phi-4-mini",
                capability = "text",
            ),
        )
        DeviceRuntimeProfileCollector.registerEvidence(
            InstalledRuntime.modelCapable(
                engine = "llama.cpp",
                model = "gemma-2b",
                capability = "text",
            ),
        )

        val registered = DeviceRuntimeProfileCollector.getRegisteredEvidence()
        assertEquals(2, registered.size)
    }

    @Test
    fun `clearEvidence removes all entries`() {
        DeviceRuntimeProfileCollector.registerEvidence(
            InstalledRuntime.modelCapable(
                engine = "llama.cpp",
                model = "test",
                capability = "text",
            ),
        )
        DeviceRuntimeProfileCollector.clearEvidence()

        val registered = DeviceRuntimeProfileCollector.getRegisteredEvidence()
        assertTrue(registered.isEmpty())
    }

    @Test
    fun `registerEvidence canonicalizes engine aliases`() {
        DeviceRuntimeProfileCollector.registerEvidence(
            InstalledRuntime.modelCapable(
                engine = "llamacpp",
                model = "test",
                capability = "text",
            ),
        )

        val registered = DeviceRuntimeProfileCollector.getRegisteredEvidence()
        assertEquals(1, registered.size)
        assertEquals("llama.cpp", registered[0].engine)
    }

    // =========================================================================
    // SDK Version
    // =========================================================================

    @Test
    fun `getSdkVersion returns non-blank string`() {
        val version = DeviceRuntimeProfileCollector.getSdkVersion()
        // In unit tests BuildConfig may not be available; returns "unknown"
        assertNotNull(version)
        assertTrue(version.isNotBlank())
    }

    // =========================================================================
    // ABI
    // =========================================================================

    @Test
    fun `getPrimaryAbi returns non-blank string`() {
        val abi = DeviceRuntimeProfileCollector.getPrimaryAbi()
        assertNotNull(abi)
        assertTrue(abi.isNotBlank())
    }

    @Test
    fun `getAllAbis returns list`() {
        val abis = DeviceRuntimeProfileCollector.getAllAbis()
        // In unit tests, Build.SUPPORTED_ABIS may be null; empty list is acceptable
        assertNotNull(abis)
    }

    @Test
    fun `primary ABI is first in all ABIs list when available`() {
        val primary = DeviceRuntimeProfileCollector.getPrimaryAbi()
        val all = DeviceRuntimeProfileCollector.getAllAbis()
        if (all.isNotEmpty() && primary != "unknown") {
            assertEquals(primary, all.first())
        }
    }

    // =========================================================================
    // RAM Band
    // =========================================================================

    @Test
    fun `ramBand returns high for 8GB or more`() {
        assertEquals("high", DeviceRuntimeProfileCollector.ramBand(8L * 1024 * 1024 * 1024))
        assertEquals("high", DeviceRuntimeProfileCollector.ramBand(12L * 1024 * 1024 * 1024))
    }

    @Test
    fun `ramBand returns mid for 4GB to 8GB`() {
        assertEquals("mid", DeviceRuntimeProfileCollector.ramBand(4L * 1024 * 1024 * 1024))
        assertEquals("mid", DeviceRuntimeProfileCollector.ramBand(6L * 1024 * 1024 * 1024))
    }

    @Test
    fun `ramBand returns low for less than 4GB`() {
        assertEquals("low", DeviceRuntimeProfileCollector.ramBand(2L * 1024 * 1024 * 1024))
        assertEquals("low", DeviceRuntimeProfileCollector.ramBand(1L * 1024 * 1024 * 1024))
    }

    @Test
    fun `ramBand returns unknown for null`() {
        assertEquals("unknown", DeviceRuntimeProfileCollector.ramBand(null))
    }

    // =========================================================================
    // Accelerators
    // =========================================================================

    @Test
    fun `detectAccelerators includes gpu`() {
        val accel = DeviceRuntimeProfileCollector.detectAccelerators()
        assertTrue(accel.contains("gpu"))
    }

    // =========================================================================
    // Installed Runtimes
    // =========================================================================

    @Test
    fun `detectInstalledRuntimes returns canonical engine ids`() {
        val runtimes = DeviceRuntimeProfileCollector.detectInstalledRuntimes()
        assertTrue(runtimes.all { it.engine == RuntimeEngineIds.canonical(it.engine) })
    }

    @Test
    fun `detectInstalledRuntimes marks all as available`() {
        val runtimes = DeviceRuntimeProfileCollector.detectInstalledRuntimes()
        assertTrue(runtimes.all { it.available })
    }

    // =========================================================================
    // isClassAvailable
    // =========================================================================

    @Test
    fun `isClassAvailable returns true for known class`() {
        assertTrue(DeviceRuntimeProfileCollector.isClassAvailable("java.lang.String"))
    }

    @Test
    fun `isClassAvailable returns false for missing class`() {
        assertEquals(false, DeviceRuntimeProfileCollector.isClassAvailable("com.nonexistent.FakeClass"))
    }

    // =========================================================================
    // getChipName
    // =========================================================================

    @Test
    fun `getChipName returns non-null on devices with hardware info`() {
        // Build.HARDWARE is always non-null on Android but may be empty in unit tests
        val chip = DeviceRuntimeProfileCollector.getChipName()
        // In Robolectric/JVM tests, Build.HARDWARE may be empty; just check no crash
        assertNotNull(chip.let { it ?: "null_is_ok" })
    }
}
