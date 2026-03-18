package ai.octomil.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Structured local metadata store for artifacts managed via desired-state sync.
 *
 * Replaces flat file-name conventions with a JSON-backed registry that tracks
 * model_id, model_version, artifact_version, installation status, and which
 * version is currently active (serving inference).
 */
class ArtifactMetadataStore(
    private val storeDir: File,
) {
    private val metadataFile = File(storeDir, "artifact_metadata.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private var entries: MutableMap<String, ArtifactMetadataEntry> = mutableMapOf()

    init {
        storeDir.mkdirs()
        load()
    }

    // =========================================================================
    // Queries
    // =========================================================================

    /**
     * Return the currently active entry for [modelId], or null.
     */
    fun activeEntry(modelId: String): ArtifactMetadataEntry? =
        entries.values.firstOrNull { it.modelId == modelId && it.active }

    /**
     * Return all entries for [modelId], sorted newest-first by installedAt.
     */
    fun entriesForModel(modelId: String): List<ArtifactMetadataEntry> =
        entries.values
            .filter { it.modelId == modelId }
            .sortedByDescending { it.installedAt }

    /**
     * Return a specific entry by artifact ID + version, or null.
     */
    fun entry(artifactId: String, version: String): ArtifactMetadataEntry? =
        entries[key(artifactId, version)]

    /**
     * Return the installed version string for [modelId], or null if nothing is installed.
     */
    fun installedVersion(modelId: String): String? =
        activeEntry(modelId)?.modelVersion

    /**
     * Return all entries.
     */
    fun allEntries(): List<ArtifactMetadataEntry> = entries.values.toList()

    // =========================================================================
    // Mutations
    // =========================================================================

    /**
     * Insert or update a metadata entry. Persists immediately.
     */
    fun upsert(entry: ArtifactMetadataEntry) {
        entries[key(entry.artifactId, entry.artifactVersion)] = entry
        save()
    }

    /**
     * Mark [artifactId]@[version] as active and deactivate all other versions
     * of the same [modelId].
     */
    fun activate(modelId: String, artifactId: String, version: String) {
        // Deactivate siblings
        entries.values
            .filter { it.modelId == modelId && it.active }
            .forEach { old ->
                entries[key(old.artifactId, old.artifactVersion)] =
                    old.copy(active = false)
            }
        // Activate target
        val target = entries[key(artifactId, version)]
        if (target != null) {
            entries[key(artifactId, version)] = target.copy(
                active = true,
                status = ArtifactSyncStatus.ACTIVE,
            )
        }
        save()
    }

    /**
     * Mark an entry as failed and deactivate it.
     */
    fun markFailed(artifactId: String, version: String, errorCode: String) {
        val target = entries[key(artifactId, version)] ?: return
        entries[key(artifactId, version)] = target.copy(
            active = false,
            status = ArtifactSyncStatus.FAILED,
            errorCode = errorCode,
        )
        save()
    }

    /**
     * Mark an entry as pending activation on next launch.
     */
    fun markPendingActivation(artifactId: String, version: String) {
        val target = entries[key(artifactId, version)] ?: return
        entries[key(artifactId, version)] = target.copy(
            status = ArtifactSyncStatus.STAGED,
        )
        save()
    }

    /**
     * Remove an entry and return its file path (for deletion).
     */
    fun remove(artifactId: String, version: String): String? {
        val removed = entries.remove(key(artifactId, version))
        if (removed != null) save()
        return removed?.filePath
    }

    /**
     * Remove all entries for artifacts not in [retainIds] set.
     * Returns file paths that should be deleted.
     */
    fun gc(retainIds: Set<String>): List<String> {
        val toRemove = entries.values
            .filter { it.artifactId !in retainIds }
            .toList()
        val paths = toRemove.mapNotNull { it.filePath }
        toRemove.forEach { entries.remove(key(it.artifactId, it.artifactVersion)) }
        if (toRemove.isNotEmpty()) save()
        return paths
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    private fun load() {
        try {
            if (metadataFile.exists()) {
                val raw = metadataFile.readText()
                val list: List<ArtifactMetadataEntry> = json.decodeFromString(raw)
                entries = list.associateBy { key(it.artifactId, it.artifactVersion) }.toMutableMap()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load artifact metadata, starting fresh")
            entries = mutableMapOf()
        }
    }

    internal fun save() {
        try {
            val list = entries.values.toList()
            metadataFile.writeText(json.encodeToString(list))
        } catch (e: Exception) {
            Timber.e(e, "Failed to save artifact metadata")
        }
    }

    private fun key(artifactId: String, version: String): String =
        "${artifactId}@${version}"
}

/**
 * Status of an artifact in the local sync lifecycle.
 */
enum class ArtifactSyncStatus(val code: String) {
    DOWNLOADING("downloading"),
    DOWNLOADED("downloaded"),
    VERIFIED("verified"),
    STAGED("staged"),
    ACTIVE("active"),
    FAILED("failed"),
    ROLLED_BACK("rolled_back"),
}

/**
 * Metadata for a single artifact managed by desired-state sync.
 */
@Serializable
data class ArtifactMetadataEntry(
    @SerialName("artifact_id")
    val artifactId: String,
    @SerialName("artifact_version")
    val artifactVersion: String,
    @SerialName("model_id")
    val modelId: String,
    @SerialName("model_version")
    val modelVersion: String,
    @SerialName("installed_at")
    val installedAt: Long,
    @SerialName("status")
    val status: ArtifactSyncStatus,
    @SerialName("active")
    val active: Boolean = false,
    @SerialName("file_path")
    val filePath: String? = null,
    @SerialName("checksum")
    val checksum: String? = null,
    @SerialName("size_bytes")
    val sizeBytes: Long = 0,
    @SerialName("error_code")
    val errorCode: String? = null,
)
