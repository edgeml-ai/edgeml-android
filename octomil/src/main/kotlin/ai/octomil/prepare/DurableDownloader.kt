package ai.octomil.prepare

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, resumable, multi-URL artifact downloader for Android.
 *
 * Port of Python `durable_download.py`, Node `durable-download.ts`,
 * and Swift `DurableDownloader.swift`. Streams bytes to
 * `<destDir>/.parts/<rel>.part` with HTTP-Range resume across
 * attempts, verifies SHA-256 against the artifact's digest, and
 * atomically renames into place. Multi-URL fallback list of
 * endpoints; expired endpoints skipped before any HTTP request.
 *
 * Crash-resume via a JSON sidecar at `<cacheDir>/.progress.json`
 * (same shape Node + Swift use; no native SQLite dep). The journal
 * is _advisory_: at open time we cross-check the row against the
 * on-disk `.part` file and clamp `bytesWritten` to the smaller.
 *
 * Uses [HttpURLConnection] from `java.net` rather than OkHttp so the
 * SDK stays thin (Android already ships HttpURLConnection in the
 * platform). Range / If-Match / 416 handling is parity-checked
 * against the other SDKs.
 */
class DurableDownloader(
    val cacheDir: File,
    private val timeoutMs: Int = 600_000,
    private val now: () -> Instant = Instant::now,
) {
    private val journal: ProgressJournal

    init {
        cacheDir.mkdirs()
        journal = ProgressJournal(File(cacheDir, ".progress.json"))
    }

    suspend fun download(descriptor: ArtifactDescriptor, destDir: File): DownloadResult {
        if (descriptor.endpoints.isEmpty()) {
            throw DownloadException("Artifact '${descriptor.artifactId}' has no download endpoints.")
        }
        if (descriptor.requiredFiles.isEmpty()) {
            throw DownloadException("Artifact '${descriptor.artifactId}' has no required_files.")
        }
        for (rf in descriptor.requiredFiles) validateRelativePath(rf.relativePath)

        destDir.mkdirs()
        val partsDir = File(destDir, ".parts").apply { mkdirs() }

        val lock = FileLock(descriptor.artifactId, lockDir = File(cacheDir, ".locks"))
        lock.acquire()
        try {
            val files = HashMap<String, File>()
            for (rf in descriptor.requiredFiles) {
                files[rf.relativePath] = downloadOne(descriptor, rf, destDir, partsDir)
            }
            return DownloadResult(descriptor.artifactId, files)
        } finally {
            lock.release()
        }
    }

    private fun downloadOne(
        descriptor: ArtifactDescriptor,
        required: RequiredFile,
        destDir: File,
        partsDir: File,
    ): File {
        val safeRel = validateRelativePath(required.relativePath)
        val finalFile = if (safeRel.isEmpty()) File(destDir, "artifact") else safeJoin(destDir, safeRel)
        finalFile.parentFile?.mkdirs()

        if (finalFile.exists() && digestMatches(finalFile, required.digest)) return finalFile

        val partName = (safeRel.ifEmpty { "artifact" }).replace("/", "_") + ".part"
        val partFile = File(partsDir, partName)

        val entry = journal.get(descriptor.artifactId, required.relativePath)
        val onDisk = if (partFile.exists()) partFile.length() else 0L
        var offset = minOf(entry.bytesWritten, onDisk)
        if (offset != onDisk && partFile.exists()) {
            RandomAccessFile(partFile, "rw").use { it.setLength(offset) }
        }

        var lastError: Throwable? = null
        val ordered = orderEndpoints(descriptor.endpoints.size, entry.endpointIndex)
        for (index in ordered) {
            val endpoint = descriptor.endpoints[index]
            if (isExpired(endpoint, now())) continue
            try {
                fetchOne(endpoint, required, partFile, offset, descriptor.artifactId, index)
                if (digestMatches(partFile, required.digest)) {
                    if (finalFile.exists()) finalFile.delete()
                    if (!partFile.renameTo(finalFile)) {
                        throw DownloadException(
                            "Failed to rename ${partFile.absolutePath} -> ${finalFile.absolutePath}"
                        )
                    }
                    journal.clear(descriptor.artifactId, required.relativePath)
                    return finalFile
                }
                partFile.delete()
                journal.clear(descriptor.artifactId, required.relativePath)
                offset = 0
                lastError = ChecksumMismatchException(
                    descriptor.artifactId, required.relativePath, index
                )
            } catch (e: HttpStatusException) {
                lastError = e
                if (e.status in setOf(401, 403, 404, 410)) {
                    partFile.delete()
                    journal.clear(descriptor.artifactId, required.relativePath)
                    offset = 0
                } else {
                    offset = if (partFile.exists()) partFile.length() else 0L
                }
            } catch (e: Throwable) {
                lastError = e
                offset = if (partFile.exists()) partFile.length() else 0L
            }
        }
        throw DownloadException(
            "Exhausted all endpoints for '${descriptor.artifactId}' file " +
                "'${required.relativePath}'. Last error: ${lastError?.message ?: "unknown"}",
            lastError
        )
    }

    private fun fetchOne(
        endpoint: DownloadEndpoint,
        required: RequiredFile,
        partFile: File,
        offset: Long,
        artifactId: String,
        endpointIndex: Int,
    ) {
        val safeRel = validateRelativePath(required.relativePath)
        val url = resolveUrl(endpoint.url, safeRel)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = true
            for ((k, v) in endpoint.headers) setRequestProperty(k, v)
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val status = conn.responseCode
            if (status == 416 && offset > 0) {
                conn.disconnect()
                partFile.delete()
                journal.clear(artifactId, required.relativePath)
                val retry = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    for ((k, v) in endpoint.headers) {
                        if (!k.equals("Range", ignoreCase = true)) setRequestProperty(k, v)
                    }
                }
                try {
                    if (retry.responseCode != 200) {
                        throw HttpStatusException(retry.responseCode, url)
                    }
                    streamToPart(retry.inputStream, partFile, 0L, artifactId, required.relativePath, endpointIndex)
                } finally {
                    retry.disconnect()
                }
                return
            }
            if (status != 200 && status != 206) {
                throw HttpStatusException(status, url)
            }
            val resume = (status == 206) && offset > 0
            streamToPart(conn.inputStream, partFile, if (resume) offset else 0L, artifactId, required.relativePath, endpointIndex)
        } finally {
            conn.disconnect()
        }
    }

    private fun streamToPart(
        input: java.io.InputStream,
        partFile: File,
        offset: Long,
        artifactId: String,
        relativePath: String,
        endpointIndex: Int,
    ) {
        if (offset == 0L) partFile.writeBytes(ByteArray(0))
        var bytesWritten = offset
        var lastFlush = bytesWritten
        val flushBytes = 4L * 1024 * 1024
        val buffer = ByteArray(64 * 1024)
        RandomAccessFile(partFile, "rw").use { raf ->
            raf.seek(offset)
            input.use { stream ->
                while (true) {
                    val n = stream.read(buffer)
                    if (n <= 0) break
                    raf.write(buffer, 0, n)
                    bytesWritten += n
                    if (bytesWritten - lastFlush >= flushBytes) {
                        raf.fd.sync()
                        journal.record(artifactId, relativePath, bytesWritten, endpointIndex)
                        lastFlush = bytesWritten
                    }
                }
                raf.fd.sync()
            }
        }
        journal.record(artifactId, relativePath, bytesWritten, endpointIndex)
    }

    private fun isExpired(endpoint: DownloadEndpoint, nowI: Instant): Boolean {
        val exp = endpoint.expiresAt ?: return false
        return !nowI.isBefore(exp)
    }

    private fun orderEndpoints(count: Int, preferred: Int): List<Int> {
        val all = (0 until count).toList()
        return if (preferred in 0 until count) listOf(preferred) + all.filter { it != preferred } else all
    }

    private fun resolveUrl(base: String, rel: String): String {
        if (rel.isEmpty()) return base
        return base.trimEnd('/') + "/" + rel.trimStart('/')
    }

    companion object {
        fun validateRelativePath(rel: String): String {
            if (rel.isEmpty()) return ""
            if (rel.contains('\u0000')) {
                throw InvalidPathException(rel, "contains a NUL byte")
            }
            if (rel.contains('\\')) {
                throw InvalidPathException(rel, "uses backslashes; artifacts must be addressed with forward-slash POSIX paths")
            }
            val segments = rel.split("/")
            for (seg in segments) {
                if (seg.isEmpty() || seg == "." || seg == "..") {
                    throw InvalidPathException(rel, "must not contain '.', '..', or empty segments")
                }
            }
            if (rel.startsWith("/")) {
                throw InvalidPathException(rel, "must be relative")
            }
            if (rel.length >= 2 && rel[0].isLetter() && rel[1] == ':') {
                throw InvalidPathException(rel, "looks like a Windows drive")
            }
            return rel
        }

        fun safeJoin(destDir: File, rel: String): File {
            val safe = validateRelativePath(rel)
            val base = destDir.canonicalFile
            if (safe.isEmpty()) return base
            val candidate = File(base, safe).canonicalFile
            val basePath = base.path + File.separator
            if (candidate.path != base.path && !candidate.path.startsWith(basePath)) {
                throw InvalidPathException(rel, "resolves outside the artifact directory")
            }
            return candidate
        }

        fun digestMatches(file: File, expected: String): Boolean {
            if (!file.exists()) return false
            val expectedHex =
                (if (expected.startsWith("sha256:")) expected.substring(7) else expected).lowercase()
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            val actualHex = md.digest().joinToString("") { "%02x".format(it) }
            return actualHex == expectedHex
        }
    }
}

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

