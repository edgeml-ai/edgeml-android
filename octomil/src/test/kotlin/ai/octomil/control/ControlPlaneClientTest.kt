package ai.octomil.control

import ai.octomil.api.OctomilApi
import ai.octomil.api.dto.DesiredModelEntry
import ai.octomil.api.dto.DesiredStateResponse
import ai.octomil.api.dto.HeartbeatRequest
import ai.octomil.api.dto.HeartbeatResponse
import ai.octomil.api.dto.ObservedModelStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ControlPlaneClientTest {

    private val api = mockk<OctomilApi>()

    // =========================================================================
    // heartbeat
    // =========================================================================

    @Test
    fun `heartbeat sends request and swallows success`() = runTest {
        val client = ControlPlaneClient(api, "org-1", "device-1")
        coEvery {
            api.sendHeartbeat("device-1", any())
        } returns Response.success(HeartbeatResponse(acknowledged = true))

        val request = HeartbeatRequest(
            sdkVersion = "1.0.0",
            batteryLevel = 80,
            isCharging = false,
            networkType = "wifi",
        )
        client.heartbeat("device-1", request)

        coVerify(exactly = 1) { api.sendHeartbeat("device-1", any()) }
    }

    @Test
    fun `heartbeat swallows exceptions`() = runTest {
        val client = ControlPlaneClient(api, "org-1", "device-1")
        coEvery {
            api.sendHeartbeat(any(), any())
        } throws RuntimeException("Network error")

        // Should not throw
        val request = HeartbeatRequest(sdkVersion = "1.0.0")
        client.heartbeat("device-1", request)
    }

    @Test
    fun `heartbeat uses constructor deviceId as default`() = runTest {
        val client = ControlPlaneClient(api, "org-1", "dev-99")
        coEvery {
            api.sendHeartbeat("dev-99", any())
        } returns Response.success(HeartbeatResponse(acknowledged = true))

        client.heartbeat(request = HeartbeatRequest())

        coVerify { api.sendHeartbeat("dev-99", any()) }
    }

    // =========================================================================
    // fetchDesiredState
    // =========================================================================

    @Test
    fun `fetchDesiredState returns body on success`() = runTest {
        val client = ControlPlaneClient(api, "org-1", "device-1")
        val expected = DesiredStateResponse(
            deviceId = "device-1",
            generatedAt = "2026-01-01T00:00:00Z",
            models = listOf(
                DesiredModelEntry(
                    modelId = "phi-4-mini-q4",
                    desiredVersion = "2.0",
                    activationPolicy = "immediate",
                ),
            ),
        )

        coEvery { api.getDesiredState("device-1") } returns Response.success(expected)

        val result = client.fetchDesiredState("device-1")
        assertNotNull(result)
        assertEquals("device-1", result.deviceId)
        assertEquals(1, result.models.size)
        assertEquals("phi-4-mini-q4", result.models[0].modelId)
        assertEquals("2.0", result.models[0].desiredVersion)
    }

    @Test
    fun `fetchDesiredState returns null on HTTP error`() = runTest {
        val client = ControlPlaneClient(api, "org-1", "device-1")
        coEvery { api.getDesiredState("device-1") } returns Response.error(
            404,
            okhttp3.ResponseBody.create(null, ""),
        )

        val result = client.fetchDesiredState("device-1")
        assertNull(result)
    }

    @Test
    fun `fetchDesiredState returns null on exception`() = runTest {
        val client = ControlPlaneClient(api, "org-1", "device-1")
        coEvery { api.getDesiredState(any()) } throws RuntimeException("Timeout")

        val result = client.fetchDesiredState("device-1")
        assertNull(result)
    }

    // =========================================================================
    // reportObservedState
    // =========================================================================

    @Test
    fun `reportObservedState sends models and swallows success`() = runTest {
        val client = ControlPlaneClient(api, "org-1", "device-1")
        coEvery {
            api.reportObservedState("device-1", any())
        } returns Response.success(Unit)

        val models = listOf(
            ObservedModelStatus(
                modelId = "phi-4-mini-q4",
                status = "active",
                installedVersion = "2.0",
                activeVersion = "2.0",
                health = "healthy",
            ),
        )
        client.reportObservedState("device-1", models)

        coVerify(exactly = 1) { api.reportObservedState("device-1", any()) }
    }

    @Test
    fun `reportObservedState swallows exceptions`() = runTest {
        val client = ControlPlaneClient(api, "org-1", "device-1")
        coEvery {
            api.reportObservedState(any(), any())
        } throws RuntimeException("Server down")

        // Should not throw
        client.reportObservedState("device-1", emptyList())
    }
}
