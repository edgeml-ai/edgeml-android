package ai.edgeml

import ai.edgeml.config.EdgeMLConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for EdgeML SDK configuration.
 */
class EdgeMLConfigTest {
    @Test
    fun `config builder creates valid config`() {
        val config =
            EdgeMLConfig
                .Builder()
                .serverUrl("https://api.edgeml.ai")
                .deviceAccessToken("test-api-key")
                .orgId("org-123")
                .modelId("model-456")
                .build()

        assertEquals("https://api.edgeml.ai", config.serverUrl)
        assertEquals("test-api-key", config.deviceAccessToken)
        assertEquals("org-123", config.orgId)
        assertEquals("model-456", config.modelId)
    }

    @Test
    fun `config builder trims trailing slash from server URL`() {
        val config =
            EdgeMLConfig
                .Builder()
                .serverUrl("https://api.edgeml.ai/")
                .deviceAccessToken("test-api-key")
                .orgId("org-123")
                .modelId("model-456")
                .build()

        assertEquals("https://api.edgeml.ai", config.serverUrl)
    }

    @Test
    fun `config has sensible defaults`() {
        val config =
            EdgeMLConfig
                .Builder()
                .serverUrl("https://api.edgeml.ai")
                .deviceAccessToken("test-api-key")
                .orgId("org-123")
                .modelId("model-456")
                .build()

        assertEquals(30_000L, config.connectionTimeoutMs)
        assertEquals(60_000L, config.readTimeoutMs)
        assertEquals(60_000L, config.writeTimeoutMs)
        assertEquals(3, config.maxRetries)
        assertEquals(1_000L, config.retryDelayMs)
        assertEquals(100 * 1024 * 1024L, config.modelCacheSizeBytes)
        assertEquals(true, config.enableGpuAcceleration)
        assertEquals(4, config.numThreads)
        assertEquals(true, config.enableBackgroundSync)
        assertEquals(60L, config.syncIntervalMinutes)
        assertEquals(20, config.minBatteryLevel)
        assertEquals(false, config.requireCharging)
        assertEquals(true, config.requireUnmeteredNetwork)
        assertEquals(true, config.enableEncryptedStorage)
    }

    @Test
    fun `config requires non-blank server URL`() {
        assertFailsWith<IllegalArgumentException> {
            EdgeMLConfig
                .Builder()
                .serverUrl("")
                .deviceAccessToken("test-api-key")
                .orgId("org-123")
                .modelId("model-456")
                .build()
        }
    }

    @Test
    fun `config requires non-blank API key`() {
        assertFailsWith<IllegalArgumentException> {
            EdgeMLConfig
                .Builder()
                .serverUrl("https://api.edgeml.ai")
                .deviceAccessToken("")
                .orgId("org-123")
                .modelId("model-456")
                .build()
        }
    }

    @Test
    fun `config requires non-blank org ID`() {
        assertFailsWith<IllegalArgumentException> {
            EdgeMLConfig
                .Builder()
                .serverUrl("https://api.edgeml.ai")
                .deviceAccessToken("test-api-key")
                .orgId("")
                .modelId("model-456")
                .build()
        }
    }

    @Test
    fun `config requires non-blank model ID`() {
        assertFailsWith<IllegalArgumentException> {
            EdgeMLConfig
                .Builder()
                .serverUrl("https://api.edgeml.ai")
                .deviceAccessToken("test-api-key")
                .orgId("org-123")
                .modelId("")
                .build()
        }
    }

    @Test
    fun `config validates timeout values`() {
        assertFailsWith<IllegalArgumentException> {
            EdgeMLConfig
                .Builder()
                .serverUrl("https://api.edgeml.ai")
                .deviceAccessToken("test-api-key")
                .orgId("org-123")
                .modelId("model-456")
                .connectionTimeoutMs(0)
                .build()
        }

        assertFailsWith<IllegalArgumentException> {
            EdgeMLConfig
                .Builder()
                .serverUrl("https://api.edgeml.ai")
                .deviceAccessToken("test-api-key")
                .orgId("org-123")
                .modelId("model-456")
                .connectionTimeoutMs(-1)
                .build()
        }
    }

    @Test
    fun `config validates sync interval minimum`() {
        assertFailsWith<IllegalArgumentException> {
            EdgeMLConfig
                .Builder()
                .serverUrl("https://api.edgeml.ai")
                .deviceAccessToken("test-api-key")
                .orgId("org-123")
                .modelId("model-456")
                .syncIntervalMinutes(14)
                .build()
        }
    }

    @Test
    fun `config validates battery level range`() {
        assertFailsWith<IllegalArgumentException> {
            EdgeMLConfig
                .Builder()
                .serverUrl("https://api.edgeml.ai")
                .deviceAccessToken("test-api-key")
                .orgId("org-123")
                .modelId("model-456")
                .minBatteryLevel(-1)
                .build()
        }

        assertFailsWith<IllegalArgumentException> {
            EdgeMLConfig
                .Builder()
                .serverUrl("https://api.edgeml.ai")
                .deviceAccessToken("test-api-key")
                .orgId("org-123")
                .modelId("model-456")
                .minBatteryLevel(101)
                .build()
        }
    }

    @Test
    fun `config builder allows custom settings`() {
        val config =
            EdgeMLConfig
                .Builder()
                .serverUrl("https://custom.server.com")
                .deviceAccessToken("custom-key")
                .orgId("custom-org")
                .modelId("custom-model")
                .deviceId("custom-device-id")
                .debugMode(true)
                .connectionTimeoutMs(10_000L)
                .readTimeoutMs(30_000L)
                .writeTimeoutMs(30_000L)
                .maxRetries(5)
                .retryDelayMs(2_000L)
                .modelCacheSizeBytes(50 * 1024 * 1024L)
                .enableGpuAcceleration(false)
                .numThreads(2)
                .enableBackgroundSync(false)
                .syncIntervalMinutes(120L)
                .minBatteryLevel(50)
                .requireCharging(true)
                .requireUnmeteredNetwork(false)
                .enableEncryptedStorage(false)
                .build()

        assertEquals("https://custom.server.com", config.serverUrl)
        assertEquals("custom-key", config.deviceAccessToken)
        assertEquals("custom-org", config.orgId)
        assertEquals("custom-model", config.modelId)
        assertEquals("custom-device-id", config.deviceId)
        assertEquals(true, config.debugMode)
        assertEquals(10_000L, config.connectionTimeoutMs)
        assertEquals(30_000L, config.readTimeoutMs)
        assertEquals(30_000L, config.writeTimeoutMs)
        assertEquals(5, config.maxRetries)
        assertEquals(2_000L, config.retryDelayMs)
        assertEquals(50 * 1024 * 1024L, config.modelCacheSizeBytes)
        assertEquals(false, config.enableGpuAcceleration)
        assertEquals(2, config.numThreads)
        assertEquals(false, config.enableBackgroundSync)
        assertEquals(120L, config.syncIntervalMinutes)
        assertEquals(50, config.minBatteryLevel)
        assertEquals(true, config.requireCharging)
        assertEquals(false, config.requireUnmeteredNetwork)
        assertEquals(false, config.enableEncryptedStorage)
    }
}
