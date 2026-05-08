// SDK environment profile resolution — staging vs production vs dev.
//
// A *profile* names a deployment environment of the Octomil control
// plane. This module is the single source of truth in the Android
// SDK for:
//
// - which base URL the SDK talks to by default,
// - which cache namespace planner / capability results are stored
//   under,
// - which model artifact bucket the SDK expects presigned URLs to
//   point at.
//
// Profiles let the same SDK build talk to staging or production
// without risk of cross-contamination — production cached planner
// decisions never leak into staging runs and vice-versa, because
// the cache key is namespaced by profile.
//
// Resolution order (first non-empty wins):
//
//   1. Explicit `profile` argument.
//   2. `OCTOMIL_PROFILE` from System.getenv (or supplied env map for
//      tests). Accepts `staging`, `production`, `dev`, or aliases
//      `prod`/`stg`.
//   3. Heuristic: if `OCTOMIL_API_BASE` / `OCTOMIL_API_URL` host
//      matches a known profile marker, infer that profile.
//   4. Default `Production`.
//
// The values here are duplicated from
// `octomil-contracts/fixtures/core/environment_capability_manifest.json`;
// once the contracts package is published as a Kotlin module the SDK
// will import the canonical loader. Until then, **any change to the
// profile→base_url mapping here MUST be mirrored in the contracts
// manifest, the Python SDK, the Node SDK, the browser SDK, and the
// iOS SDK** or the promotion gate detects drift.
//
// Mirrors `octomil-ios/Sources/Octomil/Client/Profile.swift` shape
// and resolution order — keep them in lockstep.

package ai.octomil.config

import java.net.URI
import java.net.URISyntaxException

/** Named SDK environment profiles. */
enum class OctomilProfile(val rawValue: String) {
    Production("production"),
    Staging("staging"),
    Dev("dev");

    companion object {
        private val ALIASES = mapOf(
            "prod" to "production",
            "stg" to "staging",
            "staging-2" to "staging",
        )

        /**
         * Case-insensitive lookup with helpful error.
         *
         * Accepts `prod` / `stg` aliases that operators commonly type.
         */
        @JvmStatic
        fun fromString(raw: String): OctomilProfile {
            require(raw.trim().isNotEmpty()) { "profile name must be non-empty" }
            val normalized = raw.trim().lowercase()
            val resolved = ALIASES[normalized] ?: normalized
            return values().firstOrNull { it.rawValue == resolved }
                ?: throw IllegalArgumentException(
                    "unknown profile '$raw'; valid: ${values().joinToString(", ") { it.rawValue }}"
                )
        }
    }
}

/**
 * The result of resolving a profile, with provenance.
 *
 * `source` tells the caller HOW the profile was picked — useful for
 * logging when the SDK boots so operators can verify the right path
 * was taken (vs. silently defaulting to production).
 */
data class OctomilProfileResolution(
    val profile: OctomilProfile,
    val source: Source,
) {
    enum class Source(val rawValue: String) {
        Explicit("explicit"),
        Env("env"),
        UrlInferred("url_inferred"),
        Default("default"),
    }
}

/** Static helpers for profile-aware URL/bucket/namespace resolution. */
object OctomilProfileResolver {
    // Source of truth for SDK base URLs per profile. Mirrors
    // environment_capability_manifest.json.
    //
    // Two URL forms exposed: host-only (composes its own /api/...
    // paths) and /v1-suffixed (older clients that prepend /v1).
    private val HOST_URLS = mapOf(
        OctomilProfile.Production to "https://api.octomil.com",
        OctomilProfile.Staging to "https://api.staging.octomil.com",
        OctomilProfile.Dev to "http://localhost:8000",
    )

    private val V1_URLS = mapOf(
        OctomilProfile.Production to "https://api.octomil.com/v1",
        OctomilProfile.Staging to "https://api.staging.octomil.com/v1",
        OctomilProfile.Dev to "http://localhost:8000/v1",
    )

    private val ARTIFACT_BUCKETS = mapOf(
        OctomilProfile.Production to "octomil-models",
        OctomilProfile.Staging to "octomil-models-staging",
        OctomilProfile.Dev to "octomil-models-dev",
    )

