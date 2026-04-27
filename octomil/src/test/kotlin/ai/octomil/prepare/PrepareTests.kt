package ai.octomil.prepare

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FilesystemKeyTests {
    @Test
    fun `preserves valid ASCII identifiers`() {
        val key = safeFilesystemKey("kokoro-en-v0_19")
        assertTrue(key.startsWith("kokoro-en-v0_19-"))
        assertEquals("kokoro-en-v0_19".length + 1 + 12, key.length)
    }

    @Test
    fun `is deterministic`() {
        assertEquals(safeFilesystemKey("kokoro-82m"), safeFilesystemKey("kokoro-82m"))
    }

    @Test
    fun `disambiguates inputs that sanitize alike`() {
        val a = safeFilesystemKey("model/v1")
        val b = safeFilesystemKey("model\\v1")
        assertFalse(a == b)
    }

    @Test
    fun `replaces non-ASCII with underscore`() {
        val key = safeFilesystemKey("modèle-français-🎵")
        assertTrue(key.all { it.code < 128 })
    }

    @Test
    fun `collapses empty and dot-only inputs`() {
        assertTrue(safeFilesystemKey("").startsWith("id-"))
        assertTrue(safeFilesystemKey(".").startsWith("id-"))
        assertTrue(safeFilesystemKey("..").startsWith("id-"))
    }

    @Test
    fun `caps visible portion`() {
        val key = safeFilesystemKey("a".repeat(500))
        assertTrue(key.length <= DEFAULT_MAX_VISIBLE_CHARS + 13)
    }

    @Test
    fun `cross-SDK conformance for kokoro-82m`() {
        // Python and Node and Swift all derive
        // "kokoro-82m-64e5b12f9efb"; Android must too so artifact dirs
        // and lock files line up across SDKs sharing a cache root.
        assertEquals("kokoro-82m-64e5b12f9efb", safeFilesystemKey("kokoro-82m"))
    }

    @Test(expected = FilesystemKeyException::class)
    fun `rejects NUL bytes`() {
        safeFilesystemKey("foo\u0000bar")
    }
}

class PrepareManagerTests {
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("octomil-pm-").toFile()
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `canPrepare returns true for well-formed candidate`() {
        val pm = PrepareManager(cacheDir = tmpDir)
        assertTrue(pm.canPrepare(makeCandidate()))
    }

    @Test
    fun `canPrepare returns false for synthetic candidate`() {
        val pm = PrepareManager(cacheDir = tmpDir)
        assertFalse(pm.canPrepare(makeCandidate(downloadUrls = emptyList())))
    }

    @Test
    fun `artifactDirFor matches Python and other SDKs`() {
        val pm = PrepareManager(cacheDir = tmpDir)
        val dir = pm.artifactDirFor("kokoro-82m")
        assertEquals("kokoro-82m-64e5b12f9efb", dir.name)
    }

    @Test(expected = PrepareException::class)
    fun `artifactDirFor rejects empty id`() {
        PrepareManager(cacheDir = tmpDir).artifactDirFor("")
    }

    @Test
    fun `validates locality and delivery mode`() {
        val pm = PrepareManager(cacheDir = tmpDir)
        assertFalse(pm.canPrepare(makeCandidate(locality = "cloud")))
        assertFalse(pm.canPrepare(makeCandidate(deliveryMode = "hosted_gateway")))
    }

    @Test
    fun `disabled policy rejected`() {
        val pm = PrepareManager(cacheDir = tmpDir)
        assertFalse(pm.canPrepare(makeCandidate(preparePolicy = PreparePolicy.DISABLED)))
    }

    @Test
    fun `static recipe registry has kokoro`() {
        assertNotNull(StaticRecipeRegistry.recipe("kokoro-82m"))
        assertNotNull(StaticRecipeRegistry.recipe("kokoro-en-v0_19"))
        assertNull(StaticRecipeRegistry.recipe("nonexistent-app"))
    }

    @Test
    fun `static recipe kokoro digest matches Python`() {
        val recipe = StaticRecipeRegistry.recipe("kokoro-82m")
        assertEquals(
            "sha256:912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7",
            recipe?.file?.digest,
        )
    }

    private fun makeCandidate(
        locality: String = "local",
        deliveryMode: String = "sdk_runtime",
        preparePolicy: PreparePolicy = PreparePolicy.LAZY,
        prepareRequired: Boolean = true,
        downloadUrls: List<DownloadEndpoint>? = null,
    ): PrepareCandidate {
        val urls = downloadUrls ?: listOf(DownloadEndpoint("https://cdn.example.com/"))
        return PrepareCandidate(
            locality = locality,
            engine = "sherpa-onnx",
            artifact = PrepareArtifactPlan(
                modelId = "kokoro-82m",
                artifactId = "kokoro-82m",
                digest = "sha256:" + "0".repeat(64),
                downloadUrls = urls,
            ),
            deliveryMode = deliveryMode,
            prepareRequired = prepareRequired,
            preparePolicy = preparePolicy,
        )
    }
}

class DurableDownloaderHelpersTests {
    @Test
    fun `validateRelativePath accepts simple POSIX paths`() {
        assertEquals("model.onnx", DurableDownloader.validateRelativePath("model.onnx"))
        assertEquals("subdir/model.onnx", DurableDownloader.validateRelativePath("subdir/model.onnx"))
    }

    @Test
    fun `validateRelativePath empty string returns empty`() {
        assertEquals("", DurableDownloader.validateRelativePath(""))
    }

    @Test
    fun `validateRelativePath rejects backslashes`() {
        try {
            DurableDownloader.validateRelativePath("model\\v1")
            fail("expected InvalidPathException")
        } catch (e: InvalidPathException) {
            assertTrue(e.reason.contains("backslashes"))
        }
    }

    @Test(expected = InvalidPathException::class)
    fun `validateRelativePath rejects traversal`() {
        DurableDownloader.validateRelativePath("../etc/passwd")
    }

    @Test(expected = InvalidPathException::class)
    fun `validateRelativePath rejects absolute paths`() {
        DurableDownloader.validateRelativePath("/etc/passwd")
    }

    @Test(expected = InvalidPathException::class)
    fun `validateRelativePath rejects Windows drive letters`() {
        DurableDownloader.validateRelativePath("C:foo")
    }

    @Test
    fun `safeJoin keeps path under destDir`() {
        val tmp = Files.createTempDirectory("octomil-safejoin-").toFile()
        try {
            val joined = DurableDownloader.safeJoin(tmp, "model.onnx")
            assertEquals(File(tmp, "model.onnx").canonicalPath, joined.canonicalPath)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test(expected = InvalidPathException::class)
    fun `safeJoin rejects traversal`() {
        val tmp = Files.createTempDirectory("octomil-safejoin-").toFile()
        try {
            DurableDownloader.safeJoin(tmp, "../escape.txt")
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `digestMatches verifies SHA-256`() {
        val tmp = Files.createTempFile("octomil-digest-", ".bin").toFile()
        try {
            tmp.writeBytes("hello world".toByteArray())
            // sha256("hello world") = b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
            assertTrue(
                DurableDownloader.digestMatches(
                    tmp,
                    "sha256:b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
                )
            )
            assertFalse(DurableDownloader.digestMatches(tmp, "sha256:" + "0".repeat(64)))
        } finally {
            tmp.delete()
        }
    }
}
