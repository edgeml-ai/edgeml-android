package ai.edgeml

import ai.edgeml.sdk.DeviceAuthManager
import ai.edgeml.sdk.DeviceAuthStateStore
import ai.edgeml.sdk.DeviceAuthTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeviceAuthManagerTest {

    @Test
    fun `bootstrap refresh revoke lifecycle`() = runBlocking {
        val transport = FakeTransport(
            responses = ArrayDeque(
                listOf(
                    tokenPayload("acc_bootstrap", "ref_bootstrap", expiresIn = 900),
                    tokenPayload("acc_refresh", "ref_refresh", expiresIn = 900),
                    JSONObject(),
                )
            )
        )
        val store = InMemoryStore()
        var now = 1_000_000L

        val manager = DeviceAuthManager(
            baseUrl = "https://api.example.com",
            orgId = "org-1",
            deviceIdentifier = "device-1",
            transport = transport,
            stateStore = store,
            nowMillisProvider = { now },
        )

        val bootstrapped = manager.bootstrap(bootstrapBearerToken = "bootstrap-token")
        assertEquals("acc_bootstrap", bootstrapped.accessToken)
        assertNotNull(store.state)

        val refreshed = manager.refresh()
        assertEquals("acc_refresh", refreshed.accessToken)
        assertEquals("ref_refresh", refreshed.refreshToken)

        manager.revoke()
        assertNull(store.state)
    }

    @Test
    fun `getAccessToken falls back to current token when refresh fails but token still valid`() = runBlocking {
        val transport = FakeTransport(
            responses = ArrayDeque(
                listOf(
                    tokenPayload("acc_bootstrap", "ref_bootstrap", expiresIn = 600),
                )
            ),
            throwOnCallIndex = 2, // fail first refresh call
        )
        val store = InMemoryStore()
        var now = 2_000_000L

        val manager = DeviceAuthManager(
            baseUrl = "https://api.example.com",
            orgId = "org-1",
            deviceIdentifier = "device-1",
            transport = transport,
            stateStore = store,
            nowMillisProvider = { now },
        )

        manager.bootstrap(bootstrapBearerToken = "bootstrap-token")
        val token = manager.getAccessToken(refreshIfExpiringWithinSeconds = 700)
        assertEquals("acc_bootstrap", token)
    }

    @Test
    fun `getAccessToken throws when token expired and refresh fails`() = runBlocking {
        val transport = FakeTransport(
            responses = ArrayDeque(
                listOf(
                    tokenPayload("acc_bootstrap", "ref_bootstrap", expiresIn = 1),
                )
            ),
            throwOnCallIndex = 2,
        )
        val store = InMemoryStore()
        var now = 3_000_000L

        val manager = DeviceAuthManager(
            baseUrl = "https://api.example.com",
            orgId = "org-1",
            deviceIdentifier = "device-1",
            transport = transport,
            stateStore = store,
            nowMillisProvider = { now },
        )

        manager.bootstrap(bootstrapBearerToken = "bootstrap-token")
        now += 5_000L

        assertFailsWith<IllegalStateException> {
            manager.getAccessToken(refreshIfExpiringWithinSeconds = 30)
        }
    }

    @Test
    fun `getAccessToken returns current token when not near expiry`() = runBlocking {
        val transport = FakeTransport(
            responses = ArrayDeque(
                listOf(
                    tokenPayload("acc_bootstrap", "ref_bootstrap", expiresIn = 3_600),
                )
            )
        )
        val store = InMemoryStore()
        var now = 4_000_000L

        val manager = DeviceAuthManager(
            baseUrl = "https://api.example.com",
            orgId = "org-1",
            deviceIdentifier = "device-1",
            transport = transport,
            stateStore = store,
            nowMillisProvider = { now },
        )

        manager.bootstrap(bootstrapBearerToken = "bootstrap-token")
        val token = manager.getAccessToken(refreshIfExpiringWithinSeconds = 30)
        assertEquals("acc_bootstrap", token)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `revoke failure preserves stored state`() = runBlocking {
        val transport = FakeTransport(
            responses = ArrayDeque(
                listOf(
                    tokenPayload("acc_bootstrap", "ref_bootstrap", expiresIn = 600),
                )
            ),
            throwOnCallIndex = 2,
        )
        val store = InMemoryStore()

        val manager = DeviceAuthManager(
            baseUrl = "https://api.example.com",
            orgId = "org-1",
            deviceIdentifier = "device-1",
            transport = transport,
            stateStore = store,
        )

        manager.bootstrap(bootstrapBearerToken = "bootstrap-token")

        assertFailsWith<IllegalStateException> {
            manager.revoke()
        }
        assertNotNull(store.state)
    }
}

private fun tokenPayload(
    accessToken: String,
    refreshToken: String,
    expiresIn: Long,
): JSONObject {
    return JSONObject()
        .put("access_token", accessToken)
        .put("refresh_token", refreshToken)
        .put("token_type", "Bearer")
        .put("expires_at", "2026-02-07T00:00:00Z")
        .put("expires_in", expiresIn)
        .put("org_id", "org-1")
        .put("device_identifier", "device-1")
        .put("scopes", listOf("devices:write"))
}

private class InMemoryStore : DeviceAuthStateStore {
    var state: DeviceAuthManager.DeviceTokenState? = null

    override fun save(state: DeviceAuthManager.DeviceTokenState) {
        this.state = state
    }

    override fun load(): DeviceAuthManager.DeviceTokenState? = state

    override fun clear() {
        state = null
    }
}

private class FakeTransport(
    private val responses: ArrayDeque<JSONObject>,
    private val throwOnCallIndex: Int? = null,
) : DeviceAuthTransport {
    data class Call(
        val path: String,
        val body: JSONObject,
        val bearerToken: String?,
        val expectedStatusCodes: Set<Int>
    )

    private var callCount = 0
    val calls = mutableListOf<Call>()

    override fun postJson(
        path: String,
        body: JSONObject,
        bearerToken: String?,
        expectedStatusCodes: Set<Int>
    ): JSONObject {
        callCount += 1
        calls += Call(path = path, body = body, bearerToken = bearerToken, expectedStatusCodes = expectedStatusCodes)
        if (throwOnCallIndex != null && callCount == throwOnCallIndex) {
            throw IllegalStateException("network unavailable")
        }
        return responses.removeFirstOrNull() ?: JSONObject()
    }
}
