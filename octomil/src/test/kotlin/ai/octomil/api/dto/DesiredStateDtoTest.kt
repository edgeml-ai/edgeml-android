package ai.octomil.api.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Serialization roundtrip tests for desired-state and observed-state DTOs.
 * Hard cutover: uses models array (DesiredModelEntry / ObservedModelStatus).
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
            models = listOf(
                DesiredModelEntry(
                    modelId = "phi-4-mini-q4",
                    desiredVersion = "2.0",
                    activationPolicy = "immediate",
                    artifactManifest = ArtifactManifestDto(
                        artifactId = "art-1",
                        modelId = "phi-4-mini-q4",
                        version = "2.0",
                        format = "gguf",
                        totalBytes = 5_000_000,
                        sha256 = "abc123",
                        cdnBaseUrl = "https://cdn.example.com/art-1",
                    ),
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
        assertEquals(1, deserialized.models.size)
        assertEquals("phi-4-mini-q4", deserialized.models[0].modelId)
        assertEquals("2.0", deserialized.models[0].desiredVersion)
        assertEquals("immediate", deserialized.models[0].activationPolicy)
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
        assertEquals(emptyList(), response.models)
        assertEquals(emptyList(), response.federationOffers)
        assertEquals(emptyList(), response.gcEligibleArtifactIds)
        assertNull(response.activeBinding)
    }

    @Test
    fun `DesiredStateResponse has no artifacts field`() {
        val jsonStr = """
            {
                "device_id": "dev-1",
                "generated_at": "2026-01-01T00:00:00Z",
                "artifacts": [{"artifact_id": "a", "version": "1"}]
            }
        """.trimIndent()

        // ignoreUnknownKeys allows parsing, but models should be empty
        val response = json.decodeFromString<DesiredStateResponse>(jsonStr)
        assertTrue(response.models.isEmpty())
    }

    // =========================================================================
    // DesiredModelEntry
    // =========================================================================

    @Test
    fun `DesiredModelEntry roundtrips correctly`() {
        val entry = DesiredModelEntry(
            modelId = "phi-4-mini-q4",
            desiredVersion = "3.0",
            currentChannel = "stable",
            deliveryMode = "background",
            activationPolicy = "next_launch",
            rolloutId = "rollout-1",
        )

        val serialized = json.encodeToString(entry)
        val deserialized = json.decodeFromString<DesiredModelEntry>(serialized)

        assertEquals("phi-4-mini-q4", deserialized.modelId)
        assertEquals("3.0", deserialized.desiredVersion)
        assertEquals("stable", deserialized.currentChannel)
        assertEquals("next_launch", deserialized.activationPolicy)
        assertEquals("rollout-1", deserialized.rolloutId)
    }

    @Test
    fun `DesiredModelEntry handles minimal fields`() {
        val jsonStr = """
            {
                "model_id": "test-model",
                "desired_version": "1.0"
            }
        """.trimIndent()

        val entry = json.decodeFromString<DesiredModelEntry>(jsonStr)
        assertEquals("test-model", entry.modelId)
        assertEquals("1.0", entry.desiredVersion)
        assertNull(entry.activationPolicy)
        assertNull(entry.artifactManifest)
        assertNull(entry.enginePolicy)
    }

    @Test
    fun `DesiredModelEntry with engine policy`() {
        val entry = DesiredModelEntry(
            modelId = "model-1",
            desiredVersion = "1.0",
            enginePolicy = EnginePolicyDto(
                allowed = listOf("llamacpp", "mlx"),
                forced = null,
            ),
        )

        val serialized = json.encodeToString(entry)
        val deserialized = json.decodeFromString<DesiredModelEntry>(serialized)

        assertEquals(listOf("llamacpp", "mlx"), deserialized.enginePolicy?.allowed)
        assertNull(deserialized.enginePolicy?.forced)
    }

    // =========================================================================
    // ArtifactManifestDto
    // =========================================================================

    @Test
    fun `ArtifactManifestDto roundtrips correctly`() {
        val manifest = ArtifactManifestDto(
            artifactId = "art-1",
            modelId = "model-1",
            version = "2.0",
            format = "gguf",
            totalBytes = 10_000_000,
            sha256 = "abc123def456",
            cdnBaseUrl = "https://cdn.example.com/art-1",
            chunks = listOf(
                ArtifactChunkDto(index = 0, offset = 0, size = 5_000_000, sha256 = "chunk0hash"),
                ArtifactChunkDto(index = 1, offset = 5_000_000, size = 5_000_000, sha256 = "chunk1hash"),
            ),
        )

        val serialized = json.encodeToString(manifest)
        val deserialized = json.decodeFromString<ArtifactManifestDto>(serialized)

        assertEquals("art-1", deserialized.artifactId)
        assertEquals("model-1", deserialized.modelId)
        assertEquals(10_000_000, deserialized.totalBytes)
        assertEquals(2, deserialized.chunks.size)
        assertEquals(5_000_000, deserialized.chunks[1].offset)
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
            models = listOf(
                ObservedModelStatus(
                    modelId = "phi-4-mini-q4",
                    status = "active",
                    installedVersion = "2.0",
                    activeVersion = "2.0",
                    health = "healthy",
                ),
                ObservedModelStatus(
                    modelId = "whisper-small",
                    status = "failed_corrupt",
                    lastError = "checksum_mismatch",
                ),
            ),
            sdkVersion = "1.2.0",
            osVersion = "Android 14",
        )

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<ObservedStateRequest>(serialized)

        assertEquals("1.4.0", deserialized.schemaVersion)
        assertEquals("device-1", deserialized.deviceId)
        assertEquals(2, deserialized.models.size)
        assertEquals("active", deserialized.models[0].status)
        assertEquals("2.0", deserialized.models[0].activeVersion)
        assertEquals("checksum_mismatch", deserialized.models[1].lastError)
        assertEquals("1.2.0", deserialized.sdkVersion)
    }

    @Test
    fun `ObservedStateRequest has no artifactStatuses field`() {
        val jsonStr = """
            {
                "device_id": "dev-1",
                "reported_at": "2026-01-01T00:00:00Z",
                "artifact_statuses": [{"artifact_id": "a", "status": "current"}]
            }
        """.trimIndent()

        // Old field is ignored — models defaults to empty
        val request = json.decodeFromString<ObservedStateRequest>(jsonStr)
        assertTrue(request.models.isEmpty())
    }

    // =========================================================================
    // ObservedModelStatus
    // =========================================================================

    @Test
    fun `ObservedModelStatus roundtrips correctly`() {
        val status = ObservedModelStatus(
            modelId = "phi-4-mini-q4",
            status = "active",
            installedVersion = "2.0",
            activeVersion = "2.0",
            health = "healthy",
        )

        val serialized = json.encodeToString(status)
        val deserialized = json.decodeFromString<ObservedModelStatus>(serialized)

        assertEquals("phi-4-mini-q4", deserialized.modelId)
        assertEquals("active", deserialized.status)
        assertEquals("2.0", deserialized.installedVersion)
        assertEquals("healthy", deserialized.health)
        assertNull(deserialized.lastError)
    }

    @Test
    fun `ObservedModelStatus handles error state`() {
        val status = ObservedModelStatus(
            modelId = "model-2",
            status = "failed_retryable",
            lastError = "download_http_500",
        )

        val serialized = json.encodeToString(status)
        val deserialized = json.decodeFromString<ObservedModelStatus>(serialized)

        assertEquals("failed_retryable", deserialized.status)
        assertEquals("download_http_500", deserialized.lastError)
        assertNull(deserialized.installedVersion)
        assertNull(deserialized.activeVersion)
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
