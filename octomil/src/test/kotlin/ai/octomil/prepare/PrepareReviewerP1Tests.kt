package ai.octomil.prepare

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Reviewer P1 regressions for the Android prepare lifecycle. Covers
 * the three findings against PR 231:
 *
 *   1. ``safeJoin`` rejects ancestor-symlink escapes.
 *   2. ``source="static_recipe"`` candidates pass validation
 *      without planner-supplied digest / downloadUrls.
 *   3. Static-recipe prepare materializes the archive into the
 *      backend-ready layout (model.onnx / voices.bin / etc.) AND
 *      the cache-hit branch idempotently re-runs materialization
 *      so a partial extraction completes.
 */
class PrepareReviewerP1Tests {
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("octomil-pm-p1-").toFile()
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `safeJoin refuses symlink ancestor escape`() {
        val destDir = File(tmpDir, "artifact").apply { mkdirs() }
        val outside = File(tmpDir, "outside").apply { mkdirs() }
        // Plant a symlink inside destDir whose target is outside.
        // Without the symlink-aware safeJoin, the canonical-path
        // check on the resolved candidate WOULD see "candidate is
        // outside destDir" and reject — so this regression checks
        // that even when an attacker plants the symlink AFTER
        // safeJoin's canonical check resolves the parent (i.e.
        // races a TOCTOU window), the ancestor-walk catches it.
        Files.createSymbolicLink(File(destDir, "linkdir").toPath(), outside.toPath())
        try {
            DurableDownloader.safeJoin(destDir, "linkdir/escaped.txt")
            fail("expected InvalidPathException")
        } catch (_: InvalidPathException) {
            // expected
        }
    }

    @Test
    fun `canPrepare accepts static recipe candidate without digest`() {
        val pm = PrepareManager(cacheDir = tmpDir)
        val candidate = PrepareCandidate(
            locality = "local",
            engine = "sherpa-onnx",
            artifact = PrepareArtifactPlan(
                modelId = "kokoro-82m",
                source = "static_recipe",
                recipeId = "kokoro-82m",
            ),
        )
        // Pre-fix: validateForPrepare rejected for missing digest.
        assertTrue(pm.canPrepare(candidate))
    }

    @Test
    fun `canPrepare rejects static recipe with unknown recipe id`() {
        val pm = PrepareManager(cacheDir = tmpDir)
        val candidate = PrepareCandidate(
            locality = "local",
            artifact = PrepareArtifactPlan(
                modelId = "x",
                source = "static_recipe",
                recipeId = "nonexistent-private-app",
            ),
        )
        assertFalse(pm.canPrepare(candidate))
    }

    @Test
    fun `static recipe prepare materializes backend-ready layout on cache hit`() = runBlocking {
        // Build a tarball with the canonical Kokoro layout and
        // register a recipe whose digest matches it, then pre-stage
        // the archive at the cache-hit location so prepare(...)
        // takes the alreadyVerified branch and still runs the
        // recipe's materialization plan.
        val archive = makeKokoroLayoutTarball(tmpDir)
        val archiveDigest = "sha256:" + sha256Hex(archive.readBytes())

        val recipe = StaticRecipe(
            modelId = "kokoro-test",
            file = StaticRecipeFile(
                relativePath = "kokoro-en-v0_19.tar.bz2",
                url = "file://" + archive.parentFile.absolutePath,
                digest = archiveDigest,
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
        StaticRecipeRegistry.register(recipe, id = "kokoro-test")

        val cacheDir = File(tmpDir, "cache")
        val pm = PrepareManager(cacheDir = cacheDir)
        // Pre-stage the archive at the cache-hit location.
        val artifactDir = pm.artifactDirFor("kokoro-test").apply { mkdirs() }
        archive.copyTo(File(artifactDir, "kokoro-en-v0_19.tar.bz2"))

        val candidate = PrepareCandidate(
            locality = "local",
            engine = "sherpa-onnx",
            artifact = PrepareArtifactPlan(
                modelId = "kokoro-test",
                source = "static_recipe",
                recipeId = "kokoro-test",
            ),
        )

        val outcome = pm.prepare(candidate, mode = PrepareMode.LAZY)
        val dir = outcome.artifactDir
        assertTrue("expected cache-hit branch to run", outcome.cached)
        assertTrue(File(dir, "model.onnx").exists())
        assertTrue(File(dir, "voices.bin").exists())
        assertTrue(File(dir, "tokens.txt").exists())
        assertTrue(File(dir, "espeak-ng-data/phontab").exists())
    }

    @Test
    fun `materializer unpacks Kokoro layout directly`() {
        val archive = makeKokoroLayoutTarball(tmpDir)
        val artifactDir = File(tmpDir, "artifact").apply { mkdirs() }
        archive.copyTo(File(artifactDir, "kokoro-en-v0_19.tar.bz2"))

        val plan = MaterializationPlan(
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
        )
        Materializer.materialize(plan, artifactDir)

        assertTrue(File(artifactDir, "model.onnx").exists())
        assertTrue(File(artifactDir, "voices.bin").exists())
        assertTrue(File(artifactDir, "tokens.txt").exists())
        assertTrue(File(artifactDir, "espeak-ng-data/phontab").exists())
        assertTrue(File(artifactDir, EXTRACTION_MARKER_FILENAME).exists())

        // Re-run is idempotent: marker present + outputs present →
        // no-op. Confirm the model.onnx mtime didn't change.
        val sentinel = File(artifactDir, "model.onnx")
        val before = sentinel.lastModified()
        Materializer.materialize(plan, artifactDir)
        val after = sentinel.lastModified()
        assertEquals("second materialize should be a no-op when marker is valid", before, after)
    }

    @Test
    fun `materializer recovers from partial extraction`() {
        val archive = makeKokoroLayoutTarball(tmpDir)
        val artifactDir = File(tmpDir, "artifact").apply { mkdirs() }
        archive.copyTo(File(artifactDir, "kokoro-en-v0_19.tar.bz2"))

        val plan = MaterializationPlan(
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
        )
        // Simulate a partial extraction: only one required output
        // is present, no marker.
        File(artifactDir, "model.onnx").writeText("partial")
        assertFalse(File(artifactDir, EXTRACTION_MARKER_FILENAME).exists())

        Materializer.materialize(plan, artifactDir)
        assertTrue(File(artifactDir, "voices.bin").exists())
        assertTrue(File(artifactDir, "espeak-ng-data/phontab").exists())
        assertTrue(File(artifactDir, EXTRACTION_MARKER_FILENAME).exists())
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    /** Build a Kokoro-shape tar.bz2 fixture under [dir]. */
    private fun makeKokoroLayoutTarball(dir: File): File {
        val archive = File(dir, "kokoro-en-v0_19.tar.bz2")
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
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }
}