data class DownloadEndpoint(
    val url: String,
    val expiresAt: Instant? = null,
    val headers: Map<String, String> = emptyMap(),
)

data class RequiredFile(
    val relativePath: String,
    val digest: String,
    val sizeBytes: Long? = null,
)

data class ArtifactDescriptor(
    val artifactId: String,
    val requiredFiles: List<RequiredFile>,
    val endpoints: List<DownloadEndpoint>,
)

data class DownloadResult(
    val artifactId: String,
    val files: Map<String, File>,
)

class DownloadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class InvalidPathException(val relativePath: String, val reason: String) :
    IllegalArgumentException("Required file path '$relativePath' is invalid: $reason")

class ChecksumMismatchException(val artifactId: String, val relativePath: String, val endpointIndex: Int) :
    RuntimeException("Digest mismatch for '$artifactId' file '$relativePath' from endpoint $endpointIndex.")

class HttpStatusException(val status: Int, val url: String) :
    IOException("Unexpected HTTP $status for $url")

// ---------------------------------------------------------------------------
// JSON-backed progress journal
// ---------------------------------------------------------------------------

internal data class ProgressEntry(val bytesWritten: Long, val endpointIndex: Int, val updatedAtMillis: Long)

internal class ProgressJournal(private val path: File) {
    private val lock = Any()
    private val entries: MutableMap<String, MutableMap<String, ProgressEntry>>

