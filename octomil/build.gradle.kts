buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.json:json:20260522")
    }
}

import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.GradleException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.GZIPInputStream
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
// FetchRuntimeTask — download and stage liboctomil-runtime.so into jniLibs
//
// Downloads the versioned android-arm64 runtime tarball from the private
// octomil/octomil-runtime GitHub release. Requires v0.1.5+ (first release
// that ships android-arm64 artifacts under the canonical asset name shape).
//
// Cache: <rootProject>/.gradle/octomil-runtime/<version>/<flavor>/
// Sentinel: <cacheDir>/.extracted-ok  (contains "<version>:<flavor>")
// Output:  octomil/src/main/jniLibs/<abi>/liboctomil-runtime.so
//          octomil/src/main/jniLibs/<abi>/libc++_shared.so
// ─────────────────────────────────────────────────────────────────────────────
abstract class FetchRuntimeTask : DefaultTask() {

    @get:Input abstract val runtimeVersion: Property<String>
    @get:Input abstract val runtimeFlavor: Property<String>
    @get:Input abstract val runtimeAbi: Property<String>
    @get:Input abstract val skipFetch: Property<Boolean>

    // Pre-resolved at registration time so @TaskAction never reads `project`.
    // Configuration cache forbids any Project instance access at execution time.
    @get:Internal abstract val cacheRootDir: DirectoryProperty
    @get:OutputDirectory abstract val jniLibsRootDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val version = runtimeVersion.get()
        val flavor = runtimeFlavor.get()
        val abi = runtimeAbi.get()
        val skip = skipFetch.get()

        if (skip) {
            logger.lifecycle("fetchRuntime: skipFetch=true — skipping GitHub download")
            return
        }

        val cacheDir = File(cacheRootDir.get().asFile, "$version/$flavor")
        val jniLibsDir = File(jniLibsRootDir.get().asFile, abi)
        val sentinelFile = File(cacheDir, ".extracted-ok")
        val sentinelContent = "$version:$flavor"

        // ── sentinel check ────────────────────────────────────────────────────
        val libSo = File(jniLibsDir, "liboctomil-runtime.so")
        val stdcppSo = File(jniLibsDir, "libc++_shared.so")
        if (sentinelFile.exists() && sentinelFile.readText().trim() == sentinelContent
            && libSo.exists() && stdcppSo.exists()) {
            logger.lifecycle("fetchRuntime: cache hit — $version/$flavor already staged")
            return
        }
        if (sentinelFile.exists() && sentinelFile.readText().trim() != sentinelContent) {
            logger.lifecycle("fetchRuntime: sentinel version mismatch — re-fetching")
        }

        // ── token resolution: GH_TOKEN → GITHUB_TOKEN → OCTOMIL_RUNTIME_TOKEN → gh auth token ──
        val token = resolveGhToken()
            ?: throw GradleException(
                "fetchRuntime: no GitHub token available.\n" +
                "Set GH_TOKEN or GITHUB_TOKEN env var, or run `gh auth login`.\n" +
                "A token with read access to the private octomil/octomil-runtime repo is required."
            )

        logger.lifecycle("fetchRuntime: fetching octomil-runtime $version ($flavor/$abi)")

        // ── list release assets ───────────────────────────────────────────────
        val releaseUrl = "https://api.github.com/repos/octomil/octomil-runtime/releases/tags/$version"
        val releaseJson = githubGet(releaseUrl, token)
        val assets = parseAssetsMap(releaseJson)

        if (assets.isEmpty()) {
            val message = releaseJson.optString("message", "")
            when {
                message.contains("Bad credentials", ignoreCase = true) ||
                message.contains("Requires authentication", ignoreCase = true) ->
                    throw GradleException(
                        "fetchRuntime: HTTP 401 — bad or missing GitHub token.\n" +
                        "Set GH_TOKEN or GITHUB_TOKEN env var, or run `gh auth login`.\n" +
                        "The token must have read access to the private octomil/octomil-runtime repo."
                    )
                message.contains("Not Found", ignoreCase = true) ->
                    throw GradleException(
                        "fetchRuntime: Runtime version $version not yet released.\n" +
                        "The Android SDK requires v0.1.5+ which ships the first android-arm64 artifact.\n" +
                        "Pin `octomilRuntime.version` to v0.1.5+ in octomil-runtime.properties once the runtime release lands."
                    )
                else ->
                    throw GradleException(
                        "fetchRuntime: GitHub API returned no assets for release $version.\n" +
                        "API message: $message\n" +
                        "Confirm the tag exists and your token has read access to octomil/octomil-runtime."
                    )
            }
        }

