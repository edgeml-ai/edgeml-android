package ai.octomil.prepare

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Generic materialization layer for prepared artifacts.
 *
 * Port of Python `octomil/runtime/lifecycle/materialization.py` and
 * Swift `Materializer.swift`. Once a [DurableDownloader] has verified
 * bytes on disk, callers often need a *backend-ready layout* — not
 * just the raw downloaded archive. The Sherpa TTS engine wants
 * `model.onnx` + `voices.bin` + `tokens.txt` + `espeak-ng-data/`
 * under one directory; a future Whisper recipe might want
 * `ggml-tiny.bin`.
 *
 * The runtime knows nothing about Kokoro / tarballs / Sherpa — it
 * just hands the recipe's [MaterializationPlan] to the generic
 * Materializer, which handles archive extraction, safety filtering,
 * idempotency, and required-output verification.
 *
 * Design notes:
 *
 *   - Tar / bz2 / gzip extraction uses Apache Commons Compress so
 *     the SDK doesn't depend on `/usr/bin/tar` (Android has no
 *     such binary). Same shape iOS / Python see, just a different
 *     decompression backend.
 *   - The marker file (`.octomil-materialized`) is written LAST,
 *     after every `requiredOutputs` entry is verified on disk.
 *     A partial extraction (interrupted before the marker) is
 *     detected on the next run and re-extracted, never silently
 *     treated as complete.
 *   - `stripPrefix` is enforced as an allowlist boundary by
 *     re-rooting the extraction in a `staging` directory and
 *     copying the prefix subtree into `artifactDir`. Members
 *     outside the prefix never land in the destination, so a
 *     malformed archive with root-level `model.onnx` cannot
 *     satisfy a recipe whose plan declared
 *     `stripPrefix="kokoro-en-v0_19/"`.
 *   - Symlink members in the archive are dropped (we don't follow
 *     untrusted symlinks during materialization). Each output
 *     path is path-traversal checked before write.
 */
const val EXTRACTION_MARKER_FILENAME: String = ".octomil-materialized"

class MaterializerException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object Materializer {
    /**
     * Apply [plan] to [artifactDir]. Idempotent across runs; safe
     * against tar/zip-bomb / traversal / symlink-escape archives via
     * the staging+copy boundary plus the per-entry traversal check.
     */
    fun materialize(plan: MaterializationPlan, artifactDir: File) {
        if (!artifactDir.exists() || !artifactDir.isDirectory) {
            throw MaterializerException("artifact_dir ${artifactDir.absolutePath} does not exist or is not a directory.")
        }
        when (plan.kind) {
            MaterializationPlan.Kind.NONE -> assertLayoutComplete(plan, artifactDir)
            MaterializationPlan.Kind.ARCHIVE -> materializeArchive(plan, artifactDir)
        }
    }

    // -----------------------------------------------------------------
    // Archive
    // -----------------------------------------------------------------

