package ai.octomil.client

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Read-only catalog browsing client.
 *
 * Fetches the model catalog annotated with device-specific compatibility
 * from `POST /api/v2/catalog/browse`. Uses ETag-based disk caching.
 */
class CatalogClient internal constructor(
    private val context: Context,
    private val serverUrl: String,
    private val capabilitiesClient: CapabilitiesClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var cachedEtag: String? = null
    private var cachedManifest: BrowseManifest? = null

    /**
     * Fetch the catalog manifest annotated with compatibility for this device.
     *
     * Returns cached result on 304 Not Modified. Falls back to disk cache
     * on network failure.
     */
    suspend fun browse(): BrowseManifest? {
        val caps = capabilitiesClient.current()
        val body = buildRequestBody(caps)
        val jsonBody = json.encodeToString(CatalogBrowseRequest.serializer(), body)

        val requestBuilder = Request.Builder()
            .url("$serverUrl/api/v2/catalog/browse")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", "octomil-android/1.0")

        cachedEtag?.let { etag ->
            requestBuilder.header("If-None-Match", etag)
        }

        return try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == 304) {
                    Timber.d("Catalog browse: 304 Not Modified, using cache")
                    return cachedManifest ?: loadDiskCache()
                }

                if (!response.isSuccessful) {
                    Timber.w("Catalog browse API returned ${response.code}")
                    return loadDiskCache()
                }

                val responseBody = response.body?.string() ?: return loadDiskCache()
                val families = json.decodeFromString<Map<String, BrowseFamily>>(responseBody)
                val manifest = BrowseManifest(families = families)

                // Update caches
                cachedManifest = manifest
                cachedEtag = response.header("ETag")
                writeDiskCache(responseBody)

                manifest
            }
        } catch (e: Exception) {
            Timber.w(e, "Catalog browse request failed, using disk cache")
            loadDiskCache()
        }
    }

    private fun buildRequestBody(caps: CapabilityProfile): CatalogBrowseRequest {
        return CatalogBrowseRequest(
            platform = caps.platform,
            totalMemoryMb = caps.memoryMb.toInt(),
            gpuAvailable = "gpu" in caps.accelerators,
            npuAvailable = caps.accelerators.any { it in setOf("npu", "nnapi", "vendor_npu") },
            supportedRuntimes = caps.availableRuntimes,
        )
    }

    private fun loadDiskCache(): BrowseManifest? {
        return try {
            val file = cacheFile()
            if (!file.exists()) return null
            val content = file.readText()
            val families = json.decodeFromString<Map<String, BrowseFamily>>(content)
            BrowseManifest(families = families)
        } catch (e: Exception) {
            Timber.w(e, "Failed to load catalog disk cache")
            null
        }
    }

    private fun writeDiskCache(content: String) {
        try {
            cacheFile().writeText(content)
        } catch (e: Exception) {
            Timber.w(e, "Failed to write catalog disk cache")
        }
    }

    private fun cacheFile(): File =
        File(context.cacheDir, "octomil_catalog_browse.json")

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@kotlinx.serialization.Serializable
internal data class CatalogBrowseRequest(
    val platform: String,
    val model: String? = null,
    val manufacturer: String? = null,
    @kotlinx.serialization.SerialName("cpu_architecture") val cpuArchitecture: String? = null,
    @kotlinx.serialization.SerialName("os_version") val osVersion: String? = null,
    @kotlinx.serialization.SerialName("total_memory_mb") val totalMemoryMb: Int? = null,
    @kotlinx.serialization.SerialName("gpu_available") val gpuAvailable: Boolean = false,
    @kotlinx.serialization.SerialName("npu_available") val npuAvailable: Boolean = false,
    @kotlinx.serialization.SerialName("supported_runtimes") val supportedRuntimes: List<String> = emptyList(),
)
