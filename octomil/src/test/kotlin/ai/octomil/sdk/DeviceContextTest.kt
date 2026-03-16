package ai.octomil.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceContextTest {

    private fun createContext(
        installationId: String = "test-install-id",
        orgId: String? = null,
        appId: String? = null,
    ) = DeviceContext(installationId = installationId, orgId = orgId, appId = appId)

    // =========================================================================
    // Initial state
    // =========================================================================

    @Test
    fun `initial registrationState is PENDING`() = runTest {
        val ctx = createContext()
        assertEquals(RegistrationState.PENDING, ctx.registrationState.first())
    }

    @Test
    fun `initial tokenState is None`() = runTest {
        val ctx = createContext()
        assertEquals(TokenState.None, ctx.tokenState.first())
    }

    @Test
    fun `initial isRegistered is false`() {
        val ctx = createContext()
        assertEquals(false, ctx.isRegistered)
    }

    @Test
    fun `initial serverDeviceId is null`() {
        val ctx = createContext()
        assertNull(ctx.serverDeviceId)
    }

    // =========================================================================
    // updateRegistered
    // =========================================================================

    @Test
    fun `updateRegistered sets registrationState to REGISTERED`() = runTest {
        val ctx = createContext()
        ctx.updateRegistered("server-id-1", "access-token-abc", expiresAt = Long.MAX_VALUE)

        assertEquals(RegistrationState.REGISTERED, ctx.registrationState.first())
        assertTrue(ctx.isRegistered)
    }

    @Test
    fun `updateRegistered stores serverDeviceId`() {
        val ctx = createContext()
        ctx.updateRegistered("server-id-1", "access-token-abc", expiresAt = Long.MAX_VALUE)

        assertEquals("server-id-1", ctx.serverDeviceId)
    }

    @Test
    fun `updateRegistered sets token to Valid`() = runTest {
        val ctx = createContext()
        val expiresAt = System.currentTimeMillis() + 3600_000
        ctx.updateRegistered("server-id-1", "token-xyz", expiresAt)

        val token = ctx.tokenState.first()
        assertTrue(token is TokenState.Valid)
        assertEquals("token-xyz", token.accessToken)
        assertEquals(expiresAt, token.expiresAt)
    }

    // =========================================================================
    // updateToken
    // =========================================================================

    @Test
    fun `updateToken sets Valid token without changing registrationState`() = runTest {
        val ctx = createContext()
        // Start in PENDING
        assertEquals(RegistrationState.PENDING, ctx.registrationState.first())

        ctx.updateToken("refreshed-token", expiresAt = Long.MAX_VALUE)

        val token = ctx.tokenState.first()
        assertTrue(token is TokenState.Valid)
        assertEquals("refreshed-token", token.accessToken)
        // registrationState should still be PENDING
        assertEquals(RegistrationState.PENDING, ctx.registrationState.first())
    }

    @Test
    fun `updateToken on already-registered context preserves REGISTERED state`() = runTest {
        val ctx = createContext()
        ctx.updateRegistered("server-1", "old-token", Long.MAX_VALUE)
        assertEquals(RegistrationState.REGISTERED, ctx.registrationState.first())

        ctx.updateToken("new-token", Long.MAX_VALUE)

        assertEquals(RegistrationState.REGISTERED, ctx.registrationState.first())
        val token = ctx.tokenState.first()
        assertTrue(token is TokenState.Valid)
        assertEquals("new-token", token.accessToken)
    }

    // =========================================================================
    // markFailed
    // =========================================================================

    @Test
    fun `markFailed sets registrationState to FAILED`() = runTest {
        val ctx = createContext()
        ctx.markFailed()
        assertEquals(RegistrationState.FAILED, ctx.registrationState.first())
    }

    @Test
    fun `markFailed does not alter tokenState`() = runTest {
        val ctx = createContext()
        ctx.updateToken("some-token", Long.MAX_VALUE)
        ctx.markFailed()

        val token = ctx.tokenState.first()
        assertTrue(token is TokenState.Valid)
        assertEquals("some-token", token.accessToken)
    }

    // =========================================================================
    // markTokenExpired
    // =========================================================================

    @Test
    fun `markTokenExpired sets tokenState to Expired`() = runTest {
        val ctx = createContext()
        ctx.updateToken("active-token", Long.MAX_VALUE)
        ctx.markTokenExpired()

        assertEquals(TokenState.Expired, ctx.tokenState.first())
    }

    // =========================================================================
    // authHeaders
    // =========================================================================

    @Test
    fun `authHeaders returns null when tokenState is None`() {
        val ctx = createContext()
        assertNull(ctx.authHeaders())
    }

    @Test
    fun `authHeaders returns Bearer header when token is Valid`() {
        val ctx = createContext()
        ctx.updateToken("my-access-token", expiresAt = Long.MAX_VALUE)

        val headers = ctx.authHeaders()
        assertNotNull(headers)
        assertEquals("Bearer my-access-token", headers["Authorization"])
        assertEquals(1, headers.size)
    }

    @Test
    fun `authHeaders returns null when token is Expired`() {
        val ctx = createContext()
        ctx.updateToken("token", Long.MAX_VALUE)
        ctx.markTokenExpired()

        assertNull(ctx.authHeaders())
    }

    // =========================================================================
    // telemetryResource
    // =========================================================================

    @Test
    fun `telemetryResource includes installationId and platform`() {
        val ctx = createContext(installationId = "install-uuid-123")
        val resource = ctx.telemetryResource()

        assertEquals("install-uuid-123", resource["device.id"])
        assertEquals("android", resource["platform"])
    }

    @Test
    fun `telemetryResource includes orgId when present`() {
        val ctx = createContext(orgId = "org-abc")
        val resource = ctx.telemetryResource()

        assertEquals("org-abc", resource["org.id"])
    }

    @Test
    fun `telemetryResource excludes orgId when null`() {
        val ctx = createContext(orgId = null)
        val resource = ctx.telemetryResource()

        assertTrue("org.id" !in resource)
    }

    @Test
    fun `telemetryResource includes appId when present`() {
        val ctx = createContext(appId = "app-xyz")
        val resource = ctx.telemetryResource()

        assertEquals("app-xyz", resource["app.id"])
    }

    @Test
    fun `telemetryResource excludes appId when null`() {
        val ctx = createContext(appId = null)
        val resource = ctx.telemetryResource()

        assertTrue("app.id" !in resource)
    }

    // =========================================================================
    // installationId format
    // =========================================================================

    @Test
    fun `installationId is stored as provided`() {
        val uuid = java.util.UUID.randomUUID().toString()
        val ctx = createContext(installationId = uuid)
        assertEquals(uuid, ctx.installationId)
    }

    // =========================================================================
    // restoreCachedToken
    // =========================================================================

    @Test
    fun `restoreCachedToken sets Valid when not expired`() = runTest {
        val ctx = createContext()
        val futureExpiry = System.currentTimeMillis() + 3600_000
        ctx.restoreCachedToken("cached-token", futureExpiry)

        val token = ctx.tokenState.first()
        assertTrue(token is TokenState.Valid)
        assertEquals("cached-token", token.accessToken)
        // registrationState stays PENDING
        assertEquals(RegistrationState.PENDING, ctx.registrationState.first())
    }

    @Test
    fun `restoreCachedToken sets Expired when past expiry`() = runTest {
        val ctx = createContext()
        val pastExpiry = System.currentTimeMillis() - 1000
        ctx.restoreCachedToken("stale-token", pastExpiry)

        assertEquals(TokenState.Expired, ctx.tokenState.first())
        assertEquals(RegistrationState.PENDING, ctx.registrationState.first())
    }
}
