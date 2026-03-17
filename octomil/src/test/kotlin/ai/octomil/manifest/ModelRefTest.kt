package ai.octomil.manifest

import ai.octomil.generated.ModelCapability
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ModelRefTest {

    @Test
    fun `Id wraps model name`() {
        val ref = ModelRef.Id("phi-4-mini")
        assertEquals("phi-4-mini", ref.value)
    }

    @Test
    fun `Capability wraps ModelCapability`() {
        val ref = ModelRef.Capability(ModelCapability.KEYBOARD_PREDICTION)
        assertEquals(ModelCapability.KEYBOARD_PREDICTION, ref.value)
    }

    @Test
    fun `Id equality by value`() {
        assertEquals(ModelRef.Id("abc"), ModelRef.Id("abc"))
        assertNotEquals(ModelRef.Id("abc"), ModelRef.Id("xyz"))
    }

    @Test
    fun `Capability equality by value`() {
        assertEquals(
            ModelRef.Capability(ModelCapability.CHAT),
            ModelRef.Capability(ModelCapability.CHAT),
        )
        assertNotEquals(
            ModelRef.Capability(ModelCapability.CHAT),
            ModelRef.Capability(ModelCapability.TRANSCRIPTION),
        )
    }

    @Test
    fun `Id and Capability are distinct subtypes`() {
        val id: ModelRef = ModelRef.Id("test")
        val cap: ModelRef = ModelRef.Capability(ModelCapability.CHAT)

        assertIs<ModelRef.Id>(id)
        assertIs<ModelRef.Capability>(cap)
        assertNotEquals(id, cap as ModelRef)
    }

    @Test
    fun `when expression is exhaustive over sealed class`() {
        val refs = listOf(
            ModelRef.Id("test"),
            ModelRef.Capability(ModelCapability.CHAT),
        )
        for (ref in refs) {
            val desc = when (ref) {
                is ModelRef.Id -> "id:${ref.value}"
                is ModelRef.Capability -> "cap:${ref.value.code}"
            }
            assert(desc.isNotEmpty())
        }
    }
}
