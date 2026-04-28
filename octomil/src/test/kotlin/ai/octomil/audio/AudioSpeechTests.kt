package ai.octomil.audio

import ai.octomil.errors.OctomilException
import ai.octomil.generated.RoutingPolicy
import ai.octomil.prepare.MaterializationPlan
import ai.octomil.prepare.PrepareManager
import ai.octomil.prepare.StaticRecipe
import ai.octomil.prepare.StaticRecipeFile
import ai.octomil.prepare.StaticRecipeRegistry
import ai.octomil.tts.TtsResult
import ai.octomil.tts.TtsRuntime
import ai.octomil.tts.TtsRuntimeFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
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
 * Lifecycle-parity tests for ``client.audio.speech``.
 *
 * Each test covers one of the lifecycle cells the
 * `capability_lifecycle_parity.yaml` parity contract scores:
 *
 *   - tts_speech/create — facade exists and routes to a registered
 *     [TtsRuntimeFactory].
 *   - tts_speech/prepare — facade triggers the [PrepareManager]
 *     materialization path, lands the canonical Kokoro layout on
 *     disk, and is idempotent on cache hit.
 *   - tts_speech/warmup — warmup loads + retains a [TtsRuntime]
 *     handle; the next create reuses it (no second factory call).
 *   - tts_speech/app_policy_routing — `@app/` parsing, `app=`
 *     scoping, and policy refusal of cloud-only paths.
 */
class AudioSpeechTests {
    private lateinit var tmpDir: File
    private lateinit var fakeFactory: RecordingTtsFactory

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("octomil-audio-speech-").toFile()
        fakeFactory = RecordingTtsFactory()
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // ---------------------------------------------------------------------
    // tts_speech/app_policy_routing
    // ---------------------------------------------------------------------

    @Test
    fun `resolveCandidate parses @app prefix and scopes artifactId by app slug`() {
        val speech = AudioSpeech(prepareManager = PrepareManager(cacheDir = tmpDir))
        val resolution = speech.resolveCandidate(
            model = "@app/myapp/kokoro-82m",
            app = null,
            policy = null,
        )
        assertEquals("kokoro-82m", resolution.canonicalModelId)
        assertEquals("myapp", resolution.appSlug)
        assertEquals("@app/myapp/kokoro-82m", resolution.candidate.artifact?.artifactId)
        assertEquals("static_recipe", resolution.candidate.artifact?.source)
    }

    @Test
    fun `resolveCandidate refuses mismatched @app and explicit app argument`() {
        val speech = AudioSpeech(prepareManager = PrepareManager(cacheDir = tmpDir))
        try {
            speech.resolveCandidate(
                model = "@app/myapp/kokoro-82m",
                app = "different",
                policy = null,
            )
            fail("expected app-identity mismatch to be refused")
        } catch (e: OctomilException) {
            assertTrue(e.message?.contains("does not match") == true)
        }
    }

    @Test
    fun `resolveCandidate refuses policy=cloud_only on local audio surface`() {
        val speech = AudioSpeech(prepareManager = PrepareManager(cacheDir = tmpDir))
        try {
            speech.resolveCandidate(
                model = "kokoro-82m",
                app = null,
                policy = RoutingPolicy.CLOUD_ONLY,
            )
            fail("expected cloud_only to be refused on local audio surface")
        } catch (e: OctomilException) {
            assertTrue(e.message?.contains("cloud_only") == true)
        }
    }

    @Test
    fun `resolveCandidate uses bare model id when no app context is provided`() {
        val speech = AudioSpeech(prepareManager = PrepareManager(cacheDir = tmpDir))
        val resolution = speech.resolveCandidate(
            model = "kokoro-82m",
            app = null,
            policy = RoutingPolicy.LOCAL_ONLY,
        )
        assertEquals("kokoro-82m", resolution.canonicalModelId)
        assertEquals("kokoro-82m", resolution.candidate.artifact?.artifactId)
        assertEquals(null, resolution.appSlug)
    }

    // ---------------------------------------------------------------------
    // tts_speech/prepare
    // ---------------------------------------------------------------------

    @Test
    fun `prepare materializes Kokoro layout via PrepareManager on cache hit`() = runBlocking {
        // Register a private recipe with a digest that matches the
        // pre-staged archive bytes. PrepareManager hits the
        // alreadyVerified branch and Materializer unpacks the
        // recipe-declared layout.
        val (recipeId, archive, archiveDigest) = stageKokoroRecipe(tmpDir)

        val cacheDir = File(tmpDir, "cache")
        val pm = PrepareManager(cacheDir = cacheDir)
        val artifactDir = pm.artifactDirFor(recipeId).apply { mkdirs() }
        archive.copyTo(File(artifactDir, "kokoro-en-v0_19.tar.bz2"))

        val speech = AudioSpeech(
            prepareManager = pm,
            factoryProvider = { fakeFactory },
        )
        val outcome = speech.prepare(model = recipeId)
        assertTrue("expected cache-hit branch", outcome.cached)
        // Kokoro layout invariants — the recipe declares these as
        // requiredOutputs and Materializer must produce them.
        assertTrue(File(outcome.artifactDir, "model.onnx").exists())
        assertTrue(File(outcome.artifactDir, "voices.bin").exists())
        assertTrue(File(outcome.artifactDir, "tokens.txt").exists())
        assertTrue(File(outcome.artifactDir, "espeak-ng-data/phontab").exists())
    }

