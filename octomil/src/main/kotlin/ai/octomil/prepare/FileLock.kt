package ai.octomil.prepare

import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * Cross-process file lock for artifact downloads.
 *
 * Port of Python `file_lock.py`, Node `file-lock.ts`, and Swift
 * `FileLock.swift`. Prevents concurrent downloads of the same
 * artifact across processes (multiple Android app components,
 * background services, etc.).
 *
 * Built on `File.createNewFile` (which is atomic on POSIX
 * filesystems via `O_CREAT | O_EXCL`) plus heartbeat mtime refresh
 * and stale-lock stealing for crashed holders. Same contract as the
 * other SDKs: `acquire(timeoutMs:)` blocks until the lock can be
 * taken or the deadline elapses; `release()` removes the file.
 *
 * The lock filename comes from [safeFilesystemKey] so PrepareManager
 * (artifact dir) and FileLock (lock file) use the same key shape.
 */
class FileLock(
    name: String,
    lockDir: File? = null,
    private val timeoutMs: Long = 300_000,
    private val pollIntervalMs: Long = 500,
    private val staleTimeoutMs: Long = 5 * 60_000,
) {
    val lockFile: File
    private var heartbeat: Job? = null
    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        val dir = lockDir ?: defaultLockDir()
        dir.mkdirs()
        val safe = safeFilesystemKey(name)
        lockFile = File(dir, "$safe.lock")
    }

    var isLocked: Boolean = false
        private set

    /**
     * Acquire the lock, blocking up to [timeoutMs]. Throws
     * [LockTimeoutException] on deadline.
     */
    suspend fun acquire() {
        val deadline = System.currentTimeMillis() + timeoutMs
        lockFile.parentFile?.mkdirs()

        while (true) {
            // ``File.createNewFile`` is atomic on POSIX — either we
            // made the file (and now hold the lock), or someone else
            // has it. Returns false on EEXIST.
            val created = try {
                lockFile.createNewFile()
            } catch (e: IOException) {
                throw e
            }
            if (created) {
                isLocked = true
                startHeartbeat()
                return
            }
            if (tryStealStaleLock()) continue
            if (System.currentTimeMillis() >= deadline) {
                throw LockTimeoutException(lockFile, timeoutMs)
            }
            delay(pollIntervalMs)
        }
    }

    /**
     * Release the lock. Idempotent — calling twice is safe.
     */
    fun release() {
        heartbeat?.cancel()
        heartbeat = null
        if (isLocked) {
            isLocked = false
            try {
                lockFile.delete()
            } catch (_: Throwable) {
                // Ignore — release should never propagate fs errors
                // that aren't user-actionable. The next acquire will
                // detect the lock as stale.
            }
        }
        heartbeatScope.cancel()
    }

    private fun startHeartbeat() {
        // Refresh mtime every ``staleTimeoutMs / 5`` so a long
        // download isn't stolen at the 5-min stale cutoff.
        val interval = (staleTimeoutMs / 5).coerceAtLeast(1000)
        heartbeat = heartbeatScope.launch {
            while (true) {
                delay(interval)
                try {
                    lockFile.setLastModified(System.currentTimeMillis())
                } catch (_: Throwable) {
                    // The file was removed; release will no-op.
                }
            }
        }
    }

    private fun tryStealStaleLock(): Boolean {
        if (!lockFile.exists()) return true
        val ageMs = System.currentTimeMillis() - lockFile.lastModified()
        if (ageMs <= staleTimeoutMs) return false
        return try {
            val deleted = lockFile.delete()
            // True if we deleted it OR another waiter already did.
            deleted || !lockFile.exists()
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        /** Where lock files land by default. Mirrors the other SDKs. */
        fun defaultLockDir(): File {
            val cacheRoot = System.getenv("OCTOMIL_CACHE_DIR")
            if (cacheRoot != null) {
                return File(cacheRoot, "artifacts/.locks")
            }
            val xdg = System.getenv("XDG_CACHE_HOME")
            if (xdg != null) {
                return File(xdg, "octomil/artifacts/.locks")
            }
            val home = System.getProperty("user.home") ?: "."
            return File(home, ".cache/octomil/artifacts/.locks")
        }
    }
}

class LockTimeoutException(val lockFile: File, val timeoutMs: Long) :
    RuntimeException(
        "Could not acquire lock ${lockFile.absolutePath} within ${timeoutMs}ms. " +
            "Another process may be downloading this artifact."
    )
