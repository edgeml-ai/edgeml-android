package ai.octomil.runtime.core

import android.content.SharedPreferences
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Persists benchmark winners keyed by a strong composite identity:
 * `artifactDigest` (strongest) > `modelVersion` > `filePath + fileSize` (weakest).
 *
 * Key components:
 * - **modelId**: Canonical model identifier
 * - **artifactDigest**: SHA-256 of the model file (strongest identity, optional)
 * - **modelVersion**: Immutable version string from catalog (optional)
 * - **filePath + fileSize**: Fallback when digest/version unavailable
 * - **deviceClass**: Hardware identifier (e.g. "Pixel 8")
 * - **sdkVersion**: SDK version string
 *
 * Construct via [BenchmarkStore.create] which requires [SharedPreferences].
 * Access the singleton via [BenchmarkStore.instance] after initialization.
 */
class BenchmarkStore(
    private val prefs: SharedPreferences,
    private val deviceClass: String = defaultDeviceClass(),
    private val sdkVersion: String = defaultSdkVersion(),
) {
    // MARK: - Public API

    /**
     * Record a benchmark winner for a specific model + device combination.
     *
     * @param winner The engine that won the benchmark.
     * @param modelId Canonical model identifier.
     * @param modelFilePath Absolute path of the model file, if known.
     * @param modelVersion Immutable artifact version from catalog, if known.
     * @param artifactDigest Pre-computed SHA-256 hex digest of the model file, if known.
     */
    fun record(
        winner: Engine,
        modelId: String,
        modelFilePath: String? = null,
        modelVersion: String? = null,
        artifactDigest: String? = null,
    ) {
        val key = storeKey(modelId, modelFilePath, modelVersion, artifactDigest)
        prefs.edit().putString(key, winner.wireValue).apply()
    }

    /**
     * Retrieve the persisted benchmark winner, if any.
     *
     * @param modelId Canonical model identifier.
     * @param modelFilePath Absolute path of the model file, if known.
     * @param modelVersion Immutable artifact version from catalog, if known.
     * @param artifactDigest Pre-computed SHA-256 hex digest of the model file, if known.
     * @return The winning engine, or `null` if no benchmark has been recorded.
     */
    fun winner(
        modelId: String,
        modelFilePath: String? = null,
        modelVersion: String? = null,
        artifactDigest: String? = null,
    ): Engine? {
        val key = storeKey(modelId, modelFilePath, modelVersion, artifactDigest)
        val raw = prefs.getString(key, null) ?: return null
        return Engine.fromWireValue(raw)
    }

    /**
     * Clear all persisted benchmark results.
     */
    fun clearAll() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    // MARK: - Key Construction

    /**
     * Build a composite key using the strongest available artifact identity.
     *
     * Priority:
     * 1. `artifactDigest` -- SHA-256 of the model file (strongest)
     * 2. `modelVersion` -- immutable catalog version string
     * 3. `canonicalPath + fileSize` -- fallback
     */
    internal fun storeKey(
        modelId: String,
        modelFilePath: String? = null,
        modelVersion: String? = null,
        artifactDigest: String? = null,
    ): String {
        val artifactIdentity: String = when {
            artifactDigest != null -> "d:$artifactDigest"
            modelVersion != null -> "v:$modelVersion"
            else -> {
                val path = canonicalArtifactPath(modelFilePath)
                val size = fileSize(modelFilePath)
                "p:${path}_s:$size"
            }
        }
        return "${KEY_PREFIX}${modelId}_${artifactIdentity}_${deviceClass}_$sdkVersion"
    }

    companion object {
        private const val KEY_PREFIX = "octomil_bm_"
        private const val PREFS_NAME = "octomil_benchmark_store"

        /**
         * Shared singleton instance. Must be initialized via [create] before use.
         */
        @Volatile
        var instance: BenchmarkStore = BenchmarkStore(InMemorySharedPreferences())
            private set

        /**
         * Initialize the singleton with the given [SharedPreferences].
         * Typically called during SDK initialization.
         */
        fun create(prefs: SharedPreferences): BenchmarkStore {
            val store = BenchmarkStore(prefs)
            instance = store
            return store
        }

        /**
         * Initialize the singleton using the application context.
         * Uses a dedicated SharedPreferences file.
         */
        fun create(context: android.content.Context): BenchmarkStore {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            return create(prefs)
        }

        // MARK: - Artifact Identity Helpers

        /**
         * Last two path segments as a stable relative identifier.
         * E.g. "/data/models/whisper-tiny.bin" -> "models/whisper-tiny.bin"
         */
        internal fun canonicalArtifactPath(filePath: String?): String {
            if (filePath == null) return "unknown"
            val segments = filePath.split("/").filter { it.isNotEmpty() }
            return segments.takeLast(2).joinToString("/")
        }

        /**
         * File size in bytes, or 0 if the file does not exist or is inaccessible.
         */
        internal fun fileSize(filePath: String?): Long {
            if (filePath == null) return 0
            return try {
                File(filePath).length()
            } catch (_: Exception) {
                0
            }
        }

        /**
         * Compute a SHA-256 hex digest for a model artifact.
         *
         * - For a single file: hash the file contents directly.
         * - For a directory (multi-file artifact): hash over an ordered manifest of
         *   `(relative_path, size, sha256)` for each file.
         *
         * For large artifacts, prefer passing a pre-computed digest to [record]/[winner].
         */
        fun artifactDigest(path: String): String? {
            val file = File(path)
            if (!file.exists()) return null
            return if (file.isDirectory) {
                directoryDigest(file)
            } else {
                fileDigest(file)
            }
        }

        /**
         * SHA-256 of a single file's contents.
         */
        internal fun fileDigest(file: File): String? {
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        md.update(buffer, 0, read)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * SHA-256 over the ordered manifest of all files in a directory.
         * Manifest format per entry: "relative/path\tsize\thex_sha256\n"
         */
        internal fun directoryDigest(dir: File): String? {
            val entries = mutableListOf<Triple<String, Long, String>>()
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relativePath = file.relativeTo(dir).path
                    val size = file.length()
                    val digest = fileDigest(file) ?: return null
                    entries.add(Triple(relativePath, size, digest))
                }

            // Sort by relative path for deterministic ordering
            entries.sortBy { it.first }

            val manifest = entries.joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" }
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(manifest.toByteArray(Charsets.UTF_8))
                md.digest().joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                null
            }
        }

        // MARK: - Device & SDK

        internal fun defaultDeviceClass(): String =
            "${Build.MANUFACTURER}_${Build.MODEL}".replace(" ", "_")

        internal fun defaultSdkVersion(): String =
            try {
                ai.octomil.BuildConfig.OCTOMIL_VERSION
            } catch (_: Exception) {
                "0"
            }
    }
}

/**
 * Minimal in-memory SharedPreferences for use before SDK initialization.
 * Not for production use -- exists so [BenchmarkStore.instance] is never null.
 */
private class InMemorySharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? =
        map[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = InMemoryEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class InMemoryEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removals.add(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
        override fun commit(): Boolean { doApply(); return true }
        override fun apply() { doApply() }

        private fun doApply() {
            if (clearAll) map.clear()
            removals.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }
}