    @Test
    fun `prepare is idempotent across repeated calls`() = runBlocking {
        val (recipeId, archive, _) = stageKokoroRecipe(tmpDir)

        val cacheDir = File(tmpDir, "cache")
        val pm = PrepareManager(cacheDir = cacheDir)
        val artifactDir = pm.artifactDirFor(recipeId).apply { mkdirs() }
        archive.copyTo(File(artifactDir, "kokoro-en-v0_19.tar.bz2"))

        val speech = AudioSpeech(prepareManager = pm, factoryProvider = { fakeFactory })
        val first = speech.prepare(model = recipeId)
        val sentinel = File(first.artifactDir, "model.onnx")
        val firstMtime = sentinel.lastModified()
        val second = speech.prepare(model = recipeId)
        assertEquals(first.artifactDir.absolutePath, second.artifactDir.absolutePath)
        assertEquals(
            "second prepare must not rewrite materialized outputs",
            firstMtime,
            sentinel.lastModified(),
        )
    }

    // ---------------------------------------------------------------------
    // tts_speech/warmup + tts_speech/create
    // ---------------------------------------------------------------------

    @Test
    fun `warmup loads the runtime and create reuses the warmed handle`() = runBlocking {
        val (recipeId, archive, _) = stageKokoroRecipe(tmpDir)
        val cacheDir = File(tmpDir, "cache")
        val pm = PrepareManager(cacheDir = cacheDir)
        val artifactDir = pm.artifactDirFor(recipeId).apply { mkdirs() }
        archive.copyTo(File(artifactDir, "kokoro-en-v0_19.tar.bz2"))

        val speech = AudioSpeech(prepareManager = pm, factoryProvider = { fakeFactory })

        val warm = speech.warmup(model = recipeId)
        assertEquals(1, fakeFactory.creates)
        assertEquals(recipeId, warm.model)
        assertFalse("first warmup is not a reuse", warm.runtimeReused)

        val result = speech.create(model = recipeId, input = "hi")
        // create() must not allocate a second runtime — warmup
        // primed the handle.
        assertEquals(1, fakeFactory.creates)
        assertEquals("hi", result.audioBytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `repeated warmup of same model is a no-op (runtimeReused=true)`() = runBlocking {
        val (recipeId, archive, _) = stageKokoroRecipe(tmpDir)
        val cacheDir = File(tmpDir, "cache")
        val pm = PrepareManager(cacheDir = cacheDir)
        val artifactDir = pm.artifactDirFor(recipeId).apply { mkdirs() }
        archive.copyTo(File(artifactDir, "kokoro-en-v0_19.tar.bz2"))

        val speech = AudioSpeech(prepareManager = pm, factoryProvider = { fakeFactory })
        speech.warmup(model = recipeId)
        val second = speech.warmup(model = recipeId)
        assertEquals(1, fakeFactory.creates)
        assertTrue(second.runtimeReused)
    }

    @Test
    fun `create lazy-loads runtime when no warmup ran`() = runBlocking {
        val (recipeId, archive, _) = stageKokoroRecipe(tmpDir)
        val cacheDir = File(tmpDir, "cache")
        val pm = PrepareManager(cacheDir = cacheDir)
        val artifactDir = pm.artifactDirFor(recipeId).apply { mkdirs() }
        archive.copyTo(File(artifactDir, "kokoro-en-v0_19.tar.bz2"))

        val speech = AudioSpeech(prepareManager = pm, factoryProvider = { fakeFactory })
        val result = speech.create(model = recipeId, input = "hello", voice = "af_bella", speed = 1.1f)
        assertEquals(1, fakeFactory.creates)
        // The recording factory copies its input into audioBytes so
        // we can assert the synthesize call landed.
        assertEquals("hello", result.audioBytes.toString(Charsets.UTF_8))
        assertEquals("af_bella", result.voice)
    }

    @Test
    fun `create surfaces RUNTIME_UNAVAILABLE when no factory is registered`() = runBlocking {
        val (recipeId, archive, _) = stageKokoroRecipe(tmpDir)
        val cacheDir = File(tmpDir, "cache")
        val pm = PrepareManager(cacheDir = cacheDir)
        val artifactDir = pm.artifactDirFor(recipeId).apply { mkdirs() }
        archive.copyTo(File(artifactDir, "kokoro-en-v0_19.tar.bz2"))

        val speech = AudioSpeech(prepareManager = pm, factoryProvider = { null })
        try {
            speech.create(model = recipeId, input = "hello")
            fail("expected RUNTIME_UNAVAILABLE")
        } catch (e: OctomilException) {
            assertTrue(e.message?.contains("No TTS runtime factory") == true)
        }
    }

    @Test
    fun `app-scoped warmup writes under the app-scoped artifact dir`() = runBlocking {
        // Stage TWO copies of the same archive — one under the
        // public artifact dir, one under the @app/myapp/-scoped
        // dir — so the warmup against `@app/myapp/...` reads its
        // OWN artifact dir, not the shared one.
        val (recipeId, archive, _) = stageKokoroRecipe(tmpDir)
        val cacheDir = File(tmpDir, "cache")
        val pm = PrepareManager(cacheDir = cacheDir)

        val publicDir = pm.artifactDirFor(recipeId).apply { mkdirs() }
        archive.copyTo(File(publicDir, "kokoro-en-v0_19.tar.bz2"))

        val appArtifactId = "@app/myapp/$recipeId"
        val appDir = pm.artifactDirFor(appArtifactId).apply { mkdirs() }
        archive.copyTo(File(appDir, "kokoro-en-v0_19.tar.bz2"))

        val speech = AudioSpeech(prepareManager = pm, factoryProvider = { fakeFactory })
        val warm = speech.warmup(model = "@app/myapp/$recipeId")
        // App-scoped preparation must use the app artifact dir,
        // never the public one — that's the identity gate.
        assertEquals(appDir.canonicalPath, warm.artifactDir.canonicalPath)
        assertNotNull(File(warm.artifactDir, "model.onnx"))
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private data class StagedRecipe(
        val recipeId: String,
        val archive: File,
        val digest: String,
    )

    private fun stageKokoroRecipe(workDir: File): StagedRecipe {
        val recipeId = "kokoro-test-${System.nanoTime()}"
        val archive = makeKokoroLayoutTarball(workDir)
        val digest = "sha256:" + sha256Hex(archive.readBytes())
        val recipe = StaticRecipe(
            modelId = recipeId,
            file = StaticRecipeFile(
                relativePath = "kokoro-en-v0_19.tar.bz2",
                url = "file://" + archive.parentFile.absolutePath,
                digest = digest,
            ),
            materialization = MaterializationPlan(
                kind = MaterializationPlan.Kind.ARCHIVE,
                source = "kokoro-en-v0_19.tar.bz2",
                archiveFormat = MaterializationPlan.ArchiveFormat.TAR_BZ2,
                stripPrefix = "kokoro-en-v0_19/",
                requiredOutputs = listOf(
                    "model.onnx",
                    "voices.bin",
                    "tokens.txt",
                    "espeak-ng-data/phontab",
                ),
            ),
        )
        StaticRecipeRegistry.register(recipe, id = recipeId)
        return StagedRecipe(recipeId, archive, digest)
    }

    private fun makeKokoroLayoutTarball(dir: File): File {
        val archive = File(dir, "kokoro-en-v0_19-${System.nanoTime()}.tar.bz2")
        val entries = listOf(
            "kokoro-en-v0_19/model.onnx" to "fake-onnx",
            "kokoro-en-v0_19/voices.bin" to "fake-voices",
            "kokoro-en-v0_19/tokens.txt" to "fake-tokens",
            "kokoro-en-v0_19/espeak-ng-data/phontab" to "fake-phontab",
        )
        FileOutputStream(archive).use { fos ->
            BZip2CompressorOutputStream(fos).use { bz2 ->
                TarArchiveOutputStream(bz2).use { tar ->
                    for ((name, content) in entries) {
                        val bytes = content.toByteArray()
                        val entry = TarArchiveEntry(name)
                        entry.size = bytes.size.toLong()
                        tar.putArchiveEntry(entry)
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }
                    tar.finish()
                }
            }
        }
        return archive
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Test double: counts factory.create calls and echoes the input
 * back through the audio bytes so the assertions in `create` /
 * `warmup` tests can correlate without inspecting native code.
 */
private class RecordingTtsFactory : TtsRuntimeFactory {
    var creates: Int = 0
        private set

    override fun create(modelDir: File, modelName: String): TtsRuntime =
        EchoRuntime(modelName).also { creates += 1 }
}

private class EchoRuntime(override val modelName: String) : TtsRuntime {
    override fun synthesize(text: String, voice: String?, speed: Float): TtsResult =
        TtsResult(
            audioBytes = text.toByteArray(Charsets.UTF_8),
            contentType = "audio/wav",
            format = "wav",
            sampleRate = 24_000,
            durationMs = 100,
            voice = voice,
            model = modelName,
        )

    override fun release() = Unit
}
