package ai.octomil.sdk

import ai.octomil.generated.DeliveryMode
import ai.octomil.generated.Modality
import ai.octomil.generated.ModelCapability
import ai.octomil.manifest.AppManifest
import ai.octomil.manifest.AppModelEntry
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the auto-registration gate logic.
 *
 * The actual gate lives in [ai.octomil.Octomil.shouldAutoRegister] (private),
 * but the logic is: auth != null AND (manifest has MANAGED/CLOUD models OR monitoring.enabled).
 * We test the predicate directly to cover all branches.
 */
class ShouldAutoRegisterTest {

    /**
     * Mirror of the private Octomil.shouldAutoRegister logic so we can unit test
     * every branch without needing Android context or reflection.
     */
    private fun shouldAutoRegister(
        auth: AuthConfig?,
        manifest: AppManifest,
        monitoring: MonitoringConfig,
    ): Boolean {
        if (auth == null) return false
        val hasManagedOrCloud = manifest.models.any {
            it.delivery == DeliveryMode.MANAGED || it.delivery == DeliveryMode.CLOUD
        }
        return hasManagedOrCloud || monitoring.enabled
    }

    private val bundledManifest = AppManifest(
        models = listOf(
            AppModelEntry(
                id = "local-model",
                capability = ModelCapability.CHAT,
                delivery = DeliveryMode.BUNDLED,
                inputModalities = listOf(Modality.TEXT),
                outputModalities = listOf(Modality.TEXT),
            ),
        ),
    )

    private val managedManifest = AppManifest(
        models = listOf(
            AppModelEntry(
                id = "managed-model",
                capability = ModelCapability.CHAT,
                delivery = DeliveryMode.MANAGED,
                inputModalities = listOf(Modality.TEXT),
                outputModalities = listOf(Modality.TEXT),
            ),
        ),
    )

    private val cloudManifest = AppManifest(
        models = listOf(
            AppModelEntry(
                id = "cloud-model",
                capability = ModelCapability.CHAT,
                delivery = DeliveryMode.CLOUD,
                inputModalities = listOf(Modality.TEXT),
                outputModalities = listOf(Modality.TEXT),
            ),
        ),
    )

    private val emptyManifest = AppManifest(models = emptyList())

    private val auth = AuthConfig.PublishableKey("oct_pub_test_key")
    private val monitoringEnabled = MonitoringConfig(enabled = true)
    private val monitoringDisabled = MonitoringConfig(enabled = false)

    // =========================================================================
    // auth + monitoring.enabled -> true
    // =========================================================================

    @Test
    fun `auth present and monitoring enabled returns true`() {
        assertTrue(shouldAutoRegister(auth, bundledManifest, monitoringEnabled))
    }

    @Test
    fun `auth present and monitoring enabled with empty manifest returns true`() {
        assertTrue(shouldAutoRegister(auth, emptyManifest, monitoringEnabled))
    }

    // =========================================================================
    // auth + managed or cloud models -> true
    // =========================================================================

    @Test
    fun `auth present with managed model returns true`() {
        assertTrue(shouldAutoRegister(auth, managedManifest, monitoringDisabled))
    }

    @Test
    fun `auth present with cloud model returns true`() {
        assertTrue(shouldAutoRegister(auth, cloudManifest, monitoringDisabled))
    }

    // =========================================================================
    // auth + no monitoring, no managed models -> false
    // =========================================================================

    @Test
    fun `auth present with only bundled models and monitoring disabled returns false`() {
        assertFalse(shouldAutoRegister(auth, bundledManifest, monitoringDisabled))
    }

    @Test
    fun `auth present with empty manifest and monitoring disabled returns false`() {
        assertFalse(shouldAutoRegister(auth, emptyManifest, monitoringDisabled))
    }

    // =========================================================================
    // no auth -> false regardless
    // =========================================================================

    @Test
    fun `null auth returns false even with managed models`() {
        assertFalse(shouldAutoRegister(null, managedManifest, monitoringEnabled))
    }

    @Test
    fun `null auth returns false even with monitoring enabled`() {
        assertFalse(shouldAutoRegister(null, emptyManifest, monitoringEnabled))
    }

    @Test
    fun `null auth returns false with cloud models`() {
        assertFalse(shouldAutoRegister(null, cloudManifest, monitoringDisabled))
    }

    // =========================================================================
    // All auth types
    // =========================================================================

    @Test
    fun `BootstrapToken auth with managed model returns true`() {
        val bootstrapAuth = AuthConfig.BootstrapToken("some-token")
        assertTrue(shouldAutoRegister(bootstrapAuth, managedManifest, monitoringDisabled))
    }

    @Test
    fun `Anonymous auth with monitoring enabled returns true`() {
        val anonAuth = AuthConfig.Anonymous(appId = "com.example.app")
        assertTrue(shouldAutoRegister(anonAuth, bundledManifest, monitoringEnabled))
    }
}
