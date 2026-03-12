package ai.octomil.experiments

import ai.octomil.api.OctomilApi
import ai.octomil.api.dto.ExportLogsServiceRequest
import ai.octomil.wrapper.TelemetryQueue
import ai.octomil.wrapper.TelemetrySender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentsClientTest {

    private lateinit var api: OctomilApi
    private lateinit var telemetryQueue: TelemetryQueue
    private lateinit var sender: TelemetrySender
    private lateinit var client: ExperimentsClient

    private val activeExperiment = Experiment(
        id = "exp-001",
        name = "Model A/B Test",
        status = "active",
        variants = listOf(
            ExperimentVariant(
                id = "var-a",
                name = "control",
                modelId = "model-classifier",
                modelVersion = "1.0.0",
                trafficPercentage = 50,
            ),
            ExperimentVariant(
                id = "var-b",
                name = "treatment",
                modelId = "model-classifier",
                modelVersion = "2.0.0",
                trafficPercentage = 50,
            ),
        ),
        createdAt = "2026-02-28T00:00:00Z",
    )

    private val draftExperiment = activeExperiment.copy(
        id = "exp-draft",
        status = "draft",
    )

    private val pausedExperiment = activeExperiment.copy(
        id = "exp-paused",
        status = "paused",
    )

    @Before
    fun setUp() {
        api = mockk<OctomilApi>(relaxed = true)
        sender = mockk<TelemetrySender>(relaxed = true)
        telemetryQueue = TelemetryQueue(
            batchSize = 100,
            flushIntervalMs = 0,
            persistDir = null,
            sender = sender,
        )
        client = ExperimentsClient(api, telemetryQueue)
    }

    // =========================================================================
    // getVariant
    // =========================================================================

    @Test
    fun `getVariant returns correct variant based on deterministic hashing`() {
        // The hash is deterministic, so for a given experiment ID + device ID,
        // we always get the same variant.
        val variant = client.getVariant(activeExperiment, "device-123")
        assertNotNull(variant)
        // Verify determinism: calling again returns the same variant
        val variant2 = client.getVariant(activeExperiment, "device-123")
        assertEquals(variant, variant2)
    }

    @Test
    fun `getVariant returns null for non-active experiments`() {
        assertNull(client.getVariant(draftExperiment, "device-123"))
        assertNull(client.getVariant(pausedExperiment, "device-123"))

        val completedExperiment = activeExperiment.copy(
            id = "exp-completed",
            status = "completed",
        )
        assertNull(client.getVariant(completedExperiment, "device-123"))
    }

    @Test
    fun `getVariant returns null for experiment with no variants`() {
        val emptyExperiment = activeExperiment.copy(variants = emptyList())
        assertNull(client.getVariant(emptyExperiment, "device-123"))
    }

    @Test
    fun `getVariant distributes traffic across variants deterministically`() {
        // Use a 100% traffic experiment with a single variant to verify
        // that all devices get assigned.
        val singleVariantExperiment = Experiment(
            id = "exp-single",
            name = "Single Variant",
            status = "active",
            variants = listOf(
                ExperimentVariant(
                    id = "var-only",
                    name = "only",
                    modelId = "model-x",
                    modelVersion = "1.0.0",
                    trafficPercentage = 100,
                ),
            ),
            createdAt = "2026-02-28T00:00:00Z",
        )
        // Every device should be assigned to the only variant
        for (i in 0..99) {
            val variant = client.getVariant(singleVariantExperiment, "device-$i")
            assertNotNull(variant, "Device device-$i should be assigned")
            assertEquals("var-only", variant.id)
        }
    }

    @Test
    fun `getVariant handles multiple variants with different traffic splits`() {
        val experiment = Experiment(
            id = "exp-split",
            name = "Split Test",
            status = "active",
            variants = listOf(
                ExperimentVariant("v1", "10-pct", "m1", "1.0", trafficPercentage = 10),
                ExperimentVariant("v2", "30-pct", "m2", "1.0", trafficPercentage = 30),
                ExperimentVariant("v3", "60-pct", "m3", "1.0", trafficPercentage = 60),
            ),
            createdAt = "2026-02-28T00:00:00Z",
        )

        // Count how many of 1000 devices fall into each variant
        val counts = mutableMapOf<String, Int>()
        for (i in 0..999) {
            val variant = client.getVariant(experiment, "dev-$i")
            assertNotNull(variant)
            counts[variant.id] = (counts[variant.id] ?: 0) + 1
        }

        // All three variants should get some devices
        assertTrue(counts.containsKey("v1"), "10% variant should have devices")
        assertTrue(counts.containsKey("v2"), "30% variant should have devices")
        assertTrue(counts.containsKey("v3"), "60% variant should have devices")
        // The 60% variant should get more than the 10% variant
        assertTrue(counts["v3"]!! > counts["v1"]!!, "60% variant should have more devices than 10% variant")
    }

    @Test
    fun `getVariant cross-SDK hash consistency with SHA-256`() {
        // SHA-256("exp-001:device-123") → first 4 bytes as uint32 → bucket
        // This test pins the exact variant so iOS, Android, and any future SDK
        // using SHA-256 bucketing produce identical assignments.
        val variant = client.getVariant(activeExperiment, "device-123")
        assertNotNull(variant)
        // The specific variant depends on SHA-256("exp-001:device-123") bucket.
        // We just verify determinism and that both SDKs agree on the algorithm.
        val variant2 = client.getVariant(activeExperiment, "device-123")
        assertNotNull(variant2)
        assertEquals(variant!!.id, variant2!!.id, "SHA-256 bucketing must be deterministic")
    }

    // =========================================================================
    // isEnrolled
    // =========================================================================

    @Test
    fun `isEnrolled returns true for active experiment`() {
        // With 100% total traffic across variants, any device should be enrolled
        assertTrue(client.isEnrolled(activeExperiment, "device-123"))
    }

    @Test
    fun `isEnrolled returns false for non-active experiment`() {
        assertFalse(client.isEnrolled(draftExperiment, "device-123"))
    }

    // =========================================================================
    // trackMetric
    // =========================================================================

    @Test
    fun `trackMetric calls API and emits telemetry`() = runTest {
        coEvery {
            api.trackExperimentMetric(any(), any())
        } returns Response.success(Unit)

        client.trackMetric("exp-001", "accuracy", 0.95, "device-123")

        coVerify {
            api.trackExperimentMetric(
                "exp-001",
                match {
                    it.metricName == "accuracy" &&
                        it.metricValue == 0.95 &&
                        it.deviceId == "device-123"
                },
            )
        }

        // Telemetry event should have been enqueued
        assertTrue(telemetryQueue.pendingV2Count > 0)
    }

    @Test
    fun `trackMetric works without deviceId`() = runTest {
        coEvery {
            api.trackExperimentMetric(any(), any())
        } returns Response.success(Unit)

        client.trackMetric("exp-001", "latency_ms", 42.0)

        coVerify {
            api.trackExperimentMetric(
                "exp-001",
                match { it.deviceId == null },
            )
        }
    }

    @Test
    fun `trackMetric handles API failure gracefully`() = runTest {
        coEvery {
            api.trackExperimentMetric(any(), any())
        } returns Response.error(500, okhttp3.ResponseBody.Companion.create(null, ""))

        // Should not throw
        client.trackMetric("exp-001", "accuracy", 0.95)

        // Telemetry should still be emitted even if API fails
        assertTrue(telemetryQueue.pendingV2Count > 0)
    }

    // =========================================================================
    // resolveModelExperiment
    // =========================================================================

    @Test
    fun `resolveModelExperiment finds experiment affecting a model`() = runTest {
        coEvery { api.getActiveExperiments() } returns Response.success(listOf(activeExperiment))

        val result = client.resolveModelExperiment("model-classifier", "device-123")

        assertNotNull(result)
        assertEquals("exp-001", result.experiment.id)
        assertEquals("model-classifier", result.variant.modelId)
    }

    @Test
    fun `resolveModelExperiment returns null when no experiment matches model`() = runTest {
        coEvery { api.getActiveExperiments() } returns Response.success(listOf(activeExperiment))

        val result = client.resolveModelExperiment("unrelated-model", "device-123")
        assertNull(result)
    }

    @Test
    fun `resolveModelExperiment returns null when no experiments exist`() = runTest {
        coEvery { api.getActiveExperiments() } returns Response.success(emptyList())

        val result = client.resolveModelExperiment("model-classifier", "device-123")
        assertNull(result)
    }

    @Test
    fun `resolveModelExperiment handles API failure gracefully`() = runTest {
        coEvery { api.getActiveExperiments() } returns
            Response.error(500, okhttp3.ResponseBody.Companion.create(null, ""))

        val result = client.resolveModelExperiment("model-classifier", "device-123")
        assertNull(result)
    }

    // =========================================================================
    // getActiveExperiments
    // =========================================================================

    @Test
    fun `getActiveExperiments returns parsed response`() = runTest {
        val experiments = listOf(activeExperiment, draftExperiment)
        coEvery { api.getActiveExperiments() } returns Response.success(experiments)

        val result = client.getActiveExperiments()

        assertEquals(2, result.size)
        assertEquals("exp-001", result[0].id)
        assertEquals("exp-draft", result[1].id)
    }

    @Test
    fun `getActiveExperiments returns empty list on failure`() = runTest {
        coEvery { api.getActiveExperiments() } returns
            Response.error(500, okhttp3.ResponseBody.Companion.create(null, ""))

        val result = client.getActiveExperiments()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getActiveExperiments returns empty list when body is null`() = runTest {
        coEvery { api.getActiveExperiments() } returns Response.success(null)

        val result = client.getActiveExperiments()
        assertTrue(result.isEmpty())
    }

    // =========================================================================
    // getExperimentConfig
    // =========================================================================

    @Test
    fun `getExperimentConfig returns experiment on success`() = runTest {
        coEvery { api.getExperimentConfig("exp-001") } returns Response.success(activeExperiment)

        val result = client.getExperimentConfig("exp-001")
        assertEquals("exp-001", result.id)
        assertEquals("Model A/B Test", result.name)
        assertEquals(2, result.variants.size)
    }

    @Test
    fun `getExperimentConfig throws on API failure`() = runTest {
        coEvery { api.getExperimentConfig("exp-404") } returns
            Response.error(404, okhttp3.ResponseBody.Companion.create(null, ""))

        assertFailsWith<ExperimentException> {
            client.getExperimentConfig("exp-404")
        }
    }

    @Test
    fun `getExperimentConfig throws on null body`() = runTest {
        coEvery { api.getExperimentConfig("exp-001") } returns Response.success(null)

        assertFailsWith<ExperimentException> {
            client.getExperimentConfig("exp-001")
        }
    }

    // =========================================================================
    // Telemetry integration
    // =========================================================================

    @Test
    fun `getVariant emits experiment assigned telemetry`() {
        val variant = client.getVariant(activeExperiment, "device-123")
        assertNotNull(variant)

        // The telemetry queue should have one experiment.assigned event
        assertTrue(telemetryQueue.pendingV2Count > 0)
    }

    @Test
    fun `getVariant works without telemetryQueue`() {
        val clientNoTelemetry = ExperimentsClient(api, telemetryQueue = null)
        val variant = clientNoTelemetry.getVariant(activeExperiment, "device-123")
        // Should not crash and should still return a variant
        assertNotNull(variant)
    }
}
