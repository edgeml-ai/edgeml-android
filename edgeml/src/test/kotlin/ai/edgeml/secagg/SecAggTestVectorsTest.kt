package ai.edgeml.secagg

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator

/**
 * Validate SecAgg+ crypto primitives against the shared cross-platform
 * test vectors in `secagg_test_vectors.json`.
 *
 * Covers: PRG, Shamir reconstruction, HKDF-SHA256, dequantization,
 * and pairwise mask direction.
 */
class SecAggTestVectorsTest {

    private lateinit var vectors: JSONObject
    private var x25519Available = false

    @Before
    fun setUp() {
        val stream = javaClass.classLoader!!.getResourceAsStream("secagg_test_vectors.json")
            ?: error("secagg_test_vectors.json not found in test resources")
        vectors = JSONObject(stream.bufferedReader().readText())

        x25519Available = try {
            KeyPairGenerator.getInstance("X25519")
            true
        } catch (_: Exception) {
            false
        }
    }

    // =========================================================================
    // PRG (SHA-256 counter mode)
    // =========================================================================

    @Test
    fun `PRG matches test vectors`() {
        val cases = vectors.getJSONArray("prg")
        for (i in 0 until cases.length()) {
            val case_ = cases.getJSONObject(i)
            val seed = hexToBytes(case_.getString("seed_hex"))
            val modRange = case_.getLong("mod_range")
            val count = case_.getInt("count")
            val expected = case_.getJSONArray("expected")

            val actual = SecAggPlusClient.pseudoRandGen(seed, modRange, count)

            for (j in 0 until count) {
                assertEquals(
                    "PRG mismatch at index $j (seed=${case_.getString("seed_hex").take(16)}..., mod=$modRange)",
                    expected.getLong(j),
                    actual[j],
                )
            }
        }
    }

    // =========================================================================
    // Shamir reconstruction
    // =========================================================================

    @Test
    fun `Shamir prime matches`() {
        val expectedPrime = BigInteger(vectors.getJSONObject("metadata").getString("prime"))
        assertEquals(expectedPrime, ShamirSecretSharing.MERSENNE_127)
    }

    @Test
    fun `Shamir reconstruction from test vector shares`() {
        val shamir = ShamirSecretSharing()
        val cases = vectors.getJSONArray("shamir")

        for (i in 0 until cases.length()) {
            val case_ = cases.getJSONObject(i)
            val expectedSecret = BigInteger.valueOf(case_.getLong("secret"))
            val reconstructions = case_.getJSONArray("reconstructions")

            for (r in 0 until reconstructions.length()) {
                val recon = reconstructions.getJSONObject(r)
                val sharesHex = recon.getJSONArray("shares_hex")

                // Parse hex shares into ShamirSecretSharing.Share objects
                val shares = (0 until sharesHex.length()).map { s ->
                    val raw = hexToBytes(sharesHex.getString(s))
                    // First 4 bytes = index (big-endian), next 16 bytes = value
                    val index = ((raw[0].toInt() and 0xFF) shl 24) or
                        ((raw[1].toInt() and 0xFF) shl 16) or
                        ((raw[2].toInt() and 0xFF) shl 8) or
                        (raw[3].toInt() and 0xFF)
                    val value = BigInteger(1, raw.copyOfRange(4, 20))
                    ShamirSecretSharing.Share(index = index, value = value)
                }

                val reconstructed = shamir.reconstruct(shares)
                assertEquals(
                    "Shamir reconstruction failed for indices=${recon.getJSONArray("share_indices")}",
                    expectedSecret,
                    reconstructed,
                )
            }
        }
    }

    // =========================================================================
    // HKDF-SHA256
    // =========================================================================

    @Test
    fun `HKDF-SHA256 matches test vectors`() {
        val cases = vectors.getJSONArray("hkdf_sha256")
        for (i in 0 until cases.length()) {
            val case_ = cases.getJSONObject(i)
            val ikm = hexToBytes(case_.getString("ikm_hex"))
            val info = case_.getString("info").toByteArray(Charsets.UTF_8)
            val length = case_.getInt("length")
            val expectedHex = case_.getString("derived_hex")

            val derived = ECDHKeyExchange.hkdfSHA256(ikm, length, info)
            assertEquals(
                "HKDF mismatch for info='${case_.getString("info")}'",
                expectedHex,
                bytesToHex(derived),
            )
        }
    }

    // =========================================================================
    // Quantization (dequantize only -- quantize uses stochastic rounding)
    // =========================================================================

    @Test
    fun `dequantize matches test vectors`() {
        val cases = vectors.getJSONArray("quantization")
        for (i in 0 until cases.length()) {
            val case_ = cases.getJSONObject(i)
            val clip = case_.getDouble("clipping_range").toFloat()
            val target = case_.getLong("quantization_range")
            val quantized = jsonArrayToLongList(case_.getJSONArray("quantized"))
            val expectedDeq = jsonArrayToDoubleList(case_.getJSONArray("dequantized"))

            val actual = Quantization.dequantize(quantized, clip, target)

            for (j in actual.indices) {
                assertEquals(
                    "Dequantize mismatch at index $j",
                    expectedDeq[j],
                    actual[j].toDouble(),
                    1e-4, // float precision
                )
            }
        }
    }

    // =========================================================================
    // Pairwise mask direction
    // =========================================================================

    @Test
    fun `pairwise mask direction convention`() {
        val cases = vectors.getJSONArray("pairwise_mask_direction")
        for (i in 0 until cases.length()) {
            val case_ = cases.getJSONObject(i)
            val nodeId = case_.getInt("node_id")
            val peerId = case_.getInt("peer_id")
            val expected = case_.getString("direction")

            val direction = when {
                nodeId > peerId -> "ADD"
                nodeId < peerId -> "SUBTRACT"
                else -> "SKIP"
            }
            assertEquals(
                "Direction mismatch for node=$nodeId, peer=$peerId",
                expected,
                direction,
            )
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun jsonArrayToLongList(arr: JSONArray): List<Long> =
        (0 until arr.length()).map { arr.getLong(it) }

    private fun jsonArrayToDoubleList(arr: JSONArray): List<Double> =
        (0 until arr.length()).map { arr.getDouble(it) }
}
