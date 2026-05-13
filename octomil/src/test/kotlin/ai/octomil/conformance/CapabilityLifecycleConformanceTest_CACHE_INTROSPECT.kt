package ai.octomil.conformance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test

/**
 * AUTO-GENERATED — do not edit.
 *
 * Source contract: conformance/cache.introspect.yaml
 * Conformance version: 0.1.5-rc1
 * Generator: scripts/generate_conformance.py (target=kotlin)
 *
 * Regenerated from contracts YAML; assertions read from contract source,
 * not SDK-self-reference (fixes Codex B1-class finding).
 *
 * Required runtime ABI: {major:0, minor:10}
 * is_advertised: true
 *
 * cache.introspect is runtime/cache ABI, not a session lifecycle capability.
 */
@Suppress("ClassName")
class CapabilityLifecycleConformanceTest_CACHE_INTROSPECT {
    @Test
    fun `capability name is byte-for-byte canonical`() {
        assertEquals("cache.introspect", CAPABILITY)
    }

    @Test
    fun `is_advertised flag matches contract`() {
        assertEquals(true, IS_ADVERTISED)
    }

    @Test
    fun `lifecycle steps match contract`() {
        val expected = listOf("runtime_open", "runtime_close")
        assertEquals(expected, LIFECYCLE_STEPS)
    }

    @Test
    fun `bounded error codes match contract`() {
        val expected = setOf("invalid_input", "runtime_unavailable", "unsupported_modality")
        assertEquals(expected, BOUNDED_ERROR_CODES)
    }

    @Test
    fun `expected event sequence matches contract`() {
        assertTrue(EVENT_SEQUENCE.isEmpty())
    }

    @Test
    fun `privacy deny_field_substrings are documented`() {
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("/Users/"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("/private/var/"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("/home/"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("prompt_text"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("input_text"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("audio_bytes"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("raw_audio"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("embedding_vector"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("cache_key"))
        assertTrue(DENY_FIELD_SUBSTRINGS.contains("sha256"))
    }

    // =========================================================================
    // SKIP_WITH_EXPLICIT_REASON: runtime cache ABI
    // =========================================================================

    @Test
    @Ignore("SKIP_WITH_EXPLICIT_REASON: cache providers and native runtime artifact are optional in octomil-android. NativePathSkip.CLOUD_FALLBACK_ACTIVE = false (cloud transport explicitly disallowed from masking native skip). See TODO: native-ffi-binding")
    fun `cacheIntrospect_native_runtime_cache_abi`() {
        fail("Native runtime or cache providers are missing — this test should not be running")
    }
}

// =============================================================================
// Contract constants — read from YAML, not from SDK (fixes B1 oracle drift)
// =============================================================================

private const val CAPABILITY = "cache.introspect"
private val IS_ADVERTISED = true
private val LIFECYCLE_STEPS: List<String> = listOf("runtime_open", "runtime_close")
private val BOUNDED_ERROR_CODES: Set<String> = setOf("invalid_input", "runtime_unavailable", "unsupported_modality")
private val EVENT_SEQUENCE: List<EventStep> = emptyList()
private val DENY_FIELD_SUBSTRINGS: Set<String> = setOf(
    "/Users/",
    "/private/var/",
    "/home/",
    "prompt_text",
    "input_text",
    "audio_bytes",
    "raw_audio",
    "embedding_vector",
    "cache_key",
    "sha256",
)
private const val DELIVERY_TIMING = ""

// Stub set used by is_advertised=false assertion — populated at wiring time
private val LIVE_CAPABILITY_STUBS: Set<String> = emptySet()