    // Exact-host markers used for URL inference. Match is against the
    // *parsed hostname*, never a substring of the raw URL — a hostile
    // URL like https://evil.test/?next=api.staging.octomil.com or
    // api.octomil.com.evil.test MUST NOT spoof a profile.
    private val HOST_INFERENCE_MARKERS: List<Pair<OctomilProfile, Set<String>>> = listOf(
        OctomilProfile.Staging to setOf("api.staging.octomil.com"),
        OctomilProfile.Production to setOf("api.octomil.com"),
        OctomilProfile.Dev to setOf("localhost", "127.0.0.1", "0.0.0.0"),
    )

    /** Canonical SDK host URL for the given profile (no /v1 suffix). */
    @JvmStatic
    fun hostUrl(profile: OctomilProfile): String =
        HOST_URLS.getValue(profile)

    /** Canonical SDK base URL with /v1 suffix. */
    @JvmStatic
    fun baseUrlV1(profile: OctomilProfile): String =
        V1_URLS.getValue(profile)

    /** Canonical R2 bucket for model artifacts in the given profile. */
    @JvmStatic
    fun artifactBucket(profile: OctomilProfile): String =
        ARTIFACT_BUCKETS.getValue(profile)

    /**
     * Cache key prefix for planner/capability caches — prevents
     * cross-environment cache poisoning.
     */
    @JvmStatic
    fun cacheNamespace(profile: OctomilProfile): String =
        "oct.${profile.rawValue}"

    /**
     * Resolve the active SDK profile.
     *
     * @param explicit Explicit profile name. Wins over env / URL inference.
     * @param environment Map with OCTOMIL_PROFILE / OCTOMIL_API_BASE /
     *   OCTOMIL_API_URL keys. Defaults to System.getenv() snapshot.
     *   Tests inject a custom map to avoid global state.
     */
    @JvmStatic
    @JvmOverloads
    fun resolveProfile(
        explicit: String? = null,
        environment: Map<String, String>? = null,
    ): OctomilProfileResolution {
        val env = environment ?: System.getenv()

        // 1. Explicit argument wins.
        explicit?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return OctomilProfileResolution(
                OctomilProfile.fromString(it),
                OctomilProfileResolution.Source.Explicit,
            )
        }

        // 2. OCTOMIL_PROFILE env var.
        val rawEnv = (env["OCTOMIL_PROFILE"] ?: "").trim()
        if (rawEnv.isNotEmpty()) {
            return OctomilProfileResolution(
                OctomilProfile.fromString(rawEnv),
                OctomilProfileResolution.Source.Env,
            )
        }

        // 3. URL inference. Trim BEFORE selecting so a whitespace
        //    OCTOMIL_API_BASE doesn't mask a valid OCTOMIL_API_URL
        //    (codex post-debate N1).
        val baseTrimmed = (env["OCTOMIL_API_BASE"] ?: "").trim()
        val urlTrimmed = (env["OCTOMIL_API_URL"] ?: "").trim()
        val explicitUrl = baseTrimmed.ifEmpty { urlTrimmed }
        inferFromUrl(explicitUrl)?.let {
            return OctomilProfileResolution(
                it,
                OctomilProfileResolution.Source.UrlInferred,
            )
        }

        // 4. Default.
        return OctomilProfileResolution(
            OctomilProfile.Production,
            OctomilProfileResolution.Source.Default,
        )
    }

    /**
     * Pick the host-only base URL the SDK should talk to.
     *
     * An explicit `baseUrl` wins over profile resolution (back-compat
     * for SDK users with custom URLs).
     */
    @JvmStatic
    @JvmOverloads
    fun resolveHostUrl(
        baseUrl: String? = null,
        explicit: String? = null,
        environment: Map<String, String>? = null,
    ): String {
        baseUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return hostUrl(resolveProfile(explicit, environment).profile)
    }

    /** With /v1 suffix. */
    @JvmStatic
    @JvmOverloads
    fun resolveBaseUrlV1(
        baseUrl: String? = null,
        explicit: String? = null,
        environment: Map<String, String>? = null,
    ): String {
        baseUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return baseUrlV1(resolveProfile(explicit, environment).profile)
    }

    private fun inferFromUrl(raw: String): OctomilProfile? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Use URI to parse; substring matching the raw URL would let
        // evil.test/?next=api.staging.octomil.com or
        // api.octomil.com.evil.test spoof a profile (codex post-
        // debate B1).
        val host = try {
            URI(trimmed).host?.lowercase()
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (host.isNullOrEmpty()) return null
        for ((profile, markers) in HOST_INFERENCE_MARKERS) {
            if (host in markers) return profile
        }
        return null
    }
}
