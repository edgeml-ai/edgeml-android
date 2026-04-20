package ai.octomil.runtime.planner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuntimePlannerContractConformanceTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Serializable
    private data class Fixture(
        val description: String,
        val request: FixtureRequest,
        @SerialName("planner_response") val plannerResponse: RuntimePlanResponse,
        @SerialName("expected_route_metadata") val expectedRouteMetadata: RouteMetadata? = null,
        @SerialName("expected_telemetry") val expectedTelemetry: JsonObject,
        @SerialName("expected_policy_result") val expectedPolicyResult: PolicyResult,
        @SerialName("forbidden_telemetry_keys") val forbiddenTelemetryKeys: List<String> = emptyList(),
        @SerialName("rules_tested") val rulesTested: List<String> = emptyList(),
    )

    @Serializable
    private data class FixtureRequest(
        val model: String,
        val capability: String,
        @SerialName("routing_policy") val routingPolicy: String,
    )

    @Serializable
    private data class PolicyResult(
        @SerialName("cloud_allowed") val cloudAllowed: Boolean,
        @SerialName("local_allowed") val localAllowed: Boolean? = null,
        @SerialName("fallback_allowed") val fallbackAllowed: Boolean,
        val private: Boolean = false,
    )

    private val fixtureNames = listOf(
        "app_ref_local_first_cloud_fallback",
        "app_ref_local_only",
        "capability_chat_default_model",
        "deployment_ref_cloud_only",
        "experiment_variant_resolved",
        "runtime_plan_cloud_fallback_disallowed",
        "runtime_plan_local_candidate_gates",
        "stream_post_first_token_no_fallback",
        "stream_pre_first_token_fallback",
        "telemetry_route_attempt_upload",
    )

    private fun loadFixture(name: String): Fixture {
        val stream = javaClass.classLoader!!.getResourceAsStream("sdk_parity/$name.json")
            ?: error("Missing sdk_parity fixture: $name")
        return stream.bufferedReader().use { json.decodeFromString(Fixture.serializer(), it.readText()) }
    }

    @Test
    fun `all canonical SDK parity fixtures decode`() {
        for (name in fixtureNames) {
            val fixture = loadFixture(name)
            assertTrue(fixture.description.isNotBlank(), "$name missing description")
            assertTrue(fixture.plannerResponse.candidates.isNotEmpty(), "$name missing candidates")
            assertTrue(fixture.rulesTested.isNotEmpty(), "$name missing rules_tested")
        }
    }

    @Test
    fun `app ref fixtures include app resolution`() {
        for (name in fixtureNames) {
            val fixture = loadFixture(name)
            if (!fixture.request.model.startsWith("@app/")) continue
            val resolution = assertNotNull(fixture.plannerResponse.appResolution, "$name missing app_resolution")
            assertEquals(fixture.plannerResponse.model, resolution.selectedModel)
            assertEquals(fixture.request.capability, resolution.capability)
        }
    }

    @Test
    fun `planner candidates preserve gates and fallback allowance`() {
        for (name in fixtureNames) {
            val fixture = loadFixture(name)
            assertEquals(
                fixture.expectedPolicyResult.fallbackAllowed,
                fixture.plannerResponse.fallbackAllowed,
                "$name fallback_allowed drift",
            )
            for (candidate in fixture.plannerResponse.candidates) {
                assertTrue(candidate.locality == "local" || candidate.locality == "cloud", "$name invalid locality")
                for (gate in candidate.gates) {
                    assertTrue(gate.source in setOf("server", "sdk", "runtime"), "$name invalid gate source")
                }
            }
        }
    }

    @Test
    fun `route metadata never exposes on_device locality`() {
        for (name in fixtureNames) {
            val route = loadFixture(name).expectedRouteMetadata ?: continue
            assertTrue(route.status in setOf("selected", "unavailable", "failed"), "$name invalid status")
            assertFalse(route.execution?.locality == "on_device", "$name leaked on_device execution locality")
            for (attempt in route.attempts) {
                assertFalse(attempt.locality == "on_device", "$name leaked on_device attempt locality")
            }
        }
    }

    @Test
    fun `telemetry fixtures contain no forbidden payload keys`() {
        val defaultForbidden = setOf(
            "prompt",
            "input",
            "output",
            "completion",
            "audio",
            "audio_bytes",
            "file_path",
            "text",
            "content",
            "messages",
            "system_prompt",
            "documents",
        )

        for (name in fixtureNames) {
            val fixture = loadFixture(name)
            val forbidden = defaultForbidden + fixture.forbiddenTelemetryKeys
            val keys = mutableSetOf<String>()
            collectKeys(fixture.expectedTelemetry, keys)
            assertTrue(keys.intersect(forbidden).isEmpty(), "$name telemetry contains forbidden keys")
        }
    }

    private fun collectKeys(element: JsonElement, keys: MutableSet<String>) {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    keys.add(key)
                    collectKeys(value, keys)
                }
            }
            else -> Unit
        }
    }
}
