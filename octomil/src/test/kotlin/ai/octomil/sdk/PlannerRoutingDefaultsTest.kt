package ai.octomil.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerRoutingDefaultsTest {

    // =========================================================================
    // Default behavior: planner ON with credentials
    // =========================================================================

    @Test
    fun `planner enabled by default with OrgApiKey credentials`() {
        val result = PlannerRoutingDefaults.resolve(
            explicitOverride = null,
            authConfig = AuthConfig.OrgApiKey(apiKey = "edg_test_123", orgId = "org_abc"),
            serverUrl = "https://api.octomil.com",
        )
        assertTrue("Planner should be ON when OrgApiKey credentials exist", result)
    }

    @Test
    fun `planner enabled by default with PublishableKey credentials`() {
        val result = PlannerRoutingDefaults.resolve(
            explicitOverride = null,
            authConfig = AuthConfig.PublishableKey(key = "oct_pub_test_abc123"),
            serverUrl = "https://api.octomil.com",
        )
        assertTrue("Planner should be ON when PublishableKey credentials exist", result)
    }

    @Test
    fun `planner enabled by default with BootstrapToken credentials`() {
        val result = PlannerRoutingDefaults.resolve(
            explicitOverride = null,
            authConfig = AuthConfig.BootstrapToken(token = "bt_test_token"),
            serverUrl = "https://api.octomil.com",
        )
        assertTrue("Planner should be ON when BootstrapToken credentials exist", result)
    }

    // =========================================================================
    // Default behavior: planner OFF without credentials
    // =========================================================================

    @Test
    fun `planner disabled by default with Anonymous auth`() {
        val result = PlannerRoutingDefaults.resolve(
            explicitOverride = null,
            authConfig = AuthConfig.Anonymous(appId = "app_test"),
            serverUrl = "https://api.octomil.com",
        )
        assertFalse("Planner should be OFF with Anonymous auth", result)
    }

    @Test
    fun `planner disabled by default with blank server url`() {
        val result = PlannerRoutingDefaults.resolve(
            explicitOverride = null,
            authConfig = AuthConfig.OrgApiKey(apiKey = "edg_test_123", orgId = "org_abc"),
            serverUrl = "",
        )
        assertFalse("Planner should be OFF when serverUrl is blank", result)
    }

    @Test
    fun `planner disabled by default with blank api key`() {
        val result = PlannerRoutingDefaults.resolve(
            explicitOverride = null,
            authConfig = AuthConfig.OrgApiKey(apiKey = "", orgId = "org_abc"),
            serverUrl = "https://api.octomil.com",
        )
        assertFalse("Planner should be OFF when apiKey is blank", result)
    }

    // =========================================================================
    // Explicit override
    // =========================================================================

    @Test
    fun `explicit false disables planner even with credentials`() {
        val result = PlannerRoutingDefaults.resolve(
            explicitOverride = false,
            authConfig = AuthConfig.OrgApiKey(apiKey = "edg_test_123", orgId = "org_abc"),
            serverUrl = "https://api.octomil.com",
        )
        assertFalse("Explicit false should disable planner", result)
    }

    @Test
    fun `explicit true enables planner even without credentials`() {
        val result = PlannerRoutingDefaults.resolve(
            explicitOverride = true,
            authConfig = AuthConfig.Anonymous(appId = "app_test"),
            serverUrl = "https://api.octomil.com",
        )
        assertTrue("Explicit true should enable planner", result)
    }

    // =========================================================================
    // Privacy: private and local_only block cloud
    // =========================================================================

    @Test
    fun `private policy blocks cloud routing`() {
        assertTrue(PlannerRoutingDefaults.isCloudBlocked("private"))
    }

    @Test
    fun `local_only policy blocks cloud routing`() {
        assertTrue(PlannerRoutingDefaults.isCloudBlocked("local_only"))
    }

    @Test
    fun `cloud_first policy does not block cloud routing`() {
        assertFalse(PlannerRoutingDefaults.isCloudBlocked("cloud_first"))
    }

    @Test
    fun `local_first policy does not block cloud routing`() {
        assertFalse(PlannerRoutingDefaults.isCloudBlocked("local_first"))
    }

    @Test
    fun `null policy does not block cloud routing`() {
        assertFalse(PlannerRoutingDefaults.isCloudBlocked(null))
    }

    // =========================================================================
    // Policy validation
    // =========================================================================

    @Test
    fun `validatePolicy returns auto when planner enabled and no explicit policy`() {
        assertEquals("auto", PlannerRoutingDefaults.validatePolicy(null, plannerEnabled = true))
    }

    @Test
    fun `validatePolicy returns local_first when planner disabled and no explicit policy`() {
        assertEquals("local_first", PlannerRoutingDefaults.validatePolicy(null, plannerEnabled = false))
    }

    @Test
    fun `validatePolicy preserves explicit policy`() {
        assertEquals("private", PlannerRoutingDefaults.validatePolicy("private", plannerEnabled = true))
        assertEquals("cloud_only", PlannerRoutingDefaults.validatePolicy("cloud_only", plannerEnabled = false))
    }
}
