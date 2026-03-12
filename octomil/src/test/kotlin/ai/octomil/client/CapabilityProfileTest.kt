package ai.octomil.client

import ai.octomil.generated.DeviceClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CapabilityProfileTest {

    @Test
    fun `data class equality`() {
        val profile1 = CapabilityProfile(
            deviceClass = DeviceClass.FLAGSHIP,
            availableRuntimes = listOf("tflite", "nnapi", "gpu"),
            memoryMb = 8192,
            storageMb = 4096,
            platform = "android",
            accelerators = listOf("xnnpack", "nnapi", "gpu"),
        )
        val profile2 = profile1.copy()

        assertEquals(profile1, profile2)
    }

    @Test
    fun `data class inequality on deviceClass`() {
        val flagship = CapabilityProfile(
            deviceClass = DeviceClass.FLAGSHIP,
            availableRuntimes = listOf("tflite"),
            memoryMb = 8192,
            storageMb = 4096,
            accelerators = emptyList(),
        )
        val low = flagship.copy(deviceClass = DeviceClass.LOW)

        assertNotEquals(flagship, low)
    }

    @Test
    fun `platform defaults to android`() {
        val profile = CapabilityProfile(
            deviceClass = DeviceClass.MID,
            availableRuntimes = listOf("tflite"),
            memoryMb = 4096,
            storageMb = 2048,
            accelerators = listOf("xnnpack"),
        )
        assertEquals("android", profile.platform)
    }
}
