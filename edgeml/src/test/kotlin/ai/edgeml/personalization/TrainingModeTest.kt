package ai.edgeml.personalization

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrainingModeTest {
    @Test
    fun `LOCAL_ONLY does not upload to server`() {
        assertFalse(TrainingMode.LOCAL_ONLY.uploadsToServer)
    }

    @Test
    fun `FEDERATED uploads to server`() {
        assertTrue(TrainingMode.FEDERATED.uploadsToServer)
    }

    @Test
    fun `LOCAL_ONLY has maximum privacy level`() {
        assertEquals("Maximum", TrainingMode.LOCAL_ONLY.privacyLevel)
    }

    @Test
    fun `FEDERATED has high privacy level`() {
        assertEquals("High", TrainingMode.FEDERATED.privacyLevel)
    }

    @Test
    fun `LOCAL_ONLY transmits zero bytes`() {
        assertEquals("0 bytes", TrainingMode.LOCAL_ONLY.dataTransmitted)
    }

    @Test
    fun `FEDERATED transmits encrypted weight deltas only`() {
        assertEquals("Encrypted weight deltas only", TrainingMode.FEDERATED.dataTransmitted)
    }

    @Test
    fun `LOCAL_ONLY has user-facing description`() {
        assertTrue(TrainingMode.LOCAL_ONLY.description.contains("never leaves"))
    }

    @Test
    fun `FEDERATED has user-facing description`() {
        assertTrue(TrainingMode.FEDERATED.description.contains("millions"))
    }

    @Test
    fun `enum has exactly two values`() {
        assertEquals(2, TrainingMode.entries.size)
    }

    @Test
    fun `valueOf round-trips correctly`() {
        for (mode in TrainingMode.entries) {
            assertEquals(mode, TrainingMode.valueOf(mode.name))
        }
    }
}
