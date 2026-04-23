package ai.octomil.runtime.routing

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for [ModelRefParser] -- verifies all canonical model ref kinds are
 * produced correctly and deployment/experiment refs extract IDs for telemetry.
 */
class ModelRefParserTest {

    // =========================================================================
    // Plain model
    // =========================================================================

    @Test
    fun `plain model name returns ModelRef kind`() {
        val ref = ModelRefParser.parse("gemma3-1b")
        assertIs<ParsedModelRef.ModelRef>(ref)
        assertEquals("model", ref.kind)
        assertEquals("gemma3-1b", ref.model)
    }

    @Test
    fun `blank string returns DefaultRef`() {
        val ref = ModelRefParser.parse("  ")
        assertIs<ParsedModelRef.DefaultRef>(ref)
        assertEquals("default", ref.kind)
    }

    // =========================================================================
    // App refs
    // =========================================================================

    @Test
    fun `app ref parses slug and capability`() {
        val ref = ModelRefParser.parse("@app/my-app/chat")
        assertIs<ParsedModelRef.AppRef>(ref)
        assertEquals("app", ref.kind)
        assertEquals("my-app", ref.slug)
        assertEquals("chat", ref.capability)
    }

    @Test
    fun `malformed app ref returns UnknownRef`() {
        val ref = ModelRefParser.parse("@app/only-slug")
        assertIs<ParsedModelRef.UnknownRef>(ref)
        assertEquals("unknown", ref.kind)
    }

    // =========================================================================
    // Capability refs
    // =========================================================================

    @Test
    fun `capability ref parses capability`() {
        val ref = ModelRefParser.parse("@capability/embeddings")
        assertIs<ParsedModelRef.CapabilityRef>(ref)
        assertEquals("capability", ref.kind)
        assertEquals("embeddings", ref.capability)
    }

    // =========================================================================
    // Deployment refs
    // =========================================================================

    @Test
    fun `deploy_ prefix returns DeploymentRef`() {
        val ref = ModelRefParser.parse("deploy_ios-chat-prod")
        assertIs<ParsedModelRef.DeploymentRef>(ref)
        assertEquals("deployment", ref.kind)
        assertEquals("deploy_ios-chat-prod", ref.deploymentId)
    }

    @Test
    fun `deploy_ prefix extracts full ref as deploymentId`() {
        val ref = ModelRefParser.parse("deploy_staging-embed-v2")
        assertIs<ParsedModelRef.DeploymentRef>(ref)
        assertEquals("deploy_staging-embed-v2", ref.deploymentId)
    }

    // =========================================================================
    // Experiment refs
    // =========================================================================

    @Test
    fun `exp_ prefix with variant returns ExperimentRef`() {
        val ref = ModelRefParser.parse("exp_chat_test/treatment_a")
        assertIs<ParsedModelRef.ExperimentRef>(ref)
        assertEquals("experiment", ref.kind)
        assertEquals("exp_chat_test", ref.experimentId)
        assertEquals("treatment_a", ref.variantId)
    }

    @Test
    fun `exp_ prefix without slash does not match experiment`() {
        // exp_xxx without a slash is not an experiment ref -- treated as plain model
        val ref = ModelRefParser.parse("exp_no_slash")
        assertIs<ParsedModelRef.ModelRef>(ref)
        assertEquals("model", ref.kind)
    }

    // =========================================================================
    // Alias refs
    // =========================================================================

    @Test
    fun `alias prefix returns AliasRef`() {
        val ref = ModelRefParser.parse("alias:my-model")
        assertIs<ParsedModelRef.AliasRef>(ref)
        assertEquals("alias", ref.kind)
    }

    // =========================================================================
    // Unknown refs
    // =========================================================================

    @Test
    fun `unknown at-prefix returns UnknownRef`() {
        val ref = ModelRefParser.parse("@unknown/something")
        assertIs<ParsedModelRef.UnknownRef>(ref)
        assertEquals("unknown", ref.kind)
    }

    @Test
    fun `URL-like string returns UnknownRef`() {
        val ref = ModelRefParser.parse("https://example.com/model")
        assertIs<ParsedModelRef.UnknownRef>(ref)
        assertEquals("unknown", ref.kind)
    }

    // =========================================================================
    // Canonical kinds completeness
    // =========================================================================

    @Test
    fun `all 8 canonical kinds are reachable`() {
        val kinds = setOf(
            ModelRefParser.parse("gemma3-1b").kind,              // model
            ModelRefParser.parse("@app/my-app/chat").kind,       // app
            ModelRefParser.parse("@capability/chat").kind,        // capability
            ModelRefParser.parse("deploy_prod").kind,             // deployment
            ModelRefParser.parse("exp_test/control").kind,        // experiment
            ModelRefParser.parse("alias:my-model").kind,          // alias
            ModelRefParser.parse("  ").kind,                       // default
            ModelRefParser.parse("@unknown/something").kind,      // unknown
        )
        assertEquals(
            setOf("model", "app", "capability", "deployment", "experiment", "alias", "default", "unknown"),
            kinds,
        )
    }
}
