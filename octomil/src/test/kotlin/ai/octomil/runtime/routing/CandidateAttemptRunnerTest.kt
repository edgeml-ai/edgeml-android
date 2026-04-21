package ai.octomil.runtime.routing

import ai.octomil.runtime.planner.RuntimeCandidatePlan
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CandidateAttemptRunnerTest {
    @Test
    fun `runWithInference falls back after non-streaming inference error`() = runTest {
        val runner = CandidateAttemptRunner(fallbackAllowed = true)
        val result = runner.runWithInference(
            candidates = listOf(localCandidate(), cloudCandidate()),
        ) { candidate, _ ->
            if (candidate.locality == "local") error("model load failed")
            "cloud-ok"
        }

        assertEquals("cloud-ok", result.value)
        assertTrue(result.fallbackUsed)
        assertEquals("inference_error", result.fallbackTrigger?.code)
        assertEquals("failed", result.attempts.first().status)
        assertEquals("cloud", result.selectedAttempt?.locality)
    }

    @Test
    fun `streaming inference does not fall back after first output`() = runTest {
        var emitted = false
        val runner = CandidateAttemptRunner(fallbackAllowed = true, streaming = true)
        val result = runner.runWithInference(
            candidates = listOf(localCandidate(), cloudCandidate()),
            firstOutputEmitted = { emitted },
        ) { _, _ ->
            emitted = true
            error("stream interrupted")
        }

        assertNull(result.selectedAttempt)
        assertFalse(result.fallbackUsed)
        assertEquals(1, result.attempts.size)
        assertEquals("inference_error_after_first_output", result.attempts.first().reason.code)
    }

    private fun localCandidate(): RuntimeCandidatePlan =
        RuntimeCandidatePlan(
            locality = "local",
            priority = 0,
            confidence = 1.0,
            reason = "local",
            engine = "registered",
        )

    private fun cloudCandidate(): RuntimeCandidatePlan =
        RuntimeCandidatePlan(
            locality = "cloud",
            priority = 1,
            confidence = 1.0,
            reason = "cloud",
            engine = "cloud",
        )
}
