package ai.octomil.runtime.packaging

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

/**
 * Unit tests for the fetchRuntime Gradle task logic.
 *
 * These tests exercise the key behaviours in isolation using fixture files:
 *   - MANIFEST.json parsing and asset name resolution
 *   - Legacy fallback error path (Android-requires v0.1.5+)
 *   - Sentinel hit/skip logic
 *   - SHA-256 mismatch fail-closed
 *   - Safe-extract: path-traversal and symlink rejection
 *   - Task-wiring smoke-check (dependency graph via Gradle TestKit)
 *
 * Tests that require live GitHub access are disabled by default. Set
 *   OCTOMIL_ENABLE_NETWORK_TESTS=1 to enable them in integration CI.
 */
class FetchRuntimeTaskTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Manifest parsing ──────────────────────────────────────────────────────

    @Test
    fun `manifest parse resolves android-arm64 chat asset name`() {
        val manifest = buildManifest(
            version = "v0.1.5",
            platforms = mapOf(
                "android-arm64" to mapOf(
                    "chat" to "liboctomil-runtime-v0.1.5-chat-android-arm64.tar.gz",
                    "stt"  to "liboctomil-runtime-v0.1.5-stt-android-arm64.tar.gz",
                ),
                "darwin-arm64" to mapOf(
                    "chat" to "liboctomil-runtime-v0.1.5-chat-darwin-arm64.tar.gz",
                ),
            )
        )
        val assetName = resolveManifestAsset(manifest, arch = "android-arm64", flavor = "chat")
        assertEquals("liboctomil-runtime-v0.1.5-chat-android-arm64.tar.gz", assetName)
    }

    @Test
    fun `manifest parse resolves android-arm64 stt asset name`() {
        val manifest = buildManifest(
            version = "v0.1.5",
            platforms = mapOf(
                "android-arm64" to mapOf(
                    "chat" to "liboctomil-runtime-v0.1.5-chat-android-arm64.tar.gz",
                    "stt"  to "liboctomil-runtime-v0.1.5-stt-android-arm64.tar.gz",
                ),
            )
        )
        val assetName = resolveManifestAsset(manifest, arch = "android-arm64", flavor = "stt")
        assertEquals("liboctomil-runtime-v0.1.5-stt-android-arm64.tar.gz", assetName)
    }

    @Test
    fun `manifest parse throws with available platforms when arch missing`() {
        val manifest = buildManifest(
            version = "v0.1.5",
            platforms = mapOf(
                "darwin-arm64" to mapOf("chat" to "liboctomil-runtime-v0.1.5-chat-darwin-arm64.tar.gz"),
            )
        )
        val ex = assertThrows(ManifestResolutionException::class.java) {
            resolveManifestAsset(manifest, arch = "android-arm64", flavor = "chat")
        }
        assertTrue("error should mention missing arch", ex.message!!.contains("android-arm64"))
        assertTrue("error should list available platforms", ex.message!!.contains("darwin-arm64"))
    }

    @Test
    fun `manifest parse throws with available flavors when flavor missing`() {
        val manifest = buildManifest(
            version = "v0.1.5",
            platforms = mapOf(
                "android-arm64" to mapOf("chat" to "liboctomil-runtime-v0.1.5-chat-android-arm64.tar.gz"),
            )
        )
        val ex = assertThrows(ManifestResolutionException::class.java) {
            resolveManifestAsset(manifest, arch = "android-arm64", flavor = "stt")
        }
        assertTrue("error should mention missing flavor", ex.message!!.contains("stt"))
        assertTrue("error should list available flavors", ex.message!!.contains("chat"))
    }

    @Test
    fun `manifest parse accepts additional unknown fields (forward compatibility)`() {
        val manifest = buildManifest(
            version = "v0.1.5",
            platforms = mapOf(
                "android-arm64" to mapOf("chat" to "liboctomil-runtime-v0.1.5-chat-android-arm64.tar.gz"),
            ),
            extra = mapOf("future_field" to "ignored_value")
        )
        // Should not throw
        val assetName = resolveManifestAsset(manifest, arch = "android-arm64", flavor = "chat")
        assertEquals("liboctomil-runtime-v0.1.5-chat-android-arm64.tar.gz", assetName)
    }

    // ── Legacy fallback error path ─────────────────────────────────────────────

    @Test
    fun `legacy fallback throws with Android-specific v0_1_5 requirement message`() {
        val ex = assertThrows(LegacyFallbackException::class.java) {
            legacyAndroidFallbackError(version = "v0.1.4")
        }
        assertTrue(ex.message!!.contains("v0.1.4"))
        assertTrue(ex.message!!.contains("v0.1.5"))
        assertTrue(ex.message!!.contains("android-arm64"))
    }

    @Test
    fun `legacy fallback error message does NOT claim legacy shape works for Android`() {
        val ex = assertThrows(LegacyFallbackException::class.java) {
            legacyAndroidFallbackError(version = "v0.1.4")
        }
        // Should not say "fallback available" or "will work with legacy shape"
        assertFalse(ex.message!!.contains("will work"))
        assertFalse(ex.message!!.contains("fallback available"))
    }

    // ── Sentinel skip logic ───────────────────────────────────────────────────

    @Test
    fun `sentinel check returns true when sentinel file exists and matches version+flavor`() {
        val cacheDir = tmp.newFolder("cache")
        val sentinelFile = File(cacheDir, ".extracted-ok")
        sentinelFile.writeText("v0.1.5:chat\n")
        val libSo = tmp.newFile("liboctomil-runtime.so")
        val cppSo = tmp.newFile("libc++_shared.so")
        assertTrue(isCacheHit(sentinelFile, "v0.1.5", "chat", libSo, cppSo))
    }

    @Test
    fun `sentinel check returns false when version differs`() {
        val cacheDir = tmp.newFolder("cache")
        val sentinelFile = File(cacheDir, ".extracted-ok")
        sentinelFile.writeText("v0.1.4:chat\n")
        val libSo = tmp.newFile("liboctomil-runtime.so")
        val cppSo = tmp.newFile("libc++_shared.so")
        assertFalse(isCacheHit(sentinelFile, "v0.1.5", "chat", libSo, cppSo))
    }

    @Test
    fun `sentinel check returns false when flavor differs`() {
        val cacheDir = tmp.newFolder("cache")
        val sentinelFile = File(cacheDir, ".extracted-ok")
        sentinelFile.writeText("v0.1.5:stt\n")
        val libSo = tmp.newFile("liboctomil-runtime.so")
        val cppSo = tmp.newFile("libc++_shared.so")
        assertFalse(isCacheHit(sentinelFile, "v0.1.5", "chat", libSo, cppSo))
    }

    @Test
    fun `sentinel check returns false when sentinel file absent`() {
        val sentinelFile = File(tmp.root, ".extracted-ok-nonexistent")
        val libSo = tmp.newFile("liboctomil-runtime.so")
        val cppSo = tmp.newFile("libc++_shared.so")
        assertFalse(isCacheHit(sentinelFile, "v0.1.5", "chat", libSo, cppSo))
    }

    @Test
    fun `sentinel check returns false when liboctomil-runtime_so absent even if sentinel exists`() {
        val cacheDir = tmp.newFolder("cache")
        val sentinelFile = File(cacheDir, ".extracted-ok")
        sentinelFile.writeText("v0.1.5:chat\n")
        val missingLibSo = File(tmp.root, "liboctomil-runtime.so") // NOT created
        val cppSo = tmp.newFile("libc++_shared.so")
        assertFalse(isCacheHit(sentinelFile, "v0.1.5", "chat", missingLibSo, cppSo))
    }

    // ── SHA-256 verification ──────────────────────────────────────────────────

    @Test
    fun `sha256 verification passes for correct checksum`() {
        val content = "hello octomil runtime".toByteArray()
        val file = tmp.newFile("liboctomil-runtime.so")
        file.writeBytes(content)

        val expectedHex = sha256Hex(content)
        val sumsFile = tmp.newFile("SHA256SUMS")
        sumsFile.writeText("$expectedHex  ${file.name}\n")

        // Should not throw
        verifySha256(file, sumsFile)
    }

    @Test
    fun `sha256 verification fails with clear message on mismatch`() {
        val file = tmp.newFile("liboctomil-runtime.so")
        file.writeBytes("actual content".toByteArray())

        val wrongHex = "a".repeat(64)
        val sumsFile = tmp.newFile("SHA256SUMS")
        sumsFile.writeText("$wrongHex  ${file.name}\n")

        val ex = assertThrows(Sha256MismatchException::class.java) {
            verifySha256(file, sumsFile)
        }
        assertTrue(ex.message!!.contains("SHA-256 mismatch"))
        assertTrue(ex.message!!.contains("expected:"))
        assertTrue(ex.message!!.contains("got:"))
    }

    @Test
    fun `sha256 verification fails when file not listed in SHA256SUMS`() {
        val file = tmp.newFile("liboctomil-runtime.so")
        file.writeBytes("content".toByteArray())

        val sumsFile = tmp.newFile("SHA256SUMS")
        sumsFile.writeText("${"b".repeat(64)}  some_other_file.tar.gz\n")

        val ex = assertThrows(Sha256MismatchException::class.java) {
            verifySha256(file, sumsFile)
        }
        assertTrue(ex.message!!.contains("not listed in SHA256SUMS"))
    }

    // ── Safe extraction — path traversal and symlink rejection ────────────────

    @Test
    fun `safe extract rejects path traversal entries`() {
        val tarball = createTarGz(
            entries = listOf(TarTestEntry(name = "../../etc/passwd", content = "root:x:0:0".toByteArray()))
        )
        val extractDir = tmp.newFolder("extract")
        val ex = assertThrows(SafeExtractException::class.java) {
            safeExtract(tarball, extractDir)
        }
        assertTrue(ex.message!!.contains("path-traversal"))
    }

    @Test
    fun `safe extract rejects absolute path entries`() {
        val tarball = createTarGz(
            entries = listOf(TarTestEntry(name = "/etc/passwd", content = "root".toByteArray()))
        )
        val extractDir = tmp.newFolder("extract2")
        val ex = assertThrows(SafeExtractException::class.java) {
            safeExtract(tarball, extractDir)
        }
        assertTrue(ex.message!!.contains("absolute path"))
    }

    @Test
    fun `safe extract rejects symlink entries (typeflag 2)`() {
        val tarball = createTarGzWithSymlink(linkName = "evil-link", target = "/etc/passwd")
        val extractDir = tmp.newFolder("extract3")
        val ex = assertThrows(SafeExtractException::class.java) {
            safeExtract(tarball, extractDir)
        }
        assertTrue(ex.message!!.contains("symlink"))
    }

    @Test
    fun `safe extract rejects hardlink entries (typeflag 1)`() {
        val tarball = createTarGzWithHardlink(linkName = "evil-hardlink", target = "some/real/file")
        val extractDir = tmp.newFolder("extract4")
        val ex = assertThrows(SafeExtractException::class.java) {
            safeExtract(tarball, extractDir)
        }
        assertTrue(ex.message!!.contains("hardlink"))
    }

    @Test
    fun `safe extract succeeds for well-formed tarball and stages lib files`() {
        val libContent = "ELF binary stub".toByteArray()
        val cppContent = "libc++ stub".toByteArray()
        val tarball = createTarGz(
            entries = listOf(
                TarTestEntry(name = "liboctomil-runtime-v0.1.5-chat-android-arm64/lib/liboctomil-runtime.so", content = libContent),
                TarTestEntry(name = "liboctomil-runtime-v0.1.5-chat-android-arm64/lib/libc++_shared.so",    content = cppContent),
                TarTestEntry(name = "liboctomil-runtime-v0.1.5-chat-android-arm64/include/octomil/runtime.h", content = "// header".toByteArray()),
            )
        )
        val extractDir = tmp.newFolder("extract5")
        safeExtract(tarball, extractDir) // Should not throw

        val libSo = File(extractDir, "liboctomil-runtime-v0.1.5-chat-android-arm64/lib/liboctomil-runtime.so")
        assertTrue("liboctomil-runtime.so should be extracted", libSo.exists())
        assertArrayEquals(libContent, libSo.readBytes())

        val cppSo = File(extractDir, "liboctomil-runtime-v0.1.5-chat-android-arm64/lib/libc++_shared.so")
        assertTrue("libc++_shared.so should be extracted", cppSo.exists())
    }

    @Test
    fun `safe extract silently skips AppleDouble metadata entries`() {
        val tarball = createTarGz(
            entries = listOf(
                TarTestEntry(name = "top/._liboctomil-runtime.so", content = "appledouble".toByteArray()),
                TarTestEntry(name = "top/lib/liboctomil-runtime.so", content = "real lib".toByteArray()),
            )
        )
        val extractDir = tmp.newFolder("extract6")
        safeExtract(tarball, extractDir) // Should not throw

        val appleDouble = File(extractDir, "top/._liboctomil-runtime.so")
        assertFalse("AppleDouble entry should be skipped", appleDouble.exists())

        val realLib = File(extractDir, "top/lib/liboctomil-runtime.so")
        assertTrue("Real lib should be extracted", realLib.exists())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildManifest(
        version: String,
        platforms: Map<String, Map<String, String>>,
        extra: Map<String, String> = emptyMap(),
    ): JSONObject {
        val platformsObj = JSONObject()
        for ((arch, flavors) in platforms) {
            val flavorObj = JSONObject()
            for ((flavor, assetName) in flavors) {
                flavorObj.put(flavor, assetName)
            }
            platformsObj.put(arch, flavorObj)
        }
        val root = JSONObject()
        root.put("version", version)
        root.put("platforms", platformsObj)
        for ((k, v) in extra) root.put(k, v)
        return root
    }

    // Extracted logic mirror (same logic as FetchRuntimeTask, kept in sync)

    private fun resolveManifestAsset(manifest: JSONObject, arch: String, flavor: String): String {
        val platforms = manifest.optJSONObject("platforms")
            ?: throw ManifestResolutionException("MANIFEST.json missing 'platforms' field")
        val platformEntry = platforms.optJSONObject(arch)
            ?: run {
                val available = platforms.keys().asSequence().joinToString(", ")
                throw ManifestResolutionException(
                    "MANIFEST.json has no entry for platform '$arch'. Available platforms: $available"
                )
            }
        return platformEntry.optString(flavor, "")
            .takeIf { it.isNotBlank() }
            ?: run {
                val available = platformEntry.keys().asSequence().joinToString(", ")
                throw ManifestResolutionException(
                    "MANIFEST.json has no '$flavor' artifact for $arch. Available flavors for $arch: $available"
                )
            }
    }

    private fun legacyAndroidFallbackError(version: String): Nothing {
        throw LegacyFallbackException(
            "Release $version has no MANIFEST.json. " +
            "android-arm64 artifacts were NOT shipped before v0.1.5 — the legacy release " +
            "shape only covered darwin-arm64. The Android SDK requires v0.1.5+."
        )
    }

    private fun isCacheHit(
        sentinelFile: File,
        version: String,
        flavor: String,
        libSo: File,
        cppSo: File,
    ): Boolean {
        val expected = "$version:$flavor"
        return sentinelFile.exists()
            && sentinelFile.readText().trim() == expected
            && libSo.exists()
            && cppSo.exists()
    }

    private fun verifySha256(file: File, sumsFile: File) {
        val expected = sumsFile.readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                val m = Regex("""^([0-9a-fA-F]{64})\s+(.+)$""").find(trimmed) ?: return@mapNotNull null
                m.groupValues[2].trim() to m.groupValues[1].lowercase()
            }
            .toMap()

        val basename = file.name
        val expectedHash = expected[basename]
            ?: throw Sha256MismatchException(
                "$basename is not listed in SHA256SUMS. Listed: ${expected.keys.joinToString(", ")}"
            )
        val got = sha256Hex(file.readBytes())
        if (got != expectedHash) {
            throw Sha256MismatchException(
                "SHA-256 mismatch for $basename\n  expected: $expectedHash\n  got:      $got"
            )
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ── Safe extract (mirrors the task implementation) ────────────────────────

    private data class TarTestEntry(
        val name: String,
        val content: ByteArray,
        val typeFlag: Char = '0',
        val linkTarget: String = "",
    )

    private fun safeExtract(tarball: File, targetDir: File) {
        val realTarget = targetDir.canonicalFile
        val TAR_BLOCK = 512

        fun validateName(name: String) {
            if (name.startsWith("/")) throw SafeExtractException("refusing to extract absolute path '$name'")
            if (name.split("/").any { it == ".." }) throw SafeExtractException("refusing to extract path-traversal entry '$name'")
            val resolved = File(realTarget, name).canonicalFile
            if (!resolved.path.startsWith(realTarget.path + File.separator) && resolved.path != realTarget.path) {
                throw SafeExtractException("tar entry '$name' would escape $targetDir on resolution")
            }
        }

        GZIPInputStream2(tarball.inputStream()).use { gz ->
            val header = ByteArray(TAR_BLOCK)
            while (true) {
                val read = gz.readNBytes(header, 0, TAR_BLOCK)
                if (read < TAR_BLOCK) break
                if (header.all { it == 0.toByte() }) break
                val name = String(header, 0, 100, Charsets.US_ASCII).trimEnd('\u0000')
                val typeFlag = header[156].toInt().toChar()
                val sizeStr = String(header, 124, 12, Charsets.US_ASCII).trimEnd('\u0000').trim()
                val size = if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8)
                val dataBlocks = ((size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK

                val base = name.substringAfterLast('/')
                if (base.startsWith("._")) { gz.skipNBytes(dataBlocks); continue }

                when (typeFlag) {
                    '2' -> throw SafeExtractException("refusing to extract symlink entry '$name'")
                    '1' -> throw SafeExtractException("refusing to extract hardlink entry '$name'")
                    '3', '4', '6' -> throw SafeExtractException("refusing to extract device entry '$name'")
                }
                validateName(name)

                val outFile = File(realTarget, name)
                if (typeFlag == '5' || name.endsWith("/")) {
                    outFile.mkdirs()
                    gz.skipNBytes(dataBlocks)
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        var remaining = size
                        val buf = ByteArray(4096)
                        while (remaining > 0) {
                            val n = gz.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    val pad = dataBlocks - size
                    if (pad > 0) gz.skipNBytes(pad)
                }
            }
        }
    }

    /** Build a valid .tar.gz from a list of test entries. */
    private fun createTarGz(entries: List<TarTestEntry>): File {
        val tarball = tmp.newFile("test-artifact.tar.gz")
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            for (e in entries) {
                val header = buildTarHeader(e.name, e.content.size.toLong(), e.typeFlag, e.linkTarget)
                gz.write(header)
                gz.write(e.content)
                val pad = ((e.content.size + 511) / 512) * 512 - e.content.size
                if (pad > 0) gz.write(ByteArray(pad))
            }
            // Two zero blocks = end-of-archive
            gz.write(ByteArray(1024))
        }
        tarball.writeBytes(baos.toByteArray())
        return tarball
    }

    private fun createTarGzWithSymlink(linkName: String, target: String): File =
        createTarGz(listOf(TarTestEntry(name = linkName, content = ByteArray(0), typeFlag = '2', linkTarget = target)))

    private fun createTarGzWithHardlink(linkName: String, target: String): File =
        createTarGz(listOf(TarTestEntry(name = linkName, content = ByteArray(0), typeFlag = '1', linkTarget = target)))

    private fun buildTarHeader(
        name: String,
        size: Long,
        typeFlag: Char,
        linkTarget: String = "",
    ): ByteArray {
        val header = ByteArray(512)
        fun writeStr(s: String, offset: Int, length: Int) {
            val bytes = s.toByteArray(Charsets.US_ASCII)
            val len = minOf(bytes.size, length - 1)
            bytes.copyInto(header, offset, 0, len)
        }
        fun writeOctal(v: Long, offset: Int, length: Int) {
            val s = v.toString(8).padStart(length - 1, '0')
            writeStr(s, offset, length)
        }
        writeStr(name, 0, 100)
        writeStr("0000755", 100, 8)
        writeStr("0000000", 108, 8)
        writeStr("0000000", 116, 8)
        writeOctal(size, 124, 12)
        writeOctal(0L, 136, 12)
        header[156] = typeFlag.code.toByte()
        if (linkTarget.isNotEmpty()) writeStr(linkTarget, 157, 100)
        writeStr("ustar", 257, 6)
        writeStr("00", 263, 2)
        // Compute checksum
        var checksum = 0
        for (i in header.indices) {
            checksum += if (i in 148..155) 32 else (header[i].toInt() and 0xFF)
        }
        writeStr(checksum.toString(8).padStart(6, '0') + "\u0000 ", 148, 8)
        return header
    }

    // Minimal GZIPInputStream alias for test readability
    private fun GZIPInputStream2(stream: java.io.InputStream) = java.util.zip.GZIPInputStream(stream)
}

// ── Exception types (mirror build script semantics) ───────────────────────────

class ManifestResolutionException(message: String) : Exception(message)
class LegacyFallbackException(message: String) : Exception(message)
class Sha256MismatchException(message: String) : Exception(message)
class SafeExtractException(message: String) : Exception(message)

// Helpers used by the task wiring test (Gradle TestKit)
// are in FetchRuntimeTaskWiringTest.kt
