package ai.edgeml

import ai.edgeml.config.EdgeMLConfig
import ai.edgeml.models.CachedModel

/**
 * Shared test helpers for EdgeML Android SDK tests.
 */

/**
 * Create an [EdgeMLConfig] with sensible test-friendly defaults.
 *
 * All required fields are pre-filled so tests only need to override what they
 * care about.
 */
fun testConfig(
    serverUrl: String = "https://test.edgeml.ai",
    deviceAccessToken: String = "test-token",
    orgId: String = "test-org",
    modelId: String = "test-model",
    enableBackgroundSync: Boolean = false,
    enableHeartbeat: Boolean = false,
    enableGpuAcceleration: Boolean = false,
    enableEncryptedStorage: Boolean = false,
    debugMode: Boolean = false,
): EdgeMLConfig =
    EdgeMLConfig(
        serverUrl = serverUrl,
        deviceAccessToken = deviceAccessToken,
        orgId = orgId,
        modelId = modelId,
        enableBackgroundSync = enableBackgroundSync,
        enableHeartbeat = enableHeartbeat,
        enableGpuAcceleration = enableGpuAcceleration,
        enableEncryptedStorage = enableEncryptedStorage,
        debugMode = debugMode,
    )

/**
 * Create a [CachedModel] with sensible test-friendly defaults.
 */
fun testCachedModel(
    modelId: String = "test-model",
    version: String = "1.0.0",
    filePath: String = "/tmp/test-model.tflite",
    checksum: String = "abc123",
    sizeBytes: Long = 1024L,
    format: String = "tensorflow_lite",
    downloadedAt: Long = System.currentTimeMillis(),
    verified: Boolean = true,
): CachedModel =
    CachedModel(
        modelId = modelId,
        version = version,
        filePath = filePath,
        checksum = checksum,
        sizeBytes = sizeBytes,
        format = format,
        downloadedAt = downloadedAt,
        verified = verified,
    )
