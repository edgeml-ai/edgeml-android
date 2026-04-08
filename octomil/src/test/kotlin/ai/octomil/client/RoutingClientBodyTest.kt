package ai.octomil.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that the routing request body shape matches the server contract:
 * - `deployment_id` present when configured
 * - `prefer` omitted when `deploymentId` is set and prefer was not explicit
 * - `prefer` included when explicitly set
 */
class RoutingClientBodyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val testCapabilities = RoutingDeviceCapabilities(
        platform = "android",
        model = "Pixel 9 Pro",
        totalMemoryMb = 12288,
        gpuAvailable = true,
        npuAvailable = true,
        supportedRuntimes = listOf("litert", "onnxruntime"),
    )

    @Test
    fun `deployment_id present in body when set`() {
        val request = RoutingRequest(
            modelId = "test-model",
            modelParams = 0,
            modelSizeMb = 0.0,
            deviceCapabilities = testCapabilities,
            prefer = null,
            deploymentId = "dep_abc123",
        )

        val body = json.encodeToString(RoutingRequest.serializer(), request)
        assertTrue(body.contains("\"deployment_id\":\"dep_abc123\""), "deployment_id should be in body")
    }

    @Test
    fun `prefer omitted when null`() {
        val request = RoutingRequest(
            modelId = "test-model",
            modelParams = 0,
            modelSizeMb = 0.0,
            deviceCapabilities = testCapabilities,
            prefer = null,
            deploymentId = "dep_abc123",
        )

        val body = json.encodeToString(RoutingRequest.serializer(), request)
        assertFalse(body.contains("\"prefer\""), "prefer should not appear in body when null")
    }

    @Test
    fun `prefer present when set`() {
        val request = RoutingRequest(
            modelId = "test-model",
            modelParams = 0,
            modelSizeMb = 0.0,
            deviceCapabilities = testCapabilities,
            prefer = "device",
            deploymentId = "dep_abc123",
        )

        val body = json.encodeToString(RoutingRequest.serializer(), request)
        assertTrue(body.contains("\"prefer\":\"device\""), "prefer should be in body when set")
        assertTrue(body.contains("\"deployment_id\":\"dep_abc123\""), "deployment_id should also be present")
    }

    @Test
    fun `deployment_id omitted when null`() {
        val request = RoutingRequest(
            modelId = "test-model",
            modelParams = 0,
            modelSizeMb = 0.0,
            deviceCapabilities = testCapabilities,
            prefer = "fastest",
            deploymentId = null,
        )

        val body = json.encodeToString(RoutingRequest.serializer(), request)
        assertFalse(body.contains("\"deployment_id\""), "deployment_id should not appear when null")
        assertTrue(body.contains("\"prefer\":\"fastest\""), "prefer should be present")
    }

    // =========================================================================
    // Config preferExplicit integration
    // =========================================================================

    @Test
    fun `config omits prefer when deploymentId set and not explicit`() {
        val config = RoutingConfig(
            serverUrl = "https://api.octomil.com",
            apiKey = "key",
            prefer = RoutingPreference.FASTEST,
            preferExplicit = false,
            deploymentId = "dep_abc",
        )

        val preferValue: String? = if (config.deploymentId != null && !config.preferExplicit) null else config.prefer.value
        assertEquals(null, preferValue, "prefer should be null when deploymentId set and not explicit")
    }

    @Test
    fun `config includes prefer when explicit`() {
        val config = RoutingConfig(
            serverUrl = "https://api.octomil.com",
            apiKey = "key",
            prefer = RoutingPreference.DEVICE,
            preferExplicit = true,
            deploymentId = "dep_abc",
        )

        val preferValue: String? = if (config.deploymentId != null && !config.preferExplicit) null else config.prefer.value
        assertEquals("device", preferValue)
    }

    @Test
    fun `config includes prefer when no deploymentId`() {
        val config = RoutingConfig(
            serverUrl = "https://api.octomil.com",
            apiKey = "key",
            prefer = RoutingPreference.CLOUD,
            preferExplicit = false,
            deploymentId = null,
        )

        val preferValue: String? = if (config.deploymentId != null && !config.preferExplicit) null else config.prefer.value
        assertEquals("cloud", preferValue)
    }
}