        // ── MANIFEST.json ─────────────────────────────────────────────────────
        cacheDir.mkdirs()
        val downloadDir = File(cacheDir, "_download").also { it.mkdirs() }

        val assetName: String
        if ("MANIFEST.json" in assets) {
            val manifestFile = File(downloadDir, "MANIFEST.json")
            downloadAsset(assets["MANIFEST.json"]!!, manifestFile, token)
            val manifest = JSONObject(manifestFile.readText())
            val platforms = manifest.optJSONObject("platforms")
                ?: throw GradleException("fetchRuntime: MANIFEST.json is missing 'platforms' field")
            val manifestArch = abiToManifestArch(abi)
            val platformEntry = platforms.optJSONObject(manifestArch)
                ?: run {
                    val available = platforms.keys().asSequence().joinToString(", ")
                    throw GradleException(
                        "fetchRuntime: Manifest exists but 'platforms.$manifestArch' is missing.\n" +
                        "Check the runtime release. Available platforms: $available"
                    )
                }
            assetName = platformEntry.optString(flavor, "")
                .takeIf { it.isNotBlank() }
                ?: run {
                    val available = platformEntry.keys().asSequence().joinToString(", ")
                    throw GradleException(
                        "fetchRuntime: Manifest exists but `platforms.$manifestArch.$flavor` is missing.\n" +
                        "Check the runtime release. Available flavors for $manifestArch: $available"
                    )
                }
            logger.lifecycle("fetchRuntime: manifest resolved $manifestArch/$flavor -> $assetName")
        } else {
            // No MANIFEST.json — this is a v0.1.4-era release. Android was NOT in v0.1.4.
            throw GradleException(
                "fetchRuntime: Release $version has no MANIFEST.json.\n" +
                "Android arm64 artifacts were NOT shipped in v0.1.4 or earlier — the legacy release\n" +
                "shape only covered darwin-arm64. The Android SDK requires v0.1.5+.\n" +
                "Update `octomilRuntime.version` in octomil-runtime.properties to v0.1.5 or later."
            )
        }

        // ── download tarball + SHA256SUMS ─────────────────────────────────────
        for (name in listOf(assetName, "SHA256SUMS")) {
            if (name !in assets) {
                throw GradleException("fetchRuntime: release $version is missing asset '$name'")
            }
        }
        val tarballFile = File(downloadDir, assetName)
        val sumsFile = File(downloadDir, "SHA256SUMS")

        downloadAsset(assets[assetName]!!, tarballFile, token)
        downloadAsset(assets["SHA256SUMS"]!!, sumsFile, token)

        // ── SHA-256 verification ──────────────────────────────────────────────
        verifySha256(tarballFile, sumsFile)

        // ── safe extraction ───────────────────────────────────────────────────
        val extractDir = File(cacheDir, "extracted").also { it.deleteRecursively(); it.mkdirs() }
        safeExtract(tarballFile, extractDir)

        // ── locate lib/ in extraction ─────────────────────────────────────────
        // Tarball layout: liboctomil-runtime-<ver>-<flavor>-android-arm64/lib/{*.so,...}
        val topDir = extractDir.listFiles()?.singleOrNull { it.isDirectory }
            ?: throw GradleException("fetchRuntime: unexpected tarball layout — expected single top-level directory in $extractDir")
        val libDir = File(topDir, "lib")
        if (!libDir.isDirectory) {
            throw GradleException("fetchRuntime: tarball missing lib/ directory (found: ${topDir.listFiles()?.map { it.name }})")
        }

        // ── stage .so files into jniLibs ──────────────────────────────────────
        jniLibsDir.mkdirs()
        for (soName in listOf("liboctomil-runtime.so", "libc++_shared.so")) {
            val src = File(libDir, soName)
            if (!src.exists()) {
                throw GradleException("fetchRuntime: expected $soName in tarball lib/ but not found (available: ${libDir.listFiles()?.map { it.name }})")
            }
            val dst = File(jniLibsDir, soName)
            src.copyTo(dst, overwrite = true)
            logger.lifecycle("fetchRuntime: staged $soName -> $dst")
        }

