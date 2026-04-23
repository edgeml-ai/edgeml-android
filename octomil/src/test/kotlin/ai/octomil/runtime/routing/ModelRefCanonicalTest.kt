package ai.octomil.runtime.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Fixture-driven conformance tests for [ModelRefParser].
 *
 * The fixture is copied from octomil-contracts
 * fixtures/runtime_planner/model_ref_parse_cases.json.
 */
class ModelRefCanonicalTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val fixture: JsonObject by lazy {
        val stream = javaClass.classLoader.getResourceAsStream("model_ref_parse_cases.json")
            ?: error("Missing test resource: model_ref_parse_cases.json")
        json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private val cases get() = fixture["cases"]!!.jsonArray

    @Test
    fun `all fixture cases produce the expected fields`() {
        for (case in cases) {
            val obj = case.jsonObject
            val input = obj["input"]!!.jsonPrimitive.content
            val expected = obj["expected"]!!.jsonObject
            val result = ModelRefParser.parse(input)

            assertEquals(expected["kind"]!!.jsonPrimitive.content, result.kind, "kind mismatch for '$input'")
            assertEquals(expected["raw"]!!.jsonPrimitive.content, result.ref, "raw mismatch for '$input'")

            expected["model_slug"]?.jsonPrimitive?.content?.let { expectedModel ->
                val modelRef = assertIs<ParsedModelRef.ModelRef>(result, "Expected ModelRef for '$input'")
                assertEquals(expectedModel, modelRef.model, "model slug mismatch for '$input'")
            }
            expected["app_slug"]?.jsonPrimitive?.content?.let { expectedSlug ->
                val appRef = assertIs<ParsedModelRef.AppRef>(result, "Expected AppRef for '$input'")
                assertEquals(expectedSlug, appRef.slug, "app slug mismatch for '$input'")
            }
            expected["capability"]?.jsonPrimitive?.content?.let { expectedCapability ->
                when (result) {
                    is ParsedModelRef.AppRef -> assertEquals(expectedCapability, result.capability, "capability mismatch for '$input'")
                    is ParsedModelRef.CapabilityRef -> assertEquals(expectedCapability, result.capability, "capability mismatch for '$input'")
                    else -> error("Expected capability-bearing ref for '$input'")
                }
            }
            expected["deployment_id"]?.jsonPrimitive?.content?.let { expectedDeployment ->
                val deployRef = assertIs<ParsedModelRef.DeploymentRef>(result, "Expected DeploymentRef for '$input'")
                assertEquals(expectedDeployment, deployRef.deploymentId, "deployment id mismatch for '$input'")
            }
            expected["experiment_id"]?.jsonPrimitive?.content?.let { expectedExperiment ->
                val expRef = assertIs<ParsedModelRef.ExperimentRef>(result, "Expected ExperimentRef for '$input'")
                assertEquals(expectedExperiment, expRef.experimentId, "experiment id mismatch for '$input'")
            }
            expected["variant_id"]?.jsonPrimitive?.content?.let { expectedVariant ->
                val expRef = assertIs<ParsedModelRef.ExperimentRef>(result, "Expected ExperimentRef for '$input'")
                assertEquals(expectedVariant, expRef.variantId, "variant id mismatch for '$input'")
            }
        }
    }

    @Test
    fun `fixture covers all 8 canonical kinds`() {
        val expectedKinds = setOf(
            "model",
            "app",
            "capability",
            "deployment",
            "experiment",
            "alias",
            "default",
            "unknown",
        )
        val coveredKinds = cases.map {
            it.jsonObject["expected"]!!.jsonObject["kind"]!!.jsonPrimitive.content
        }.toSet()

        assertEquals(expectedKinds, coveredKinds, "Fixture missing canonical kinds")
    }
}
