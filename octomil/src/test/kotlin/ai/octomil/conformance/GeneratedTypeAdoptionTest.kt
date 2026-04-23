package ai.octomil.conformance

import ai.octomil.generated.ArtifactCacheStatus
import ai.octomil.generated.ExecutionMode
import ai.octomil.generated.FallbackTriggerStage
import ai.octomil.generated.ModelRefKind
import ai.octomil.generated.PlannerSource
import ai.octomil.generated.RouteLocality
import ai.octomil.generated.RouteMode
import ai.octomil.generated.RoutingPolicy
import ai.octomil.generated.RuntimeExecutor
import ai.octomil.runtime.planner.PlannerSourceNormalizer
import ai.octomil.runtime.planner.RoutingPolicyNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract-generated type adoption conformance tests.
 *
 * Verifies that generated enum values from octomil-contracts match the
 * expected canonical values used across all SDKs, and that hand-maintained
 * types are properly backed by generated equivalents.
 */
class GeneratedTypeAdoptionTest {

    // -- PlannerSource -------------------------------------------------------

    @Test
    fun `PlannerSource has exactly 3 canonical values`() {
        assertEquals("server", PlannerSource.SERVER.code)
        assertEquals("cache", PlannerSource.CACHE.code)
        assertEquals("offline", PlannerSource.OFFLINE.code)
        assertEquals(3, PlannerSource.entries.size)
    }

    @Test
    fun `PlannerSourceNormalizer canonical sources backed by generated enum`() {
        assertEquals(3, PlannerSourceNormalizer.canonicalSources.size)
        assertTrue(PlannerSourceNormalizer.canonicalSources.contains(PlannerSource.SERVER.code))
        assertTrue(PlannerSourceNormalizer.canonicalSources.contains(PlannerSource.CACHE.code))
        assertTrue(PlannerSourceNormalizer.canonicalSources.contains(PlannerSource.OFFLINE.code))
    }

    @Test
    fun `PlannerSourceNormalizer normalizes aliases`() {
        assertEquals("server", PlannerSourceNormalizer.normalize("server"))
        assertEquals("cache", PlannerSourceNormalizer.normalize("cache"))
        assertEquals("offline", PlannerSourceNormalizer.normalize("offline"))
        assertEquals("server", PlannerSourceNormalizer.normalize("server_plan"))
        assertEquals("cache", PlannerSourceNormalizer.normalize("cached"))
        assertEquals("offline", PlannerSourceNormalizer.normalize("local_default"))
        assertEquals("offline", PlannerSourceNormalizer.normalize(""))
        assertEquals("offline", PlannerSourceNormalizer.normalize("unknown_value"))
    }

    // -- ModelRefKind --------------------------------------------------------

    @Test
    fun `ModelRefKind has all 8 canonical kinds`() {
        assertEquals("model", ModelRefKind.MODEL.code)
        assertEquals("app", ModelRefKind.APP.code)
        assertEquals("capability", ModelRefKind.CAPABILITY.code)
        assertEquals("deployment", ModelRefKind.DEPLOYMENT.code)
        assertEquals("experiment", ModelRefKind.EXPERIMENT.code)
        assertEquals("alias", ModelRefKind.ALIAS.code)
        assertEquals("default", ModelRefKind.DEFAULT.code)
        assertEquals("unknown", ModelRefKind.UNKNOWN.code)
        assertEquals(8, ModelRefKind.entries.size)
    }

    @Test
    fun `ModelRefKind fromCode round-trips all values`() {
        for (kind in ModelRefKind.entries) {
            assertEquals(kind, ModelRefKind.fromCode(kind.code))
        }
    }

    // -- RouteLocality -------------------------------------------------------

    @Test
    fun `RouteLocality has exactly 2 values`() {
        assertEquals("local", RouteLocality.LOCAL.code)
        assertEquals("cloud", RouteLocality.CLOUD.code)
        assertEquals(2, RouteLocality.entries.size)
    }

    // -- RouteMode -----------------------------------------------------------

    @Test
    fun `RouteMode has expected values`() {
        assertEquals("sdk_runtime", RouteMode.SDK_RUNTIME.code)
        assertEquals("hosted_gateway", RouteMode.HOSTED_GATEWAY.code)
        assertEquals("external_endpoint", RouteMode.EXTERNAL_ENDPOINT.code)
    }

    // -- ExecutionMode -------------------------------------------------------

