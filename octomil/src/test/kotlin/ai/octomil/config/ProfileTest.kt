// Tests for OctomilProfile / OctomilProfileResolver.
//
// Mirrors octomil-python/tests/test_config_profile.py,
// octomil-node/tests/profile.test.ts,
// octomil-browser/tests/profile.test.ts, and
// octomil-ios/Tests/OctomilTests/ProfileTests.swift —
// keep them in lockstep.

package ai.octomil.config

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileTest {

    // ── Profile rawValue ────────────────────────────────────────────

    @Test
    fun `rawValues match canonical names used in env_capability_manifest`() {
        assertEquals("production", OctomilProfile.Production.rawValue)
        assertEquals("staging", OctomilProfile.Staging.rawValue)
        assertEquals("dev", OctomilProfile.Dev.rawValue)
    }

    // ── fromString ──────────────────────────────────────────────────

    @Test
    fun `fromString accepts canonical names`() {
        assertEquals(OctomilProfile.Production, OctomilProfile.fromString("production"))
        assertEquals(OctomilProfile.Staging, OctomilProfile.fromString("staging"))
        assertEquals(OctomilProfile.Dev, OctomilProfile.fromString("dev"))
    }

    @Test
    fun `fromString is case-insensitive`() {
        assertEquals(OctomilProfile.Staging, OctomilProfile.fromString("STAGING"))
        assertEquals(OctomilProfile.Staging, OctomilProfile.fromString("Staging"))
    }

    @Test
    fun `fromString accepts aliases`() {
        assertEquals(OctomilProfile.Production, OctomilProfile.fromString("prod"))
        assertEquals(OctomilProfile.Staging, OctomilProfile.fromString("stg"))
    }

    @Test
    fun `fromString rejects unknown profile`() {
        val e = assertFailsWith<IllegalArgumentException> {
            OctomilProfile.fromString("preview")
        }
        assertTrue(e.message!!.contains("unknown profile"))
    }

    @Test
    fun `fromString rejects empty string`() {
        val e = assertFailsWith<IllegalArgumentException> {
            OctomilProfile.fromString("")
        }
        assertTrue(e.message!!.contains("non-empty"))
    }

    // ── URL forms ───────────────────────────────────────────────────

    @Test
    fun `host production does not include 'staging'`() {
        // Critical safety pin — if production ever drifts to a
        // staging-shaped URL, the SDK silently routes prod traffic to
        // staging.
        val url = OctomilProfileResolver.hostUrl(OctomilProfile.Production)
        assertFalse(url.contains("staging"))
        assertEquals("https://api.octomil.com", url)
    }

    @Test
    fun `host staging is distinct from production`() {
        val staging = OctomilProfileResolver.hostUrl(OctomilProfile.Staging)
        val production = OctomilProfileResolver.hostUrl(OctomilProfile.Production)
        assertEquals("https://api.staging.octomil.com", staging)
        assertTrue(staging != production)
    }

    @Test
    fun `v1 form suffixes each profile`() {
        assertEquals(
            "https://api.octomil.com/v1",
            OctomilProfileResolver.baseUrlV1(OctomilProfile.Production),
        )
        assertEquals(
            "https://api.staging.octomil.com/v1",
            OctomilProfileResolver.baseUrlV1(OctomilProfile.Staging),
        )
    }

    @Test
    fun `dev URL is localhost-shaped`() {
        assertTrue(
            OctomilProfileResolver.hostUrl(OctomilProfile.Dev).startsWith("http://localhost")
        )
    }

    // ── artifact buckets ────────────────────────────────────────────

    @Test
    fun `artifact buckets are distinct per profile`() {
        val buckets = OctomilProfile.values().map { OctomilProfileResolver.artifactBucket(it) }.toSet()
        assertEquals(3, buckets.size)
        assertEquals("octomil-models", OctomilProfileResolver.artifactBucket(OctomilProfile.Production))
        assertEquals(
            "octomil-models-staging",
            OctomilProfileResolver.artifactBucket(OctomilProfile.Staging),
        )
    }

    @Test
    fun `staging artifact bucket name does not contain 'prod'`() {
        val bucket = OctomilProfileResolver.artifactBucket(OctomilProfile.Staging).lowercase()
        assertFalse(bucket.contains("prod"))
    }

    // ── cache namespaces ────────────────────────────────────────────

    @Test
    fun `cache namespace embeds profile name`() {
        assertEquals("oct.production", OctomilProfileResolver.cacheNamespace(OctomilProfile.Production))
        assertEquals("oct.staging", OctomilProfileResolver.cacheNamespace(OctomilProfile.Staging))
        assertEquals("oct.dev", OctomilProfileResolver.cacheNamespace(OctomilProfile.Dev))
    }

    @Test
    fun `no two profiles share namespace`() {
        val ns = OctomilProfile.values().map { OctomilProfileResolver.cacheNamespace(it) }.toSet()
        assertEquals(OctomilProfile.values().size, ns.size)
    }

    // ── resolveProfile — explicit ──────────────────────────────────

    @Test
    fun `explicit arg wins over env`() {
        val res = OctomilProfileResolver.resolveProfile(
            explicit = "staging",
            environment = mapOf("OCTOMIL_PROFILE" to "production"),
        )
        assertEquals(OctomilProfile.Staging, res.profile)
        assertEquals(OctomilProfileResolution.Source.Explicit, res.source)
    }

    @Test
    fun `explicit alias resolves`() {
        val res = OctomilProfileResolver.resolveProfile(
            explicit = "prod",
            environment = emptyMap(),
        )
        assertEquals(OctomilProfile.Production, res.profile)
    }

    @Test
    fun `explicit invalid throws`() {
        assertFailsWith<IllegalArgumentException> {
            OctomilProfileResolver.resolveProfile(explicit = "preview", environment = emptyMap())
        }
    }

    @Test
    fun `whitespace explicit falls through to env`() {
        val res = OctomilProfileResolver.resolveProfile(
            explicit = "  ",
            environment = mapOf("OCTOMIL_PROFILE" to "staging"),
        )
        assertEquals(OctomilProfileResolution.Source.Env, res.source)
    }

    // ── resolveProfile — env ───────────────────────────────────────

    @Test
    fun `env picks staging`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf("OCTOMIL_PROFILE" to "staging"),
        )
        assertEquals(OctomilProfile.Staging, res.profile)
        assertEquals(OctomilProfileResolution.Source.Env, res.source)
    }

    @Test
    fun `empty OCTOMIL_PROFILE falls through`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf("OCTOMIL_PROFILE" to ""),
        )
        assertEquals(OctomilProfile.Production, res.profile)
        assertEquals(OctomilProfileResolution.Source.Default, res.source)
    }

    @Test
    fun `OCTOMIL_PROFILE case-insensitive`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf("OCTOMIL_PROFILE" to "STAGING"),
        )
        assertEquals(OctomilProfile.Staging, res.profile)
    }

    // ── resolveProfile — URL inference ─────────────────────────────

    @Test
    fun `infers staging from OCTOMIL_API_BASE`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf("OCTOMIL_API_BASE" to "https://api.staging.octomil.com/v1"),
        )
        assertEquals(OctomilProfile.Staging, res.profile)
        assertEquals(OctomilProfileResolution.Source.UrlInferred, res.source)
    }

    @Test
    fun `infers production from OCTOMIL_API_URL`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf("OCTOMIL_API_URL" to "https://api.octomil.com"),
        )
        assertEquals(OctomilProfile.Production, res.profile)
        assertEquals(OctomilProfileResolution.Source.UrlInferred, res.source)
    }

    @Test
    fun `infers dev from localhost`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf("OCTOMIL_API_BASE" to "http://localhost:8000"),
        )
        assertEquals(OctomilProfile.Dev, res.profile)
    }

    @Test
    fun `infers dev from 127`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf("OCTOMIL_API_BASE" to "http://127.0.0.1:8000"),
        )
        assertEquals(OctomilProfile.Dev, res.profile)
    }

    @Test
    fun `env profile overrides URL inference`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf(
                "OCTOMIL_PROFILE" to "staging",
                "OCTOMIL_API_BASE" to "https://api.octomil.com",
            ),
        )
        assertEquals(OctomilProfile.Staging, res.profile)
        assertEquals(OctomilProfileResolution.Source.Env, res.source)
    }

    @Test
    fun `unmatched URL falls through to default`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf("OCTOMIL_API_BASE" to "https://example.com/api"),
        )
        assertEquals(OctomilProfile.Production, res.profile)
        assertEquals(OctomilProfileResolution.Source.Default, res.source)
    }

    // ── default ─────────────────────────────────────────────────────

    @Test
    fun `no signals defaults to production`() {
        val res = OctomilProfileResolver.resolveProfile(environment = emptyMap())
        assertEquals(OctomilProfile.Production, res.profile)
        assertEquals(OctomilProfileResolution.Source.Default, res.source)
    }

    // ── resolveHostUrl / resolveBaseUrlV1 ──────────────────────────

    @Test
    fun `resolveHostUrl explicit baseUrl wins`() {
        val url = OctomilProfileResolver.resolveHostUrl(
            baseUrl = "https://custom.example.com",
            environment = mapOf("OCTOMIL_PROFILE" to "staging"),
        )
        assertEquals("https://custom.example.com", url)
    }

    @Test
    fun `resolveHostUrl staging profile`() {
        val url = OctomilProfileResolver.resolveHostUrl(
            environment = mapOf("OCTOMIL_PROFILE" to "staging"),
        )
        assertEquals("https://api.staging.octomil.com", url)
    }

    @Test
    fun `resolveBaseUrlV1 staging profile`() {
        val url = OctomilProfileResolver.resolveBaseUrlV1(
            environment = mapOf("OCTOMIL_PROFILE" to "staging"),
        )
        assertEquals("https://api.staging.octomil.com/v1", url)
    }

    @Test
    fun `resolveDefault returns production URL`() {
        assertEquals(
            "https://api.octomil.com",
            OctomilProfileResolver.resolveHostUrl(environment = emptyMap()),
        )
        assertEquals(
            "https://api.octomil.com/v1",
            OctomilProfileResolver.resolveBaseUrlV1(environment = emptyMap()),
        )
    }

    // ── cross-profile isolation ────────────────────────────────────

    @Test
    fun `no two profiles share host URL`() {
        val urls = OctomilProfile.values().map { OctomilProfileResolver.hostUrl(it) }.toSet()
        assertEquals(OctomilProfile.values().size, urls.size)
    }

    // ── Hostile-URL inference safety (codex post-debate B1) ────────

    @Test
    fun `marker in query string does not spoof profile`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf("OCTOMIL_API_BASE" to "https://evil.test/?next=api.staging.octomil.com"),
        )
        assertEquals(OctomilProfile.Production, res.profile)
        assertEquals(OctomilProfileResolution.Source.Default, res.source)
    }

    @Test
    fun `marker in path does not spoof profile`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf("OCTOMIL_API_BASE" to "https://evil.test/api.octomil.com/v1"),
        )
        assertEquals(OctomilProfile.Production, res.profile)
    }

    @Test
    fun `marker in userinfo does not spoof profile`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf("OCTOMIL_API_BASE" to "https://api.staging.octomil.com@evil.test/v1"),
        )
        // URI.host is evil.test, not api.staging.octomil.com.
        assertEquals(OctomilProfile.Production, res.profile)
    }

    @Test
    fun `superdomain does not spoof production`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf("OCTOMIL_API_BASE" to "https://api.octomil.com.evil.test/v1"),
        )
        assertEquals(OctomilProfile.Production, res.profile)
        assertEquals(OctomilProfileResolution.Source.Default, res.source)
    }

    @Test
    fun `unparseable URL falls through safely`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf("OCTOMIL_API_BASE" to "not a url"),
        )
        assertEquals(OctomilProfile.Production, res.profile)
    }

    // ── Whitespace fallback (codex post-debate N1) ─────────────────

    @Test
    fun `whitespace API_BASE falls back to API_URL`() {
        val res = OctomilProfileResolver.resolveProfile(
            environment = mapOf(
                "OCTOMIL_API_BASE" to "   ",
                "OCTOMIL_API_URL" to "https://api.staging.octomil.com",
            ),
        )
        assertEquals(OctomilProfile.Staging, res.profile)
        assertEquals(OctomilProfileResolution.Source.UrlInferred, res.source)
    }
}