        // ── write sentinel ────────────────────────────────────────────────────
        sentinelFile.writeText(sentinelContent + "\n")

        // ── cleanup download dir ──────────────────────────────────────────────
        downloadDir.deleteRecursively()

        logger.lifecycle("fetchRuntime: runtime $version ($flavor) ready in $jniLibsDir")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun resolveGhToken(): String? {
        for (env in listOf("GH_TOKEN", "GITHUB_TOKEN", "OCTOMIL_RUNTIME_TOKEN")) {
            val v = System.getenv(env)
            if (!v.isNullOrBlank()) return v.trim()
        }
        // Fall back to `gh auth token`
        return try {
            val proc = ProcessBuilder("gh", "auth", "token")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val exitCode = proc.waitFor()
            if (exitCode == 0 && output.isNotBlank()) output else null
        } catch (_: Exception) {
            null
        }
    }

    private fun githubGet(urlStr: String, token: String): JSONObject {
        val conn = openGithubConnection(urlStr, token)
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            JSONObject().put("status", code).put("message", body.take(500))
        }
    }

    private fun openGithubConnection(urlStr: String, token: String): HttpURLConnection {
        var url = urlStr
        repeat(5) {
            val u = URL(url)
            val conn = u.openConnection() as HttpURLConnection
            if (shouldSendGithubAuth(u)) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "octomil-android/fetchRuntime")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.instanceFollowRedirects = false
            conn.connect()
            val code = conn.responseCode
            if (code in 301..302) {
                url = conn.getHeaderField("Location") ?: throw GradleException("fetchRuntime: redirect with no Location header")
                conn.disconnect()
                return@repeat
            }
            return conn
        }
        throw GradleException("fetchRuntime: too many redirects fetching $urlStr")
    }

    /**
     * Return true when it is safe to send the GitHub bearer token to this URL.
     *
     * GitHub release-asset downloads are served via a 302 redirect to a signed
     * S3-style URL on a host like `objects.githubusercontent.com`. The signed
     * URL already carries its own auth in the query string — re-sending our
     * GitHub token to that host both LEAKS the credential to a non-GitHub
     * origin AND can cause the redirected request to 400/403 due to the
     * dual-auth conflict.
     *
     * Only attach the bearer token when the request host is `api.github.com`
     * or any subdomain of `github.com`. On every redirect, this check is
     * re-evaluated against the NEW URL — so when GitHub redirects an asset
     * download off-domain we drop the Authorization header before re-sending.
     */
    private fun shouldSendGithubAuth(url: URL): Boolean {
        val host = url.host ?: return false
        return host == "api.github.com" || host == "github.com" || host.endsWith(".github.com")
    }

    private fun parseAssetsMap(releaseJson: JSONObject): Map<String, String> {
        val assets = mutableMapOf<String, String>()
        val arr = releaseJson.optJSONArray("assets") ?: return assets
        for (i in 0 until arr.length()) {
            val asset = arr.getJSONObject(i)
            val name = asset.optString("name", "")
            val url = asset.optString("url", "")
            if (name.isNotBlank() && url.isNotBlank()) {
                assets[name] = url
            }
        }
        return assets
    }

    private fun downloadAsset(apiUrl: String, dest: File, token: String) {
        logger.lifecycle("  download ${dest.name}")
        var url = apiUrl
        repeat(10) {
            val u = URL(url)
            val conn = u.openConnection() as HttpURLConnection
            // Only attach the GitHub bearer on github.com hosts. On asset
            // redirects to signed-URL hosts (objects.githubusercontent.com,
            // S3, ...) we must NOT re-send the token — it would leak the
            // credential to a non-GitHub origin and the signed URL has its
            // own auth in the query string.
            if (shouldSendGithubAuth(u)) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            conn.setRequestProperty("Accept", "application/octet-stream")
            conn.setRequestProperty("User-Agent", "octomil-android/fetchRuntime")
            conn.instanceFollowRedirects = false
            conn.connect()
            val code = conn.responseCode
            when {
                code in 301..302 -> {
                    url = conn.getHeaderField("Location")
                        ?: throw GradleException("fetchRuntime: download redirect with no Location for ${dest.name}")
                    conn.disconnect()
                    return@repeat
                }
                code == 401 -> throw GradleException(
                    "fetchRuntime: HTTP 401 downloading ${dest.name}.\n" +
                    "Set GH_TOKEN or run `gh auth login`."
                )
                code == 404 -> throw GradleException(
                    "fetchRuntime: HTTP 404 downloading ${dest.name}.\n" +
                    "Confirm the asset exists in the requested release."
                )
                code !in 200..299 -> {
                    val body = conn.errorStream?.bufferedReader()?.readText()?.take(500) ?: ""
                    throw GradleException("fetchRuntime: HTTP $code downloading ${dest.name}: $body")
                }
                else -> {
                    conn.inputStream.use { input ->
                        FileOutputStream(dest).use { output ->
                            input.copyTo(output)
                        }
                    }
                    conn.disconnect()
                    return
                }
            }
        }
        throw GradleException("fetchRuntime: too many redirects downloading ${dest.name}")
    }

    private fun verifySha256(tarball: File, sumsFile: File) {
        // SHA256SUMS path entries may be emitted with a "./" prefix (e.g. BSD
        // `shasum -a 256 ./*.tar.gz`) or as bare filenames. Normalize to bare
        // filenames so the lookup key matches `tarball.name` either way.
        val expected = sumsFile.readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) null
                else {
                    val m = Regex("""^([0-9a-fA-F]{64})\s+(.+)$""").find(trimmed)
                    m?.let {
                        val rawPath = it.groupValues[2].trim()
                        val normalized = rawPath.removePrefix("./")
                        normalized to it.groupValues[1].lowercase()
                    }
                }
            }
            .toMap()

        val basename = tarball.name
        val expectedHash = expected[basename]
            ?: throw GradleException(
                "fetchRuntime: $basename is not listed in SHA256SUMS.\n" +
                "Listed files: ${expected.keys.joinToString(", ")}"
            )

        val digest = MessageDigest.getInstance("SHA-256")
        tarball.inputStream().use { stream ->
            val buf = ByteArray(1 shl 20)
            var n = stream.read(buf)
            while (n != -1) {
                digest.update(buf, 0, n)
                n = stream.read(buf)
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (got != expectedHash) {
            throw GradleException(
                "fetchRuntime: SHA-256 mismatch for $basename\n" +
                "  expected: $expectedHash\n" +
                "  got:      $got\n" +
                "Refusing to extract a corrupt or tampered artifact."
            )
        }
        logger.lifecycle("  verified $basename")
    }

    /**
     * Read a POSIX/GNU tar + gzip archive using pure Java stdlib.
     * No external dependency — avoids buildscript classpath complications.
     *
     * TAR block size is 512 bytes. Each entry header occupies one block.
     * The header encodes: name (100), mode (8), uid/gid (16), size (12),
     * mtime (12), checksum (8), typeflag (1), linkname (100), magic (6/8),
     * ... (GNU/POSIX extensions for long names live in preceding entries).
     */
    private data class TarEntry(
        val name: String,
        val typeFlag: Char,
        val size: Long,
        val isLongName: Boolean = false,
    )

    private fun readTarEntry(header: ByteArray): TarEntry? {
        // All-zero block = end-of-archive
        if (header.all { it == 0.toByte() }) return null
        val name = readCString(header, 0, 100)
        val typeFlag = header[156].toInt().toChar()
        val sizeStr = readCString(header, 124, 12)
        val size = sizeStr.trimEnd('\u0000').trim().let { if (it.isEmpty()) 0L else it.toLong(8) }
        return TarEntry(name = name, typeFlag = typeFlag, size = size)
    }

    private fun readCString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.US_ASCII)
    }

    private fun safeExtract(tarball: File, targetDir: File) {
        val realTarget = targetDir.canonicalFile
        val TAR_BLOCK = 512

        // Collect all entries for validation pass, then extract.
        data class EntryRecord(val name: String, val typeFlag: Char, val size: Long, val dataOffset: Long)
        val records = mutableListOf<EntryRecord>()
        var pendingLongName: String? = null

        // ── validation + index pass ───────────────────────────────────────────
        GZIPInputStream(tarball.inputStream()).use { gz ->
            val header = ByteArray(TAR_BLOCK)
            var offset = 0L
            while (true) {
                val read = gz.readNBytes(header, 0, TAR_BLOCK)
                if (read < TAR_BLOCK) break
                offset += TAR_BLOCK

                val entry = readTarEntry(header) ?: break

                // GNU long-name extension: typeFlag 'L' = next entry's name is in data
                if (entry.typeFlag == 'L') {
                    val nameBytes = ByteArray(entry.size.toInt())
                    gz.readNBytes(nameBytes, 0, nameBytes.size)
                    pendingLongName = readCString(nameBytes, 0, nameBytes.size)
                    // Skip padding to next 512-byte boundary
                    val pad = ((entry.size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK - entry.size
                    if (pad > 0) gz.skipNBytes(pad)
                    offset += ((entry.size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK
                    continue
                }

                val name = pendingLongName ?: entry.name
                pendingLongName = null

                val base = name.substringAfterLast('/')

                // Skip AppleDouble
                if (base.startsWith("._")) {
                    val dataBlocks = ((entry.size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK
                    gz.skipNBytes(dataBlocks)
                    offset += dataBlocks
                    continue
                }

                // Reject symlinks (typeFlag '2') and hardlinks ('1')
                if (entry.typeFlag == '2') {
                    throw GradleException("fetchRuntime: refusing to extract symlink entry '$name'")
                }
                if (entry.typeFlag == '1') {
                    throw GradleException("fetchRuntime: refusing to extract hardlink entry '$name'")
                }
                // Reject character/block devices and FIFOs
                if (entry.typeFlag in listOf('3', '4', '6')) {
                    throw GradleException("fetchRuntime: refusing to extract device/fifo entry '$name'")
                }

                // Reject absolute paths
                if (name.startsWith("/")) {
                    throw GradleException("fetchRuntime: refusing to extract absolute path '$name'")
                }
                // Reject path traversal
                if (name.split("/").any { it == ".." }) {
                    throw GradleException("fetchRuntime: refusing to extract path-traversal entry '$name'")
                }
                // Confinement check
                val resolved = File(realTarget, name).canonicalFile
                if (!resolved.path.startsWith(realTarget.path + File.separator) && resolved.path != realTarget.path) {
                    throw GradleException("fetchRuntime: tar entry '$name' would escape $targetDir on resolution")
                }

                val dataBlocks = ((entry.size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK
                records += EntryRecord(name, entry.typeFlag, entry.size, offset)
                gz.skipNBytes(dataBlocks)
                offset += dataBlocks
            }
        }

        // ── extraction pass ───────────────────────────────────────────────────
        pendingLongName = null
        GZIPInputStream(tarball.inputStream()).use { gz ->
            val header = ByteArray(TAR_BLOCK)
            while (true) {
                val read = gz.readNBytes(header, 0, TAR_BLOCK)
                if (read < TAR_BLOCK) break

                val entry = readTarEntry(header) ?: break

                if (entry.typeFlag == 'L') {
                    val nameBytes = ByteArray(entry.size.toInt())
                    gz.readNBytes(nameBytes, 0, nameBytes.size)
                    pendingLongName = readCString(nameBytes, 0, nameBytes.size)
                    val pad = ((entry.size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK - entry.size
                    if (pad > 0) gz.skipNBytes(pad)
                    continue
                }

                val name = pendingLongName ?: entry.name
                pendingLongName = null

                val base = name.substringAfterLast('/')
                val dataBlocks = ((entry.size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK

                if (base.startsWith("._") || entry.typeFlag in listOf('1', '2', '3', '4', '6')) {
                    gz.skipNBytes(dataBlocks)
                    continue
                }

                val outFile = File(realTarget, name)
                if (entry.typeFlag == '5' || (entry.typeFlag == '\u0000' && name.endsWith("/"))) {
                    outFile.mkdirs()
                    gz.skipNBytes(dataBlocks)
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        var remaining = entry.size
                        val buf = ByteArray(8192)
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = gz.read(buf, 0, toRead)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    // Skip padding
                    val pad = dataBlocks - entry.size
                    if (pad > 0) gz.skipNBytes(pad)
                }
            }
        }
    }

    private fun abiToManifestArch(abi: String): String = when (abi) {
        "arm64-v8a" -> "android-arm64"
        "x86_64" -> "android-x86_64"
        else -> throw GradleException("fetchRuntime: unsupported ABI '$abi'. Supported: arm64-v8a, x86_64")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Read octomil-runtime.properties for version/flavor/abi/skipFetch
// Each can be overridden on the command line: -PoctomilRuntime.version=v0.1.6
// ─────────────────────────────────────────────────────────────────────────────
fun loadRuntimeProperties(): Properties {
    val props = Properties()
    val propsFile = rootProject.file("octomil-runtime.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use<java.io.InputStream, Unit> { props.load(it) }
    }
    return props
}

val runtimeProps = loadRuntimeProperties()

fun gradleOrPropString(key: String, default: String): String =
    providers.gradleProperty(key).orNull
        ?: runtimeProps.getProperty(key, default)

fun gradleOrPropBool(key: String, default: Boolean): Boolean =
    providers.gradleProperty(key).orNull?.toBoolean()
        ?: runtimeProps.getProperty(key)?.toBoolean()
        ?: default

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("jacoco")
    id("maven-publish")
    id("signing")
}

// Gate optional engine runtime dependencies behind a Gradle property.
// Default is false — CI and clean checkouts build without unpublished AARs.
// Local dev with sibling engine repos: ./gradlew ... -Poctomil.includeExternalRuntimes=true
val includeExternalRuntimes: Boolean =
    providers.gradleProperty("octomil.includeExternalRuntimes")
        .map(String::toBoolean)
        .orElse(false)
        .get()

// ─────────────────────────────────────────────────────────────────────────────
// fetchRuntime task registration
//
// Wired to run before preBuild so both debug and release builds trigger it.
// Skip with: ./gradlew :octomil:assembleDebug -PoctomilRuntime.skipFetch=true
// ─────────────────────────────────────────────────────────────────────────────
val fetchRuntime = tasks.register<FetchRuntimeTask>("fetchRuntime") {
    group = "octomil"
    description = "Download liboctomil-runtime.so from GitHub Releases and stage into jniLibs"

    // Resolve all values at REGISTRATION time. The @TaskAction body MUST NOT
    // touch `project`, `project.layout`, `project.rootDir`, `project.buildDir`,
    // or any other Project-instance API — Gradle's configuration cache forbids
    // accessing the Project from a task action and will fail the build.
    runtimeVersion.set(gradleOrPropString("octomilRuntime.version", "v0.1.16"))
    runtimeFlavor.set(gradleOrPropString("octomilRuntime.flavor", "chat"))
    runtimeAbi.set(gradleOrPropString("octomilRuntime.abi", "arm64-v8a"))
    skipFetch.set(gradleOrPropBool("octomilRuntime.skipFetch", false))

    // Pre-resolve filesystem locations as DirectoryProperty inputs. These are
    // captured by the configuration cache and read via .get() in the action.
    cacheRootDir.set(rootProject.layout.projectDirectory.dir(".gradle/octomil-runtime"))
    jniLibsRootDir.set(project.layout.projectDirectory.dir("src/main/jniLibs"))
}

tasks.named("preBuild") {
    dependsOn(fetchRuntime)
}

android {
    namespace = "ai.octomil"
    compileSdk = 36

    defaultConfig {
        minSdk = 33

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "OCTOMIL_VERSION", "\"${project.property("OCTOMIL_VERSION")}\"")

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            enableUnitTestCoverage = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(listOf(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
            ))
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "3g"
                it.maxParallelForks = 1
                it.forkEvery = 1
                it.setFailFast(true)
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                )
                it.extensions.configure<JacocoTaskExtension> {
                    isEnabled = false
                }
            }
        }
    }

    // Optional engine runtimes live in src/externalRuntimes/kotlin.
    // Include that source set only when the external runtime artifacts are available.
    if (includeExternalRuntimes) {
        sourceSets["main"].kotlin.srcDir("src/externalRuntimes/kotlin")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // AndroidX
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.11.1")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Security - EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0")

    // Networking - Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:logging-interceptor:5.3.2")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")

    // TensorFlow Lite — core + GPU delegate
    
    
    //     implementation("com.google.ai.edge.litert:litert:latest")
    //     implementation("com.google.ai.edge.litert:litert-gpu:latest")
    
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.5.0") {
        // litert-support and litert-support-api share the same Android namespace
        // (org.tensorflow.lite.support), causing manifest merger failures in AGP 9.0+.
        exclude(group = "com.google.ai.edge.litert", module = "litert-support-api")
    }
    implementation("org.tensorflow:tensorflow-lite-gpu:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.17.0")

    // Optional vendor NPU delegates — loaded via reflection in TFLiteTrainer.
    // Add the ones relevant to your target devices. The SDK auto-detects the SoC
    // and uses the delegate if the AAR is on the classpath; no code changes needed.
    //
    // Qualcomm QNN (Snapdragon NPU/DSP — replaces deprecated Hexagon):
    //   implementation("com.qualcomm.qti:qnn-tflite-delegate:2.+")
    //   // AAR from Qualcomm AI Hub: https://aihub.qualcomm.com/
    //
    // Samsung Eden / ENN (Exynos NPU):
    //   implementation("com.samsung.android:eden-tflite-delegate:1.+")
    //   // AAR from Samsung Mobile AI SDK: https://developer.samsung.com/neural
    //
    // MediaTek NeuroPilot (Dimensity APU):
    //   implementation("com.mediatek.neuropilot:tflite-neuron-delegate:1.+")
    //   // AAR from NeuroPilot SDK: https://neuropilot.mediatek.com/

    // Engine runtimes — optional, published as Maven Central artifacts.
    // Composite build substitution (in settings.gradle.kts) replaces these
    // with local projects when engine repos are present on disk.
    // Gated behind -Poctomil.includeExternalRuntimes=true because these
    // artifacts are not yet published to Maven Central.
    if (includeExternalRuntimes) {
        implementation("ai.octomil:octomil-runtime-sherpa-android:1.0.0")
        implementation("ai.octomil:octomil-runtime-llama-android:1.0.0")
    }

    // Archive extraction for prepare-lifecycle Materializer.
    // Apache Commons Compress provides tar / bzip2 / gzip / zip
    // input streams without shelling out (Android has no
    // ``/usr/bin/tar``). 2025 release; Apache 2.0.
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Testing
    testImplementation("org.json:json:20260522")
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.3.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.work:work-testing:2.11.1")
}

// Maven publish to Maven Central (Sonatype OSSRH)
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.property("OCTOMIL_GROUP").toString()
                artifactId = "octomil-android"
                version = project.property("OCTOMIL_VERSION").toString()

                pom {
                    name.set("Octomil Android SDK")
                    description.set("On-device AI inference SDK for Android — chat, transcription, and text prediction")
                    url.set("https://github.com/octomil/octomil-android")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("octomil")
                            name.set("Octomil Team")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/octomil/octomil-android.git")
                        developerConnection.set("scm:git:ssh://github.com/octomil/octomil-android.git")
                        url.set("https://github.com/octomil/octomil-android")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "SonatypeOSSRH"
                val releasesUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                val snapshotsUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
                url = if (project.property("OCTOMIL_VERSION").toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
                credentials {
                    username = System.getenv("OSSRH_USERNAME") ?: ""
                    password = System.getenv("OSSRH_PASSWORD") ?: ""
                }
            }
        }
    }

    // Only sign when publishing to remote repos. CI can provide an in-memory
    // key; local release machines may use the configured gpg command.
    if (System.getenv("OSSRH_USERNAME")?.isNotBlank() == true) {
        signing {
            val inMemoryKey =
                providers.gradleProperty("signingInMemoryKey").orNull
                    ?: System.getenv("SIGNING_IN_MEMORY_KEY")
            val inMemoryKeyPassword =
                providers.gradleProperty("signingInMemoryKeyPassword").orNull
                    ?: System.getenv("SIGNING_IN_MEMORY_KEY_PASSWORD")

            if (!inMemoryKey.isNullOrBlank()) {
                useInMemoryPgpKeys(inMemoryKey, inMemoryKeyPassword)
            } else {
                useGpgCmd()
            }
            sign(publishing.publications["release"])
        }
    }
}

// JaCoCo configuration
tasks.register("jacocoTestReport", JacocoReport::class) {
    group = "verification"
    description = "Generates JaCoCo code coverage report from debug unit tests."
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    val fileFilter = listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*"
    )

    val debugTree = fileTree("${project.layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }

    val mainSrc = "${project.projectDir}/src/main/kotlin"

    sourceDirectories.setFrom(files(mainSrc))
    classDirectories.setFrom(files(debugTree))
    executionData.setFrom(fileTree("${project.layout.buildDirectory.get()}/jacoco") {
        include("**/*.exec")
    })
}
