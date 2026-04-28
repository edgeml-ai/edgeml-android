package ai.octomil.audio

import ai.octomil.errors.OctomilException
import ai.octomil.generated.RoutingPolicy
import ai.octomil.ModelResolver
import ai.octomil.prepare.PrepareManager
import ai.octomil.speech.SpeechRuntime
import ai.octomil.speech.SpeechRuntimeRegistry
import ai.octomil.speech.SpeechSession
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Lifecycle-parity tests for ``client.audio.transcriptions``.
 *
 *   - transcription/prepare — facade builds a sdk_runtime
 *     PrepareCandidate and PrepareManager materializes from the
 *     planner-supplied digest + downloadUrl. Cache hit branch is
 *     idempotent.
 *   - transcription/warmup — warmup loads + retains a
 *     [SpeechRuntime]; subsequent warmup of the same model is a
 *     reuse, not a re-init.
 *   - transcription/app_policy_routing — `@app/` parsing, `app=`
 *     conflict refusal, `policy=cloud_only` refusal.
 */
class AudioTranscriptionsLifecycleTests {
    private lateinit var tmpDir: File
    private lateinit var fakeFactory: RecordingSpeechFactory

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("octomil-stt-life-").toFile()
        fakeFactory = RecordingSpeechFactory()
        SpeechRuntimeRegistry.factory = fakeFactory
    }

    @After
    fun tearDown() {
        SpeechRuntimeRegistry.factory = null
        tmpDir.deleteRecursively()
    }

    // ---------------------------------------------------------------------
    // transcription/app_policy_routing
    // ---------------------------------------------------------------------

    @Test
    fun `resolve parses @app prefix and scopes artifactId by app slug`() {
        val transcriptions = newTranscriptions()
        val resolution = transcriptions.resolveTranscriptionCandidate(
            model = "@app/myapp/whisper-small",
            app = null,
            policy = null,
            digest = "sha256:" + "0".repeat(64),
            downloadUrl = "https://cdn.example/whisper-small.bin",
            relativePath = "model.bin",
            sizeBytes = null,
        )
        assertEquals("whisper-small", resolution.canonicalModelId)
        assertEquals("@app/myapp/whisper-small", resolution.warmupKey)
        assertEquals("@app/myapp/whisper-small", resolution.candidate.artifact?.artifactId)
    }

    @Test
    fun `resolve refuses mismatched @app and explicit app argument`() {
        val transcriptions = newTranscriptions()
        try {
            transcriptions.resolveTranscriptionCandidate(
                model = "@app/myapp/whisper-small",
                app = "other",
                policy = null,
                digest = "sha256:" + "0".repeat(64),
                downloadUrl = "https://cdn.example/whisper-small.bin",
                relativePath = null,
                sizeBytes = null,
            )
            fail("expected app identity mismatch refusal")
        } catch (e: OctomilException) {
            assertTrue(e.message?.contains("does not match") == true)
        }
    }

    @Test
    fun `resolve refuses policy=cloud_only on local STT`() {
        val transcriptions = newTranscriptions()
        try {
            transcriptions.resolveTranscriptionCandidate(
                model = "whisper-small",
                app = null,
                policy = RoutingPolicy.CLOUD_ONLY,
                digest = "sha256:" + "0".repeat(64),
                downloadUrl = "https://cdn.example/whisper-small.bin",
                relativePath = null,
                sizeBytes = null,
            )
            fail("expected cloud_only refusal")
        } catch (e: OctomilException) {
            assertTrue(e.message?.contains("cloud_only") == true)
        }
    }

    // ---------------------------------------------------------------------
    // transcription/prepare
    // ---------------------------------------------------------------------

    @Test
    fun `prepare materializes single-file artifact from cache hit`() = runBlocking {
        val pm = PrepareManager(cacheDir = File(tmpDir, "cache"))
        val transcriptions = newTranscriptions(prepareManager = pm)

        val modelId = "whisper-small-test"
        val payload = "fake-whisper-bytes".toByteArray()
        val digest = "sha256:" + sha256Hex(payload)
        val artifactDir = pm.artifactDirFor(modelId).apply { mkdirs() }
        File(artifactDir, "model.bin").writeBytes(payload)

        val outcome = transcriptions.prepare(
            model = modelId,
            digest = digest,
            downloadUrl = "https://cdn.example/$modelId/model.bin",
            relativePath = "model.bin",
            sizeBytes = payload.size.toLong(),
        )
        assertTrue("expected cache-hit branch", outcome.cached)
        assertTrue(File(outcome.artifactDir, "model.bin").exists())
        assertEquals(modelId, outcome.artifactId)
    }

    @Test
    fun `prepare is idempotent across repeated calls`() = runBlocking {
        val pm = PrepareManager(cacheDir = File(tmpDir, "cache"))
        val transcriptions = newTranscriptions(prepareManager = pm)

        val modelId = "whisper-tiny-test"
        val payload = "fake-whisper-tiny-bytes".toByteArray()
        val digest = "sha256:" + sha256Hex(payload)
        val artifactDir = pm.artifactDirFor(modelId).apply { mkdirs() }
        File(artifactDir, "model.bin").writeBytes(payload)

        val first = transcriptions.prepare(
            model = modelId,
            digest = digest,
            downloadUrl = "https://cdn.example/$modelId/model.bin",
            relativePath = "model.bin",
        )
        val sentinel = File(first.artifactDir, "model.bin")
        val firstMtime = sentinel.lastModified()
        val second = transcriptions.prepare(
            model = modelId,
            digest = digest,
            downloadUrl = "https://cdn.example/$modelId/model.bin",
            relativePath = "model.bin",
        )
        assertEquals(first.artifactDir.absolutePath, second.artifactDir.absolutePath)
        assertEquals(firstMtime, sentinel.lastModified())
    }

    // ---------------------------------------------------------------------
    // transcription/warmup
    // ---------------------------------------------------------------------

    @Test
    fun `warmup loads the runtime once and second warmup is a reuse`() = runBlocking {
        val pm = PrepareManager(cacheDir = File(tmpDir, "cache"))
        val transcriptions = newTranscriptions(prepareManager = pm)

        val modelId = "whisper-small-warmup"
        val payload = "fake".toByteArray()
        val digest = "sha256:" + sha256Hex(payload)
        val artifactDir = pm.artifactDirFor(modelId).apply { mkdirs() }
        File(artifactDir, "model.bin").writeBytes(payload)

        val first = transcriptions.warmup(
            model = modelId,
            digest = digest,
            downloadUrl = "https://cdn.example/$modelId/model.bin",
            relativePath = "model.bin",
        )
        val second = transcriptions.warmup(
            model = modelId,
            digest = digest,
            downloadUrl = "https://cdn.example/$modelId/model.bin",
            relativePath = "model.bin",
        )
        assertEquals(1, fakeFactory.creates)
        assertFalse(first.runtimeReused)
        assertTrue(second.runtimeReused)
        assertEquals(modelId, first.model)
    }

    @Test
    fun `app-scoped warmup uses the app-scoped artifact dir`() = runBlocking {
        val pm = PrepareManager(cacheDir = File(tmpDir, "cache"))
        val transcriptions = newTranscriptions(prepareManager = pm)

        val modelId = "whisper-small-appscoped"
        val payload = "fake-app".toByteArray()
        val digest = "sha256:" + sha256Hex(payload)

        // Stage public + app dirs so a misconfigured route would
        // pick up the wrong bytes.
        val publicDir = pm.artifactDirFor(modelId).apply { mkdirs() }
        File(publicDir, "model.bin").writeBytes("PUBLIC".toByteArray())

        val appArtifactId = "@app/myapp/$modelId"
        val appDir = pm.artifactDirFor(appArtifactId).apply { mkdirs() }
        File(appDir, "model.bin").writeBytes(payload)

        val warm = transcriptions.warmup(
            model = "@app/myapp/$modelId",
            digest = digest,
            downloadUrl = "https://cdn.example/$modelId/model.bin",
            relativePath = "model.bin",
        )
        assertEquals(appDir.canonicalPath, warm.artifactDir.canonicalPath)
        assertEquals(modelId, warm.model)
    }

    private fun newTranscriptions(
        prepareManager: PrepareManager = PrepareManager(cacheDir = File(tmpDir, "cache")),
    ): AudioTranscriptions = AudioTranscriptions(
        contextProvider = { null },
        resolver = ModelResolver.default(),
        prepareManager = prepareManager,
    )

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }
}

private class RecordingSpeechFactory : (File) -> SpeechRuntime {
    var creates: Int = 0
        private set

    override fun invoke(modelDir: File): SpeechRuntime {
        creates += 1
        return object : SpeechRuntime {
            override fun startSession(): SpeechSession = StubSession()
            override fun release() = Unit
        }
    }
}

private class StubSession : SpeechSession {
    private val flow = MutableStateFlow("")
    override val transcript: StateFlow<String> = flow
    override fun feed(samples: FloatArray) = Unit
    override suspend fun finalize(): String = ""
    override fun reset() = Unit
    override fun release() = Unit
}