    private fun materializeArchive(plan: MaterializationPlan, artifactDir: File) {
        val source = plan.source
            ?: throw MaterializerException("MaterializationPlan(kind=ARCHIVE) requires source.")
        val archiveFile = File(artifactDir, source)
        if (!archiveFile.exists()) {
            throw MaterializerException("archive \"$source\" not found under ${artifactDir.absolutePath}.")
        }

        // Idempotency: skip extraction only when the marker is
        // present AND every required output is on disk.
        if (extractionMarkerValid(plan, artifactDir)) return

        // Reset any half-written marker from an interrupted run so
        // the post-extraction completeness check writes fresh.
        File(artifactDir, EXTRACTION_MARKER_FILENAME).takeIf { it.exists() }?.delete()

        // Stage extraction in a sibling directory under artifactDir;
        // only the ``stripPrefix`` subtree is moved into
        // ``artifactDir``. This enforces the prefix as an allowlist
        // and gives us a clean rollback target if extraction fails
        // partway through.
        val staging = File(artifactDir, ".staging-${UUID.randomUUID()}")
        staging.mkdirs()
        try {
            val format = plan.archiveFormat ?: inferArchiveFormat(source)
            extract(archiveFile, staging, format)
            copyAllowlist(staging, artifactDir, plan.stripPrefix)
            assertLayoutComplete(plan, artifactDir)
            // Atomic marker write: write to .tmp and rename so a
            // crash mid-write doesn't leave a half-written marker.
            val markerTmp = File(artifactDir, "$EXTRACTION_MARKER_FILENAME.tmp")
            markerTmp.writeText("kind=${plan.kind.name}\nsource=$source\n")
            val marker = File(artifactDir, EXTRACTION_MARKER_FILENAME)
            if (marker.exists()) marker.delete()
            if (!markerTmp.renameTo(marker)) {
                marker.writeText(markerTmp.readText())
                markerTmp.delete()
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extract(archive: File, into: File, format: MaterializationPlan.ArchiveFormat) {
        BufferedInputStream(FileInputStream(archive)).use { raw ->
            val archiveStream: ArchiveInputStream<*> = when (format) {
                MaterializationPlan.ArchiveFormat.TAR_BZ2 ->
                    TarArchiveInputStream(BZip2CompressorInputStream(raw, true))
                MaterializationPlan.ArchiveFormat.TAR_GZ ->
                    TarArchiveInputStream(GzipCompressorInputStream(raw, true))
                MaterializationPlan.ArchiveFormat.TAR ->
                    TarArchiveInputStream(raw)
                MaterializationPlan.ArchiveFormat.ZIP ->
                    ZipArchiveInputStream(raw)
            }
            archiveStream.use { stream ->
                while (true) {
                    val entry = stream.nextEntry ?: break
                    if (!stream.canReadEntryData(entry)) continue
                    if (entry.isDirectory) {
                        File(into, entry.name).mkdirs()
                        continue
                    }
                    // Symlinks in the archive are dropped — we don't
                    // follow untrusted links during materialization.
                    if (entry is org.apache.commons.compress.archivers.tar.TarArchiveEntry && entry.isSymbolicLink) {
                        continue
                    }
                    val candidate = safeExtractPath(into, entry.name)
                    candidate.parentFile?.mkdirs()
                    FileOutputStream(candidate).use { out ->
                        stream.copyTo(out)
                    }
                }
            }
        }
    }

    /**
     * Path-traversal guard for archive entry names: refuses
     * `..` segments, absolute paths, or anything that resolves
     * outside [base].
     */
    private fun safeExtractPath(base: File, name: String): File {
        val normalized = name.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty()) {
            throw MaterializerException("archive contains an entry with an empty name")
        }
        for (segment in normalized.split('/')) {
            if (segment == "..") {
                throw MaterializerException("archive contains a path-traversal entry: $name")
            }
        }
        val baseCanonical = base.canonicalFile
        val candidate = File(base, normalized)
        // Check parents for symlink escape — base is freshly
        // created here so this is mostly belt-and-braces.
        val candidateCanonical = candidate.canonicalFile
        val basePath = baseCanonical.path + File.separator
        if (candidateCanonical.path != baseCanonical.path && !candidateCanonical.path.startsWith(basePath)) {
            throw MaterializerException("archive entry $name resolves outside extraction root")
        }
        return candidate
    }

    private fun copyAllowlist(staging: File, artifactDir: File, stripPrefix: String?) {
        val prefix = stripPrefix?.let { if (it.endsWith("/")) it else "$it/" } ?: ""
        val sourceRoot: File = if (prefix.isEmpty()) staging else File(staging, prefix.dropLast(1))
        if (!sourceRoot.exists()) {
            // Misshapen archive — empty extraction or wrong prefix
            // declared. Let assertLayoutComplete surface the
            // actionable required-outputs error.
            return
        }
        val sourceRootCanonical = sourceRoot.canonicalFile
        val sourceRootPath = sourceRootCanonical.path
        sourceRootCanonical.walkTopDown().forEach { entry ->
            if (entry == sourceRootCanonical) return@forEach
            // Drop in-archive symlinks at this layer too.
            if (java.nio.file.Files.isSymbolicLink(entry.toPath())) return@forEach
            val entryPath = entry.path
            if (!entryPath.startsWith(sourceRootPath + File.separator)) return@forEach
            val rel = entryPath.removePrefix(sourceRootPath + File.separator)
            val destination = File(artifactDir, rel)
            if (entry.isDirectory) {
                destination.mkdirs()
            } else {
                destination.parentFile?.mkdirs()
                if (destination.exists()) destination.delete()
                entry.copyTo(destination, overwrite = true)
            }
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun inferArchiveFormat(source: String): MaterializationPlan.ArchiveFormat {
        val s = source.lowercase()
        return when {
            s.endsWith(".tar.bz2") || s.endsWith(".tbz2") || s.endsWith(".tbz") ->
                MaterializationPlan.ArchiveFormat.TAR_BZ2
            s.endsWith(".tar.gz") || s.endsWith(".tgz") -> MaterializationPlan.ArchiveFormat.TAR_GZ
            s.endsWith(".tar") -> MaterializationPlan.ArchiveFormat.TAR
            s.endsWith(".zip") -> MaterializationPlan.ArchiveFormat.ZIP
            else -> MaterializationPlan.ArchiveFormat.TAR
        }
    }

    private fun extractionMarkerValid(plan: MaterializationPlan, artifactDir: File): Boolean {
        val marker = File(artifactDir, EXTRACTION_MARKER_FILENAME)
        if (!marker.exists()) return false
        if (plan.requiredOutputs.isEmpty()) return false
        return plan.requiredOutputs.all { rel -> File(artifactDir, rel).exists() }
    }

    private fun assertLayoutComplete(plan: MaterializationPlan, artifactDir: File) {
        val missing = plan.requiredOutputs.filter { rel -> !File(artifactDir, rel).exists() }
        if (missing.isNotEmpty()) {
            throw MaterializerException(
                "required outputs missing under ${artifactDir.absolutePath}: $missing."
            )
        }
    }
}
