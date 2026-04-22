package ai.octomil.runtime.planner

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for planner source normalization.
 *
 * Verifies that all SDK output boundaries emit only canonical planner_source
 * values: "server", "cache", "offline". Non-canonical aliases must be
 * normalized before they reach the wire.
 */
class PlannerSourceNormalizationTest {

    // -------------------------------------------------------------------------
    // PlannerSourceNormalizer.normalize
    // -------------------------------------------------------------------------

    @Test
    fun `canonical values pass through unchanged`() {
        assertEquals("server", PlannerSourceNormalizer.normalize("server"))
        assertEquals("cache", PlannerSourceNormalizer.normalize("cache"))
        assertEquals("offline", PlannerSourceNormalizer.normalize("offline"))
    }

    @Test
    fun `local_default maps to offline`() {
        assertEquals("offline", PlannerSourceNormalizer.normalize("local_default"))
    }

    @Test
    fun `server_plan maps to server`() {
        assertEquals("server", PlannerSourceNormalizer.normalize("server_plan"))
    }

    @Test
    fun `cached maps to cache`() {
        assertEquals("cache", PlannerSourceNormalizer.normalize("cached"))
    }

    @Test
    fun `fallback maps to offline`() {
        assertEquals("offline", PlannerSourceNormalizer.normalize("fallback"))
    }

    @Test
    fun `none maps to offline`() {
        assertEquals("offline", PlannerSourceNormalizer.normalize("none"))
    }

    @Test
    fun `local_benchmark maps to offline`() {
        assertEquals("offline", PlannerSourceNormalizer.normalize("local_benchmark"))
    }

    @Test
    fun `empty string maps to offline`() {
        assertEquals("offline", PlannerSourceNormalizer.normalize(""))
    }

    @Test
    fun `unknown value maps to offline`() {
        assertEquals("offline", PlannerSourceNormalizer.normalize("custom_source"))
    }

    // -------------------------------------------------------------------------
    // PlannerSourceNormalizer.canonicalSources
    // -------------------------------------------------------------------------

    @Test
    fun `canonical sources contains exactly three values`() {
        assertEquals(3, PlannerSourceNormalizer.canonicalSources.size)
        assertTrue(PlannerSourceNormalizer.canonicalSources.contains("server"))
        assertTrue(PlannerSourceNormalizer.canonicalSources.contains("cache"))
        assertTrue(PlannerSourceNormalizer.canonicalSources.contains("offline"))
    }

    @Test
    fun `canonical sources does not contain aliases`() {
        val nonCanonical = listOf("local_default", "server_plan", "cached", "fallback", "none")
        for (value in nonCanonical) {
            assertFalse(
                PlannerSourceNormalizer.canonicalSources.contains(value),
                "$value should not be in canonical sources",
            )
        }
    }

    // -------------------------------------------------------------------------
    // RouteMetadata.fromSelection normalization
    // -------------------------------------------------------------------------

    @Test
    fun `fromSelection normalizes server_plan to server`() {
        val selection = RuntimeSelection(
            locality = "cloud",
            source = "server_plan",
            reason = "server plan selected",
        )
        val metadata = RouteMetadata.fromSelection(selection)
        assertEquals("server", metadata.planner.source)
    }

    @Test
    fun `fromSelection normalizes cache to cache`() {
        val selection = RuntimeSelection(
            locality = "cloud",
            source = "cache",
            reason = "cached plan",
        )
        val metadata = RouteMetadata.fromSelection(selection)
        assertEquals("cache", metadata.planner.source)
    }

    @Test
    fun `fromSelection normalizes local_default to offline`() {
        val selection = RuntimeSelection(
            locality = "local",
            engine = "litert",
            source = "local_default",
            reason = "offline default",
        )
        val metadata = RouteMetadata.fromSelection(selection)
        assertEquals("offline", metadata.planner.source)
    }

    @Test
    fun `fromSelection normalizes fallback to offline`() {
        val selection = RuntimeSelection(
            locality = "cloud",
            source = "fallback",
            reason = "fallback to cloud",
        )
        val metadata = RouteMetadata.fromSelection(selection)
        assertEquals("offline", metadata.planner.source)
    }

    @Test
    fun `fromSelection normalizes none to offline`() {
        val selection = RuntimeSelection(
            locality = "cloud",
            source = "none",
            reason = "no source",
        )
        val metadata = RouteMetadata.fromSelection(selection)
        assertEquals("offline", metadata.planner.source)
    }

    @Test
    fun `fromSelection normalizes empty string to offline`() {
        val selection = RuntimeSelection(
            locality = "cloud",
            source = "",
            reason = "no source",
        )
        val metadata = RouteMetadata.fromSelection(selection)
        assertEquals("offline", metadata.planner.source)
    }

    // -------------------------------------------------------------------------
    // Cross-SDK serialization shape
    // -------------------------------------------------------------------------

    @Test
    fun `all known aliases normalize to a canonical value`() {
        val aliases = listOf(
            "server_plan", "local_default", "cached", "fallback",
            "none", "local_benchmark", "",
        )
        for (alias in aliases) {
            val normalized = PlannerSourceNormalizer.normalize(alias)
            assertTrue(
                PlannerSourceNormalizer.canonicalSources.contains(normalized),
                "'$alias' normalized to '$normalized' which is not canonical",
            )
        }
    }
}
