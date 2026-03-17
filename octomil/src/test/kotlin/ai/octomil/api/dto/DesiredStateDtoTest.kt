package ai.octomil.api.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Serialization roundtrip tests for desired-state and observed-state DTOs.
 */
class DesiredStateDtoTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // =========================================================================
    // DesiredStateResponse
    // =========================================================================

    @Test
    fun `DesiredStateResponse roundtrips with all fields`() {
        val response = DesiredStateResponse(
            schemaVersion = "1.4.0",
            deviceId = "device-1",
            generatedAt = "2026-01-01T00:00:00Z",
            activeBinding = "binding-1",
            artifacts = listOf(
                DesiredArtifact(
                    artifactId = "model-1",
                    version = "2.0",
                    downloadUrl = "https://cdn.example.com/model.tflite",
                    checksum = "sha256:abc123",
                    sizeBytes = 5_000_000,
                    format = "tensorflow_lite",
                ),
            ),
            policyConfig = mapOf("max_training_time" to "300"),
            federationOffers = listOf(
                FederationOffer(
                    roundId = "round-1",
                    jobId = "job-1",
                    expiresAt = "2026-01-02T00:00:00Z",
                ),
            ),
            gcEligibleArtifactIds = listOf("old-model-1"),
        )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<DesiredStateResponse>(serialized)

        assertEquals("1.4.0", deserialized.schemaVersion)
        assertEquals("device-1", deserialized.deviceId)
        assertEquals("binding-1", deserialized.activeBinding)
        assertEquals(1, deserialized.artifacts.size)
        assertEquals("model-1", deserialized.artifacts[0].artifactId)
        assertEquals(5_000_000, deserialized.artifacts[0].sizeBytes)
        assertEquals(1, deserialized.federationOffers.size)
        assertEquals("round-1", deserialized.federationOffers[0].roundId)
        assertEquals(listOf("old-model-1"), deserialized.gcEligibleArtifactIds)
    }

    @Test
    fun `DesiredStateResponse handles minimal response`() {
        val jsonStr = """
            {
                "device_id": "dev-1",
                "generated_at": "2026-01-01T00:00:00Z"
            }
        """.trimIndent()

        val response = json.decodeFromString<DesiredStateResponse>(jsonStr)
        assertEquals("dev-1", response.deviceId)
        assertEquals("1.4.0", response.schemaVersion)
        assertEquals(emptyList(), response.artifacts)
        assertEquals(emptyList(), response.federationOffers)
        assertEquals(emptyList(), response.gcEligibleArtifactIds)
        assertNull(response.activeBinding)
    }

    // =========================================================================
    // DesiredArtifact
    // =========================================================================

    @Test
    fun `DesiredArtifact roundtrips correctly`() {
        val artifact = DesiredArtifact(
            artifactId = "art-1",
            version = "3.0",
            downloadUrl = "https://cdn.example.com/art.bin",
            checksum = "sha256:def456",
            sizeBytes = 10_000,
            format = "onnx",
        )

        val serialized = json.encodeToString(artifact)
        val deserialized = json.decodeFromString<DesiredArtifact>(serialized)

        assertEquals("art-1", deserialized.artifactId)
        assertEquals("3.0", deserialized.version)
        assertEquals("sha256:def456", deserialized.checksum)
        assertEquals("onnx", deserialized.format)
    }

    @Test
    fun `DesiredArtifact handles null format`() {
        val jsonStr = """
            {
                "artifact_id": "art-1",
                "version": "1.0",
                "download_url": "https://cdn.example.com/art.bin",
                "checksum": "abc",
                "size_bytes": 1000
            }
        """.trimIndent()

        val artifact = json.decodeFromString<DesiredArtifact>(jsonStr)
        assertNull(artifact.format)
    }

    // =========================================================================
    // FederationOffer
    // =========================================================================

    @Test
    fun `FederationOffer roundtrips correctly`() {
        val offer = FederationOffer(
            roundId = "round-1",
            jobId = "job-1",
            expiresAt = "2026-01-02T00:00:00Z",
        )

        val serialized = json.encodeToString(offer)
        val deserialized = json.decodeFromString<FederationOffer>(serialized)

        assertEquals("round-1", deserialized.roundId)
        assertEquals("job-1", deserialized.jobId)
    }

    @Test
    fun `FederationOffer handles minimal JSON`() {
        val jsonStr = """{"round_id": "r-1"}"""
        val offer = json.decodeFromString<FederationOffer>(jsonStr)
        assertEquals("r-1", offer.roundId)
        assertNull(offer.jobId)
        assertNull(offer.expiresAt)
    }

    // =========================================================================
    // ObservedStateRequest
    // =========================================================================

    @Test
    fun `ObservedStateRequest roundtrips with all fields`() {
        val request = ObservedStateRequest(
            schemaVersion = "1.4.0",
            deviceId = "device-1",
            reportedAt = "2026-01-01T12:00:00Z",
            artifactStatuses = listOf(
                ArtifactStatusEntry(
                    artifactId = "model-1",
                    status = "current",
                    bytesDownloaded = 5_000_000,
                    totalBytes = 5_000_000,
                ),
                ArtifactStatusEntry(
                    artifactId = "model-2",
                    status = "error",
                    errorCode = "checksum_mismatch",
                ),
            ),
            sdkVersion = "1.2.0",
            osVersion = "Android 14",
        )

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<ObservedStateRequest>(serialized)

        assertEquals("1.4.0", deserialized.schemaVersion)
        assertEquals("device-1", deserialized.deviceId)
        assertEquals(2, deserialized.artifactStatuses.size)
        assertEquals("current", deserialized.artifactStatuses[0].status)
        assertEquals("checksum_mismatch", deserialized.artifactStatuses[1].errorCode)
        assertEquals("1.2.0", deserialized.sdkVersion)
    }

    // =========================================================================
    // ArtifactStatusEntry
    // =========================================================================

    @Test
    fun `ArtifactStatusEntry roundtrips correctly`() {
        val entry = ArtifactStatusEntry(
            artifactId = "art-1",
            status = "downloading",
            bytesDownloaded = 2_500_000,
            totalBytes = 5_000_000,
        )

        val serialized = json.encodeToString(entry)
        val deserialized = json.decodeFromString<ArtifactStatusEntry>(serialized)

        assertEquals("art-1", deserialized.artifactId)
        assertEquals("downloading", deserialized.status)
        assertEquals(2_500_000, deserialized.bytesDownloaded)
        assertEquals(5_000_000, deserialized.totalBytes)
        assertNull(deserialized.errorCode)
    }

    @Test
    fun `ArtifactStatusEntry handles error state`() {
        val entry = ArtifactStatusEntry(
            artifactId = "art-2",
            status = "error",
            errorCode = "download_http_500",
        )

        val serialized = json.encodeToString(entry)
        val deserialized = json.decodeFromString<ArtifactStatusEntry>(serialized)

        assertEquals("error", deserialized.status)
        assertEquals("download_http_500", deserialized.errorCode)
        assertNull(deserialized.bytesDownloaded)
    }

    // =========================================================================
    // DeviceRegistrationResponse with new token fields
    // =========================================================================

    @Test
    fun `DeviceRegistrationResponse includes access_token and expires_at`() {
        val jsonStr = """
            {
                "id": "uuid-1",
                "device_identifier": "device-1",
                "org_id": "org-1",
                "platform": "android",
                "status": "active",
                "created_at": "2024-01-01T00:00:00Z",
                "updated_at": "2024-01-01T00:00:00Z",
                "access_token": "tok_live_abc123",
                "expires_at": "2024-01-02T00:00:00Z",
                "refresh_token": "rt_abc123"
            }
        """.trimIndent()

        val response = json.decodeFromString<DeviceRegistrationResponse>(jsonStr)
        assertEquals("tok_live_abc123", response.accessToken)
        assertEquals("2024-01-02T00:00:00Z", response.expiresAt)
        assertEquals("rt_abc123", response.refreshToken)
    }

    @Test
    fun `DeviceRegistrationResponse handles missing token fields`() {
        val jsonStr = """
            {
                "id": "uuid-1",
                "device_identifier": "device-1",
                "org_id": "org-1",
                "platform": "android",
                "status": "active",
                "created_at": "2024-01-01T00:00:00Z",
                "updated_at": "2024-01-01T00:00:00Z",
                "api_token": "legacy-token"
            }
        """.trimIndent()

        val response = json.decodeFromString<DeviceRegistrationResponse>(jsonStr)
        assertNull(response.accessToken)
        assertNull(response.expiresAt)
        assertNull(response.refreshToken)
        assertEquals("legacy-token", response.apiToken)
    }
}
