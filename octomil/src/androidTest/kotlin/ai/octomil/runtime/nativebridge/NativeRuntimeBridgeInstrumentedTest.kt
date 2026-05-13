package ai.octomil.runtime.nativebridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRuntimeBridgeInstrumentedTest {
    @Test
    fun nativeBridgeLoadsOnDeviceAndFailsClosedWithoutRuntimeArtifact() {
        val result = NativeRuntimeBridge().open()

        when (result) {
            is NativeRuntimeResult.Success -> {
                val caps = result.value.capabilities()
                assertTrue(caps is NativeRuntimeResult.Success || caps is NativeRuntimeResult.Error)
                result.value.close()
            }
            is NativeRuntimeResult.Skipped -> {
                assertEquals(NativeRuntimeStatus.RUNTIME_UNAVAILABLE, result.reason.status)
                assertTrue(result.reason.message.contains("liboctomil_runtime"))
            }
            is NativeRuntimeResult.Error -> {
                assertTrue(result.error.message.isNotBlank())
            }
        }
    }
}
