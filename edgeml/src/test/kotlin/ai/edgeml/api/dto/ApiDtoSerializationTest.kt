package ai.edgeml.api.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests JSON serialization/deserialization roundtrips for all API DTOs.
 */
class ApiDtoSerializationTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    // =========================================================================
    // DeviceRegistrationRequest
    // =========================================================================

    @Test
    fun `DeviceRegistrationRequest serializes required fields`() {
        val request =
            DeviceRegistrationRequest(
                deviceIdentifier = "device-1",
                orgId = "org-1",
                osVersion = "Android 14",
                sdkVersion = "1.0.0",
            )

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<DeviceRegistrationRequest>(serialized)

        assertEquals("device-1", deserialized.deviceIdentifier)
        assertEquals("org-1", deserialized.orgId)
        assertEquals("android", deserialized.platform)
        assertEquals("Android 14", deserialized.osVersion)
        assertEquals("1.0.0", deserialized.sdkVersion)
    }

    @Test
    fun `DeviceRegistrationRequest serializes optional fields`() {
        val request =
            DeviceRegistrationRequest(
                deviceIdentifier = "device-1",
                orgId = "org-1",
                osVersion = "Android 14",
                sdkVersion = "1.0.0",
                manufacturer = "Google",
                model = "Pixel 8",
                locale = "en_US",
                region = "us",
                appVersion = "2.0.0",
                capabilities =
                    DeviceCapabilities(
                        cpuArchitecture = "arm64-v8a",
                        gpuAvailable = true,
                        nnapiAvailable = true,
                        totalMemoryMb = 8192,
                        availableStorageMb = 50000,
                    ),
            )

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<DeviceRegistrationRequest>(serialized)

        assertEquals("Google", deserialized.manufacturer)
        assertEquals("Pixel 8", deserialized.model)
        assertEquals("en_US", deserialized.locale)
        assertEquals("us", deserialized.region)
        assertEquals("2.0.0", deserialized.appVersion)
        assertEquals("arm64-v8a", deserialized.capabilities?.cpuArchitecture)
        assertTrue(deserialized.capabilities?.gpuAvailable == true)
        assertEquals(8192, deserialized.capabilities?.totalMemoryMb)
    }

    @Test
    fun `DeviceRegistrationRequest defaults platform to android`() {
        val request =
            DeviceRegistrationRequest(
                deviceIdentifier = "d1",
                orgId = "o1",
                osVersion = "14",
                sdkVersion = "1.0",
            )
        assertEquals("android", request.platform)
    }

    // =========================================================================
    // DeviceRegistrationResponse
    // =========================================================================

    @Test
    fun `DeviceRegistrationResponse roundtrips correctly`() {
        val response =
            DeviceRegistrationResponse(
                id = "uuid-1",
                deviceIdentifier = "device-1",
                orgId = "org-1",
                platform = "android",
                status = "active",
                createdAt = "2024-01-01T00:00:00Z",
                updatedAt = "2024-01-01T00:00:00Z",
                apiToken = "token-123",
            )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<DeviceRegistrationResponse>(serialized)

        assertEquals("uuid-1", deserialized.id)
        assertEquals("active", deserialized.status)
        assertEquals("token-123", deserialized.apiToken)
    }

    @Test
    fun `DeviceRegistrationResponse handles null optional fields`() {
        val jsonStr =
            """
            {
                "id": "uuid-1",
                "device_identifier": "device-1",
                "org_id": "org-1",
                "platform": "android",
                "status": "active",
                "created_at": "2024-01-01T00:00:00Z",
                "updated_at": "2024-01-01T00:00:00Z"
            }
            """.trimIndent()

        val response = json.decodeFromString<DeviceRegistrationResponse>(jsonStr)
        assertNull(response.manufacturer)
        assertNull(response.apiToken)
        assertNull(response.lastHeartbeat)
    }

    // =========================================================================
    // HeartbeatRequest / HeartbeatResponse
    // =========================================================================

    @Test
    fun `HeartbeatRequest roundtrips with all fields`() {
        val request =
            HeartbeatRequest(
                sdkVersion = "1.0.0",
                osVersion = "Android 14",
                appVersion = "2.0.0",
                batteryLevel = 85,
                isCharging = true,
                availableStorageMb = 50000,
                availableMemoryMb = 4096,
                networkType = "wifi",
            )

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<HeartbeatRequest>(serialized)

        assertEquals("1.0.0", deserialized.sdkVersion)
        assertEquals(85, deserialized.batteryLevel)
        assertEquals(true, deserialized.isCharging)
        assertEquals("wifi", deserialized.networkType)
    }

    @Test
    fun `HeartbeatResponse roundtrips correctly`() {
        val response =
            HeartbeatResponse(
                acknowledged = true,
                serverTime = "2024-01-01T12:00:00Z",
            )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<HeartbeatResponse>(serialized)

        assertTrue(deserialized.acknowledged)
        assertEquals("2024-01-01T12:00:00Z", deserialized.serverTime)
    }

    // =========================================================================
    // DeviceGroup
    // =========================================================================

    @Test
    fun `DeviceGroup roundtrips correctly`() {
        val group =
            DeviceGroup(
                id = "group-1",
                name = "beta-testers",
                description = "Beta testing group",
                groupType = "manual",
                isActive = true,
                deviceCount = 42,
                tags = listOf("beta", "internal"),
                createdAt = "2024-01-01T00:00:00Z",
                updatedAt = "2024-01-01T00:00:00Z",
            )

        val serialized = json.encodeToString(group)
        val deserialized = json.decodeFromString<DeviceGroup>(serialized)

        assertEquals("group-1", deserialized.id)
        assertEquals("beta-testers", deserialized.name)
        assertEquals(42, deserialized.deviceCount)
        assertEquals(listOf("beta", "internal"), deserialized.tags)
    }

    @Test
    fun `DeviceGroupsResponse roundtrips correctly`() {
        val response =
            DeviceGroupsResponse(
                groups =
                    listOf(
                        DeviceGroup("g1", "group1", null, "manual", true, 10, null, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z"),
                    ),
                count = 1,
            )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<DeviceGroupsResponse>(serialized)

        assertEquals(1, deserialized.count)
        assertEquals("g1", deserialized.groups[0].id)
    }

    // =========================================================================
    // GroupMembership
    // =========================================================================

    @Test
    fun `GroupMembership roundtrips correctly`() {
        val membership =
            GroupMembership(
                id = "m1",
                deviceId = "d1",
                groupId = "g1",
                groupName = "beta",
                membershipType = "manual",
                createdAt = "2024-01-01T00:00:00Z",
            )

        val serialized = json.encodeToString(membership)
        val deserialized = json.decodeFromString<GroupMembership>(serialized)

        assertEquals("m1", deserialized.id)
        assertEquals("d1", deserialized.deviceId)
        assertEquals("beta", deserialized.groupName)
    }

    @Test
    fun `GroupMembershipsResponse roundtrips correctly`() {
        val response =
            GroupMembershipsResponse(
                memberships =
                    listOf(
                        GroupMembership("m1", "d1", "g1", "beta", "manual", "2024-01-01T00:00:00Z"),
                    ),
                count = 1,
            )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<GroupMembershipsResponse>(serialized)

        assertEquals(1, deserialized.count)
        assertEquals(1, deserialized.memberships.size)
    }

    // =========================================================================
    // VersionResolutionResponse
    // =========================================================================

    @Test
    fun `VersionResolutionResponse roundtrips with optional fields`() {
        val response =
            VersionResolutionResponse(
                version = "2.0.0",
                source = "rollout",
                experimentId = "exp-1",
                rolloutId = 42,
                deviceBucket = 73,
            )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<VersionResolutionResponse>(serialized)

        assertEquals("2.0.0", deserialized.version)
        assertEquals("rollout", deserialized.source)
        assertEquals("exp-1", deserialized.experimentId)
        assertEquals(42L, deserialized.rolloutId)
        assertEquals(73, deserialized.deviceBucket)
    }

    @Test
    fun `VersionResolutionResponse handles minimal response`() {
        val jsonStr = """{"version": "1.0.0", "source": "default"}"""
        val response = json.decodeFromString<VersionResolutionResponse>(jsonStr)

        assertEquals("1.0.0", response.version)
        assertEquals("default", response.source)
        assertNull(response.experimentId)
        assertNull(response.rolloutId)
    }

    // =========================================================================
    // ModelResponse / ModelVersionResponse / ModelDownloadResponse
    // =========================================================================

    @Test
    fun `ModelResponse roundtrips correctly`() {
        val response =
            ModelResponse(
                id = "model-1",
                orgId = "org-1",
                name = "my-model",
                framework = "tensorflow_lite",
                useCase = "classification",
                description = "A test model",
                versionCount = 3,
                createdAt = "2024-01-01T00:00:00Z",
                updatedAt = "2024-01-01T00:00:00Z",
            )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<ModelResponse>(serialized)

        assertEquals("model-1", deserialized.id)
        assertEquals("my-model", deserialized.name)
        assertEquals(3, deserialized.versionCount)
    }

    @Test
    fun `ModelVersionResponse roundtrips with metrics`() {
        val response =
            ModelVersionResponse(
                id = "v1",
                modelId = "m1",
                version = "1.0.0",
                status = "published",
                storagePath = "s3://bucket/model.tflite",
                format = "tensorflow_lite",
                checksum = "abc123",
                sizeBytes = 1_000_000,
                metrics = mapOf("accuracy" to 0.95, "f1" to 0.92),
                createdAt = "2024-01-01T00:00:00Z",
                updatedAt = "2024-01-01T00:00:00Z",
            )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<ModelVersionResponse>(serialized)

        assertEquals("1.0.0", deserialized.version)
        assertEquals(0.95, deserialized.metrics?.get("accuracy"))
        assertEquals(1_000_000, deserialized.sizeBytes)
    }

    @Test
    fun `ModelDownloadResponse roundtrips correctly`() {
        val response =
            ModelDownloadResponse(
                downloadUrl = "https://cdn.example.com/model.tflite",
                expiresAt = "2024-01-01T01:00:00Z",
                checksum = "sha256:abc123",
                sizeBytes = 5_000_000,
            )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<ModelDownloadResponse>(serialized)

        assertEquals("https://cdn.example.com/model.tflite", deserialized.downloadUrl)
        assertEquals("sha256:abc123", deserialized.checksum)
    }

    // =========================================================================
    // TrainingEventRequest / GradientUpdateRequest
    // =========================================================================

    @Test
    fun `TrainingEventRequest roundtrips correctly`() {
        val request =
            TrainingEventRequest(
                deviceId = "d1",
                modelId = "m1",
                version = "1.0",
                eventType = "training_completed",
                timestamp = "2024-01-01T00:00:00Z",
                metrics = mapOf("loss" to 0.1),
                metadata = mapOf("tag" to "experiment-1"),
            )

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<TrainingEventRequest>(serialized)

        assertEquals("d1", deserialized.deviceId)
        assertEquals("training_completed", deserialized.eventType)
        assertEquals(0.1, deserialized.metrics?.get("loss"))
    }

    @Test
    fun `GradientUpdateRequest roundtrips correctly`() {
        val request =
            GradientUpdateRequest(
                deviceId = "d1",
                modelId = "m1",
                version = "1.0",
                roundId = "round-1",
                numSamples = 100,
                trainingTimeMs = 5000,
                metrics =
                    TrainingMetrics(
                        loss = 0.05,
                        accuracy = 0.98,
                        numBatches = 4,
                        learningRate = 0.001,
                    ),
            )

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<GradientUpdateRequest>(serialized)

        assertEquals("round-1", deserialized.roundId)
        assertEquals(100, deserialized.numSamples)
        assertEquals(0.05, deserialized.metrics.loss)
        assertEquals(0.98, deserialized.metrics.accuracy)
    }

    @Test
    fun `GradientUpdateResponse roundtrips correctly`() {
        val response =
            GradientUpdateResponse(
                accepted = true,
                roundId = "round-1",
                message = "Weights accepted",
            )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<GradientUpdateResponse>(serialized)

        assertTrue(deserialized.accepted)
        assertEquals("round-1", deserialized.roundId)
    }

    // =========================================================================
    // TrainingMetrics
    // =========================================================================

    @Test
    fun `TrainingMetrics roundtrips with custom metrics`() {
        val metrics =
            TrainingMetrics(
                loss = 0.1,
                accuracy = 0.95,
                numBatches = 10,
                learningRate = 0.001,
                customMetrics = mapOf("precision" to 0.93, "recall" to 0.91),
            )

        val serialized = json.encodeToString(metrics)
        val deserialized = json.decodeFromString<TrainingMetrics>(serialized)

        assertEquals(0.1, deserialized.loss)
        assertEquals(10, deserialized.numBatches)
        assertEquals(0.93, deserialized.customMetrics?.get("precision"))
    }

    // =========================================================================
    // InferenceEvent DTOs
    // =========================================================================

    @Test
    fun `InferenceEventRequest roundtrips correctly`() {
        val request =
            InferenceEventRequest(
                deviceId = "d1",
                modelId = "m1",
                version = "1.0",
                modality = "text",
                sessionId = "session-1",
                eventType = "generation_started",
                timestampMs = 1706745600000,
                orgId = "org-1",
            )

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<InferenceEventRequest>(serialized)

        assertEquals("d1", deserialized.deviceId)
        assertEquals("text", deserialized.modality)
        assertEquals("session-1", deserialized.sessionId)
    }

    @Test
    fun `InferenceEventMetrics roundtrips correctly`() {
        val metrics =
            InferenceEventMetrics(
                ttfcMs = 50.0,
                chunkIndex = 5,
                chunkLatencyMs = 10.0,
                totalChunks = 100,
                totalDurationMs = 1000.0,
                throughput = 100.0,
            )

        val serialized = json.encodeToString(metrics)
        val deserialized = json.decodeFromString<InferenceEventMetrics>(serialized)

        assertEquals(50.0, deserialized.ttfcMs)
        assertEquals(5, deserialized.chunkIndex)
        assertEquals(100, deserialized.totalChunks)
    }

    @Test
    fun `InferenceEventResponse roundtrips correctly`() {
        val response =
            InferenceEventResponse(
                status = "accepted",
                sessionId = "session-1",
            )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<InferenceEventResponse>(serialized)

        assertEquals("accepted", deserialized.status)
        assertEquals("session-1", deserialized.sessionId)
    }

    // =========================================================================
    // DevicePolicyResponse
    // =========================================================================

    @Test
    fun `DevicePolicyResponse roundtrips correctly`() {
        val policy =
            DevicePolicyResponse(
                batteryThreshold = 30,
                networkPolicy = "wifi_only",
                samplingPolicy = "random_10_percent",
                trainingWindow = "02:00-06:00",
            )

        val serialized = json.encodeToString(policy)
        val deserialized = json.decodeFromString<DevicePolicyResponse>(serialized)

        assertEquals(30, deserialized.batteryThreshold)
        assertEquals("wifi_only", deserialized.networkPolicy)
        assertEquals("random_10_percent", deserialized.samplingPolicy)
        assertEquals("02:00-06:00", deserialized.trainingWindow)
    }

    @Test
    fun `DevicePolicyResponse handles null optional fields`() {
        val jsonStr = """{"battery_threshold": 20, "network_policy": "any"}"""
        val policy = json.decodeFromString<DevicePolicyResponse>(jsonStr)

        assertEquals(20, policy.batteryThreshold)
        assertEquals("any", policy.networkPolicy)
        assertNull(policy.samplingPolicy)
        assertNull(policy.trainingWindow)
    }

    // =========================================================================
    // ErrorResponse / HealthResponse
    // =========================================================================

    @Test
    fun `ErrorResponse roundtrips correctly`() {
        val error = ErrorResponse(detail = "Not found", statusCode = 404)

        val serialized = json.encodeToString(error)
        val deserialized = json.decodeFromString<ErrorResponse>(serialized)

        assertEquals("Not found", deserialized.detail)
        assertEquals(404, deserialized.statusCode)
    }

    @Test
    fun `HealthResponse roundtrips correctly`() {
        val response =
            HealthResponse(
                status = "healthy",
                version = "1.0.0",
                timestamp = "2024-01-01T00:00:00Z",
            )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<HealthResponse>(serialized)

        assertEquals("healthy", deserialized.status)
        assertEquals("1.0.0", deserialized.version)
    }

    // =========================================================================
    // AssignmentRequest
    // =========================================================================

    @Test
    fun `AssignmentRequest has correct defaults`() {
        val request = AssignmentRequest()
        assertNull(request.version)
        assertNull(request.experimentId)
        assertEquals("default", request.variant)
        assertEquals("sdk_registration", request.assignmentReason)
    }

    @Test
    fun `AssignmentRequest roundtrips with custom values`() {
        val request =
            AssignmentRequest(
                version = "2.0.0",
                experimentId = "exp-1",
                variant = "treatment",
                assignmentReason = "manual_override",
            )

        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<AssignmentRequest>(serialized)

        assertEquals("2.0.0", deserialized.version)
        assertEquals("exp-1", deserialized.experimentId)
        assertEquals("treatment", deserialized.variant)
    }

    // =========================================================================
    // RoundAssignment
    // =========================================================================

    @Test
    fun `RoundAssignment roundtrips correctly`() {
        val round = RoundAssignment(
            id = "r-1",
            orgId = "org-1",
            modelId = "model-1",
            versionId = "v-1",
            state = "waiting_for_updates",
            minClients = 5,
            maxClients = 50,
            clientSelectionStrategy = "random",
            aggregationType = "fedavg",
            timeoutMinutes = 30,
            differentialPrivacy = true,
            dpEpsilon = 1.0,
            dpDelta = 1e-5,
            secureAggregation = true,
            secaggThreshold = 3,
            selectedClientCount = 10,
            receivedUpdateCount = 4,
            createdAt = "2026-01-01T00:00:00Z",
            clientSelectionStartedAt = "2026-01-01T00:01:00Z",
            aggregationCompletedAt = null,
        )

        val serialized = json.encodeToString(round)
        val deserialized = json.decodeFromString<RoundAssignment>(serialized)

        assertEquals("r-1", deserialized.id)
        assertEquals("waiting_for_updates", deserialized.state)
        assertEquals("fedavg", deserialized.aggregationType)
        assertTrue(deserialized.secureAggregation)
        assertEquals(3, deserialized.secaggThreshold)
        assertEquals(10, deserialized.selectedClientCount)
        assertNull(deserialized.aggregationCompletedAt)
    }

    @Test
    fun `RoundAssignment handles minimal JSON from server`() {
        val jsonStr = """
            {
                "id": "r-2",
                "org_id": "org-1",
                "model_id": "m-1",
                "version_id": "v-1",
                "state": "initializing",
                "min_clients": 10,
                "max_clients": 100,
                "client_selection_strategy": "random",
                "aggregation_type": "fedavg",
                "timeout_minutes": 30,
                "created_at": "2026-01-01T00:00:00Z"
            }
        """.trimIndent()

        val round = json.decodeFromString<RoundAssignment>(jsonStr)

        assertEquals("r-2", round.id)
        assertFalse(round.differentialPrivacy)
        assertFalse(round.secureAggregation)
        assertNull(round.dpEpsilon)
        assertEquals(0, round.selectedClientCount)
    }

    @Test
    fun `RoundsListResponse roundtrips correctly`() {
        val response = RoundsListResponse(
            rounds = listOf(
                RoundAssignment(
                    id = "r-1",
                    orgId = "org-1",
                    modelId = "m-1",
                    versionId = "v-1",
                    state = "waiting_for_updates",
                    minClients = 5,
                    maxClients = 50,
                    clientSelectionStrategy = "random",
                    aggregationType = "fedavg",
                    timeoutMinutes = 30,
                    createdAt = "2026-01-01T00:00:00Z",
                ),
            ),
        )

        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<RoundsListResponse>(serialized)

        assertEquals(1, deserialized.rounds.size)
        assertEquals("r-1", deserialized.rounds[0].id)
    }

    // =========================================================================
    // Unknown fields
    // =========================================================================

    @Test
    fun `deserialization ignores unknown fields`() {
        val jsonStr =
            """
            {
                "acknowledged": true,
                "server_time": "2024-01-01T00:00:00Z",
                "extra_field": "should_be_ignored"
            }
            """.trimIndent()

        val response = json.decodeFromString<HeartbeatResponse>(jsonStr)
        assertTrue(response.acknowledged)
    }
}
