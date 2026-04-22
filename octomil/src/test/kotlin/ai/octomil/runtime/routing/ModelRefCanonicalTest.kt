package ai.octomil.runtime.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Data-driven conformance tests for [ModelRefParser] using the canonical
 * fixture from octomil-contracts (fixtures/model_refs/canonical.json).
 *
 * The fixture is the single source of truth for model ref classification.
 * If this test fails, fix the parser -- not the fixture.
 */
class ModelRefCanonicalTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val fixture: JsonObject by lazy {
        val stream = javaClass.classLoader.getResourceAsStream("model_refs/canonical.json")
            ?: error("Missing test resource: model_refs/canonical.json")
        json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private val deprecatedAliases: JsonObject by lazy {
        val stream = javaClass.classLoader.getResourceAsStream("model_refs/deprecated_aliases.json")
            ?: error("Missing test resource: model_refs/deprecated_aliases.json")
        json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private val cases get() = fixture["cases"]!!.jsonArray

    // =========================================================================
    // Kind classification
    // =========================================================================

    @Test
    fun `all fixture cases produce the expected kind`() {
        for (case in cases) {
            val obj = case.jsonObject
            val input = obj["input"]!!.jsonPrimitive.content
            val expectedKind = obj["expected_kind"]!!.jsonPrimitive.content
            val description = obj["description"]?.jsonPrimitive?.content ?: input

            val result = ModelRefParser.parse(input)
            assertEquals(
                expectedKind,
                result.kind,
                "Failed for input='$input' ($description): expected kind='$expectedKind', got='${result.kind}'"
            )
        }
    }

    // =========================================================================
    // App ref field extraction
    // =========================================================================

    @Test
    fun `app ref cases extract correct slug and capability`() {
        for (case in cases) {
            val obj = case.jsonObject
            if (obj["expected_kind"]?.jsonPrimitive?.content != "app") continue

            val input = obj["input"]!!.jsonPrimitive.content
            val expectedSlug = obj["expected_app_slug"]!!.jsonPrimitive.content
            val expectedCap = obj["expected_capability"]!!.jsonPrimitive.content

            val result = ModelRefParser.parse(input)
            assertIs<ParsedModelRef.AppRef>(result, "Expected AppRef for '$input'")
            assertEquals(expectedSlug, result.slug, "Slug mismatch for '$input'")
            assertEquals(expectedCap, result.capability, "Capability mismatch for '$input'")
        }
    }

    // =========================================================================
    // Deployment ref field extraction
    // =========================================================================

    @Test
    fun `deployment ref cases extract correct deployment id`() {
        for (case in cases) {
            val obj = case.jsonObject
            if (obj["expected_kind"]?.jsonPrimitive?.content != "deployment") continue

            val input = obj["input"]!!.jsonPrimitive.content
            val expectedId = obj["expected_deployment_id"]!!.jsonPrimitive.content

            val result = ModelRefParser.parse(input)
            assertIs<ParsedModelRef.DeploymentRef>(result, "Expected DeploymentRef for '$input'")
            assertEquals(expectedId, result.deploymentId, "Deployment ID mismatch for '$input'")
        }
    }

    // =========================================================================
    // Experiment ref field extraction
    // =========================================================================

    @Test
    fun `experiment ref cases extract correct experiment and variant ids`() {
        for (case in cases) {
            val obj = case.jsonObject
            if (obj["expected_kind"]?.jsonPrimitive?.content != "experiment") continue

            val input = obj["input"]!!.jsonPrimitive.content
            val expectedExpId = obj["expected_experiment_id"]!!.jsonPrimitive.content
            val expectedVarId = obj["expected_variant_id"]!!.jsonPrimitive.content

            val result = ModelRefParser.parse(input)
            assertIs<ParsedModelRef.ExperimentRef>(result, "Expected ExperimentRef for '$input'")
            assertEquals(expectedExpId, result.experimentId, "Experiment ID mismatch for '$input'")
            assertEquals(expectedVarId, result.variantId, "Variant ID mismatch for '$input'")
        }
    }

    // =========================================================================
    // Deprecated aliases
    // =========================================================================

    @Test
    fun `parser never produces deprecated kind values`() {
        val deprecatedKinds = deprecatedAliases["deprecated_to_canonical"]!!
            .jsonObject.keys

        for (case in cases) {
            val obj = case.jsonObject
            val input = obj["input"]!!.jsonPrimitive.content
            val result = ModelRefParser.parse(input)
            assertTrue(
                result.kind !in deprecatedKinds,
                "Parser produced deprecated kind '${result.kind}' for input '$input'"
            )
        }
    }

    // =========================================================================
    // All 8 canonical kinds covered
    // =========================================================================

    @Test
    fun `fixture covers all 8 canonical kinds`() {
        val expectedKinds = setOf(
            "model", "app", "capability", "deployment",
            "experiment", "alias", "default", "unknown"
        )
        val coveredKinds = cases.map {
            it.jsonObject["expected_kind"]!!.jsonPrimitive.content
        }.toSet()

        assertEquals(expectedKinds, coveredKinds, "Fixture missing some canonical kinds")
    }
}