    @Test
    fun `ExecutionMode has expected values`() {
        assertEquals("sdk_runtime", ExecutionMode.SDK_RUNTIME.code)
        assertEquals("hosted_gateway", ExecutionMode.HOSTED_GATEWAY.code)
        assertEquals("external_endpoint", ExecutionMode.EXTERNAL_ENDPOINT.code)
    }

    // -- RoutingPolicy -------------------------------------------------------

    @Test
    fun `RoutingPolicy has 7 values including auto`() {
        assertEquals("private", RoutingPolicy.PRIVATE.code)
        assertEquals("local_only", RoutingPolicy.LOCAL_ONLY.code)
        assertEquals("local_first", RoutingPolicy.LOCAL_FIRST.code)
        assertEquals("cloud_first", RoutingPolicy.CLOUD_FIRST.code)
        assertEquals("cloud_only", RoutingPolicy.CLOUD_ONLY.code)
        assertEquals("performance_first", RoutingPolicy.PERFORMANCE_FIRST.code)
        assertEquals("auto", RoutingPolicy.AUTO.code)
        assertEquals(7, RoutingPolicy.entries.size)
    }

    @Test
    fun `RoutingPolicyNames backed by generated RoutingPolicy enum`() {
        assertEquals(RoutingPolicy.PRIVATE.code, RoutingPolicyNames.PRIVATE)
        assertEquals(RoutingPolicy.LOCAL_ONLY.code, RoutingPolicyNames.LOCAL_ONLY)
        assertEquals(RoutingPolicy.LOCAL_FIRST.code, RoutingPolicyNames.LOCAL_FIRST)
        assertEquals(RoutingPolicy.CLOUD_FIRST.code, RoutingPolicyNames.CLOUD_FIRST)
        assertEquals(RoutingPolicy.CLOUD_ONLY.code, RoutingPolicyNames.CLOUD_ONLY)
        assertEquals(RoutingPolicy.PERFORMANCE_FIRST.code, RoutingPolicyNames.PERFORMANCE_FIRST)
    }

    @Test
    fun `RoutingPolicyNames ALL excludes auto`() {
        assertEquals(6, RoutingPolicyNames.ALL.size)
        assertFalse(RoutingPolicyNames.ALL.contains("auto"))
        assertTrue(RoutingPolicyNames.ALL.contains("private"))
        assertTrue(RoutingPolicyNames.ALL.contains("local_only"))
    }

    // -- FallbackTriggerStage ------------------------------------------------

    @Test
    fun `FallbackTriggerStage has expected values`() {
        assertEquals("policy", FallbackTriggerStage.POLICY.code)
        assertEquals("prepare", FallbackTriggerStage.PREPARE.code)
        assertEquals("gate", FallbackTriggerStage.GATE.code)
        assertEquals("inference", FallbackTriggerStage.INFERENCE.code)
        assertEquals("timeout", FallbackTriggerStage.TIMEOUT.code)
    }

    // -- ArtifactCacheStatus -------------------------------------------------

    @Test
    fun `ArtifactCacheStatus has expected values`() {
        assertEquals("hit", ArtifactCacheStatus.HIT.code)
        assertEquals("miss", ArtifactCacheStatus.MISS.code)
        assertEquals("downloaded", ArtifactCacheStatus.DOWNLOADED.code)
        assertEquals("not_applicable", ArtifactCacheStatus.NOT_APPLICABLE.code)
        assertEquals("unavailable", ArtifactCacheStatus.UNAVAILABLE.code)
    }

    // -- RuntimeExecutor -----------------------------------------------------

    @Test
    fun `RuntimeExecutor has blessed engines`() {
        assertNotNull(RuntimeExecutor.fromCode("coreml"))
        assertNotNull(RuntimeExecutor.fromCode("litert"))
        assertNotNull(RuntimeExecutor.fromCode("llamacpp"))
    }

    @Test
    fun `RuntimeExecutor has supported engines`() {
        assertNotNull(RuntimeExecutor.fromCode("mlx"))
        assertNotNull(RuntimeExecutor.fromCode("onnxruntime"))
        assertNotNull(RuntimeExecutor.fromCode("cloud"))
        assertNotNull(RuntimeExecutor.fromCode("whisper"))
    }

    @Test
    fun `RuntimeExecutor excludes Ollama`() {
        assertNull(RuntimeExecutor.fromCode("ollama"))
    }

    @Test
    fun `RuntimeExecutor has test engine`() {
        assertNotNull(RuntimeExecutor.fromCode("echo"))
    }
}
