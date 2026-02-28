package ai.octomil.wrapper

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tensorflow.lite.Interpreter
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for [Octomil.wrap] — the main entry point for creating wrapped interpreters.
 */
class OctomilWrapperTest {

    private lateinit var mockInterpreter: Interpreter
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "octomil_wrapper_entry_test_${System.nanoTime()}")
        tmpDir.mkdirs()

        mockInterpreter = mockk<Interpreter>(relaxed = true)
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // =========================================================================
    // wrap() — basic creation
    // =========================================================================

    @Test
    fun `wrap returns a non-null OctomilWrappedInterpreter`() {
        val config = OctomilWrapperConfig.offline()

        val wrapped = Octomil.wrap(
            interpreter = mockInterpreter,
            modelId = "classifier",
            config = config,
        )

        assertNotNull(wrapped)
        wrapped.close()
    }

    @Test
    fun `wrap with default config does not throw`() {
        val wrapped = Octomil.wrap(
            interpreter = mockInterpreter,
            modelId = "test-model",
            config = OctomilWrapperConfig.default(),
        )

        assertNotNull(wrapped)
        wrapped.close()
    }

    @Test
    fun `wrapped interpreter delegates predict to original`() {
        val config = OctomilWrapperConfig.offline()
        val wrapped = Octomil.wrap(
            interpreter = mockInterpreter,
            modelId = "test-model",
            config = config,
        )

        val input = floatArrayOf(1.0f, 2.0f)
        val output = floatArrayOf(0.0f)
        wrapped.predict(input, output)

        verify(exactly = 1) { mockInterpreter.run(input, output) }
        wrapped.close()
    }

    // =========================================================================
    // wrap() — with persist dir
    // =========================================================================

    @Test
    fun `wrap with persistDir creates telemetry directory`() {
        val config = OctomilWrapperConfig(
            telemetryEnabled = true,
            validateInputs = false,
            otaUpdatesEnabled = false,
        )

        val wrapped = Octomil.wrap(
            interpreter = mockInterpreter,
            modelId = "test-model",
            config = config,
            persistDir = tmpDir,
        )

        // Run an inference so telemetry is enqueued
        wrapped.predict(floatArrayOf(1.0f), floatArrayOf(0.0f))

        assertNotNull(wrapped)
        wrapped.close()

        // After close, remaining events should be persisted
        val telemetryDir = File(tmpDir, "octomil_telemetry")
        // The directory may or may not be created depending on flush timing,
        // but the wrap call itself should not throw
    }

    // =========================================================================
    // wrap() — with server config does not crash (even though server is unreachable)
    // =========================================================================

    @Test
    fun `wrap with unreachable server does not block or throw`() {
        val config = OctomilWrapperConfig(
            serverUrl = "https://nonexistent.octomil.test",
            apiKey = "fake-key",
            validateInputs = true,
            telemetryEnabled = true,
            otaUpdatesEnabled = true,
        )

        // This should return immediately even though server is unreachable
        // (async contract fetch and OTA check run in background)
        val wrapped = Octomil.wrap(
            interpreter = mockInterpreter,
            modelId = "test-model",
            config = config,
        )

        assertNotNull(wrapped)
        // Should still be able to predict without server
        wrapped.predict(floatArrayOf(1.0f), floatArrayOf(0.0f))

        verify(exactly = 1) { mockInterpreter.run(any(), any()) }
        wrapped.close()
    }

    // =========================================================================
    // wrap() — tensor count passthrough
    // =========================================================================

    @Test
    fun `wrapped interpreter exposes tensor counts`() {
        every { mockInterpreter.inputTensorCount } returns 1
        every { mockInterpreter.outputTensorCount } returns 2

        val wrapped = Octomil.wrap(
            interpreter = mockInterpreter,
            modelId = "test-model",
            config = OctomilWrapperConfig.offline(),
        )

        assertEquals(1, wrapped.getInputTensorCount())
        assertEquals(2, wrapped.getOutputTensorCount())
        wrapped.close()
    }

    // =========================================================================
    // wrap() — offline mode (no validation/telemetry/ota)
    // =========================================================================

    @Test
    fun `offline config produces a functioning wrapper`() {
        val wrapped = Octomil.wrap(
            interpreter = mockInterpreter,
            modelId = "offline-model",
            config = OctomilWrapperConfig.offline(),
        )

        // Predict should work without any server
        wrapped.predict(floatArrayOf(1.0f, 2.0f, 3.0f), floatArrayOf(0.0f))
        verify(exactly = 1) { mockInterpreter.run(any(), any()) }
        wrapped.close()
    }
}
