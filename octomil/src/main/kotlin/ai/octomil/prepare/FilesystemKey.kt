@file:JvmName("FilesystemKey")

package ai.octomil.prepare

import java.security.MessageDigest

/**
 * Shared filesystem-key helper for planner-supplied identifiers.
 *
 * Port of Python `octomil/runtime/lifecycle/_fs_key.py`, Node
 * `src/prepare/fs-key.ts`, and Swift `FilesystemKey.swift`.
 *
 * PrepareManager (artifact dir) and FileLock (lock file) consume the
 * same key shape so two layers of the prepare-lifecycle pipeline
 * cannot disagree about safety. This module is the one place that
 * decides.
 *
 * Key requirements (mirrored across the four SDKs):
 *
 *  - **Bounded byte length.** NAME_MAX is 255 _bytes_ on every common
 *    filesystem (ext4, F2FS on Android, APFS on Mac, NTFS), not 255
 *    characters. The visible portion is therefore capped at
 *    [DEFAULT_MAX_VISIBLE_CHARS] characters of pure-ASCII output (the
 *    sanitizer replaces every non-ASCII byte with `_` first).
 *  - **Windows-safe.** Strips `< > : " / \ | ? *` along with
 *    everything non-ASCII. Even though Android doesn't enforce these,
 *    an artifact id round-tripped through a Windows host (CI, shared
 *    NAS, code-review tools) must remain a valid filename.
 *  - **Stable mapping.** Same input → same output across processes
 *    AND across SDKs (Python / Node / Swift / Kotlin must pick the
 *    same key for the same artifact id, so a Python-side
 *    `client.prepare` populates a directory an Android-side dispatch
 *    reads).
 *  - **Disambiguating.** Distinct planner ids that sanitize to the
 *    same visible name still get distinct keys via a SHA-256 suffix
 *    over the _original_ (unmodified) input.
 */

/** Visible-portion cap. Full key is `<visible>-<12-char hash>`;
 * 96 + 1 + 12 = 109-byte ASCII payload, well under NAME_MAX (255
 * bytes) even with the consumer's own suffix (e.g. `.lock`). */
const val DEFAULT_MAX_VISIBLE_CHARS = 96

/** Replace any character outside `[A-Za-z0-9._-]` with `_`. */
private val SAFE_CHARS = Regex("[^A-Za-z0-9._-]")

class FilesystemKeyException(message: String) : IllegalArgumentException(message)

/**
 * Return a NAME_MAX-safe, Windows-safe, deterministic key for [name].
 * Pure ASCII output, `result.length <= maxVisibleChars + 13`, stable
 * across processes. Empty / dot-only inputs collapse to `id-<hash>`
 * so the consumer always has at least a 14-character (1 + 1 + 12)
 * component.
 *
 * @throws FilesystemKeyException only when [name] contains a NUL byte.
 */
fun safeFilesystemKey(name: String, maxVisibleChars: Int = DEFAULT_MAX_VISIBLE_CHARS): String {
    if (name.contains('\u0000')) {
        throw FilesystemKeyException("filesystem key must not contain NUL bytes")
    }
    var sanitized = SAFE_CHARS.replace(name, "_").trim('_', '.')
    if (sanitized.isEmpty() || sanitized == "." || sanitized == "..") {
        sanitized = "id"
    }
    if (sanitized.length > maxVisibleChars) {
        sanitized = sanitized.substring(0, maxVisibleChars).trimEnd('_', '.')
        if (sanitized.isEmpty()) sanitized = "id"
    }
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(name.toByteArray(Charsets.UTF_8))
    val digestPrefix = hash.joinToString("") { "%02x".format(it) }.substring(0, 12)
    return "$sanitized-$digestPrefix"
}