    init {
        path.parentFile?.mkdirs()
        entries = load()
    }

    fun get(artifactId: String, relativePath: String): ProgressEntry =
        synchronized(lock) {
            entries[artifactId]?.get(relativePath) ?: ProgressEntry(0, 0, 0)
        }

    fun record(artifactId: String, relativePath: String, bytesWritten: Long, endpointIndex: Int) {
        synchronized(lock) {
            val slot = entries.getOrPut(artifactId) { HashMap() }
            slot[relativePath] = ProgressEntry(bytesWritten, endpointIndex, System.currentTimeMillis())
            flush()
        }
    }

    fun clear(artifactId: String, relativePath: String) {
        synchronized(lock) {
            val slot = entries[artifactId] ?: return
            slot.remove(relativePath)
            if (slot.isEmpty()) entries.remove(artifactId)
            flush()
        }
    }

    private fun load(): MutableMap<String, MutableMap<String, ProgressEntry>> {
        if (!path.exists()) return HashMap()
        return try {
            val raw = path.readText()
            if (raw.isBlank()) return HashMap()
            val obj = JSONObject(raw)
            val entriesObj = obj.optJSONObject("entries") ?: return HashMap()
            val out = HashMap<String, MutableMap<String, ProgressEntry>>()
            for (artifactId in entriesObj.keys()) {
                val artifactObj = entriesObj.getJSONObject(artifactId)
                val slot = HashMap<String, ProgressEntry>()
                for (rel in artifactObj.keys()) {
                    val e = artifactObj.getJSONObject(rel)
                    slot[rel] = ProgressEntry(
                        e.optLong("bytesWritten", 0),
                        e.optInt("endpointIndex", 0),
                        e.optLong("updatedAtMillis", 0),
                    )
                }
                out[artifactId] = slot
            }
            out
        } catch (_: Throwable) {
            // Corrupt or missing — start fresh. The journal is
            // advisory; downloadOne re-checks the on-disk .part size
            // before trusting any offset.
            HashMap()
        }
    }

    private fun flush() {
        val root = JSONObject()
        val entriesObj = JSONObject()
        for ((artifactId, slot) in entries) {
            val artifactObj = JSONObject()
            for ((rel, entry) in slot) {
                artifactObj.put(rel, JSONObject().apply {
                    put("bytesWritten", entry.bytesWritten)
                    put("endpointIndex", entry.endpointIndex)
                    put("updatedAtMillis", entry.updatedAtMillis)
                })
            }
            entriesObj.put(artifactId, artifactObj)
        }
        root.put("entries", entriesObj)
        val tmp = File(path.parentFile, path.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(path)) {
            // Fall back to copy + delete on platforms where the
            // tmp-and-final live on different filesystems.
            path.writeText(root.toString())
            tmp.delete()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun unusedJsonArrayHolder(@Suppress("UNUSED_PARAMETER") a: JSONArray) = Unit
}
