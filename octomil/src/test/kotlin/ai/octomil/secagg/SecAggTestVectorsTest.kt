package ai.octomil.secagg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Validate SecAgg+ crypto primitives using runtime-generated test vectors.
 *
 * All expected values are computed by the actual implementations in this
 * module via [TestVectorGenerator]. No static JSON files are loaded.
 *
 * Covers: PRG, ECDH (symmetry), HKDF-SHA256, AES-GCM (encrypt/decrypt
 * round-trip), Shamir split/reconstruct, quantize/dequantize round-trip,
 * and pairwise mask direction convention.
 */
class SecAggTestVectorsTest {

    // =========================================================================
    // PRG (SHA-256 counter mode)
    // =========================================================================

    @Test
    fun `PRG output is deterministic for fixed seeds`() {
        val vectors = TestVectorGenerator.generatePrgVectors()

        for (vec in vectors) {
            // Re-run PRG with the same inputs and verify identical output
            val actual = SecAggPlusClient.pseudoRandGen(vec.seed, vec.modRange, vec.count)

            for (j in 0 until vec.count) {
                assertEquals(
                    "PRG mismatch at index $j (seed=${TestVectorGenerator.bytesToHex(vec.seed).take(16)}..., mod=${vec.modRange})",
                    vec.expected[j],
                    actual[j],
                )
            }
        }
    }

    @Test
    fun `PRG with small modulus produces values in range`() {
        val vectors = TestVectorGenerator.generatePrgVectors()
        val smallModVec = vectors.last() // mod=1000

        for (j in 0 until smallModVec.count) {
            val value = smallModVec.expected[j]
            assert(value in 0 until smallModVec.modRange) {
                "PRG value $value out of range [0, ${smallModVec.modRange})"
            }
        }
    }

    // =========================================================================
    // ECDH (X25519 key exchange symmetry)
    // =========================================================================

    @Test
    fun `ECDH shared secret is symmetric`() {
        val x25519Available = try {
            KeyPairGenerator.getInstance("X25519")
            true
        } catch (_: Exception) {
            false
        }
        assumeTrue("X25519 not available on this JVM", x25519Available)

        val vec = TestVectorGenerator.generateEcdhVector()
        assertNotNull("ECDH vector generation returned null despite X25519 being available", vec)
        vec!!

        // The core property: Alice and Bob derive the same shared secret
        assertArrayEquals(
            "ECDH shared secret must be symmetric (Alice->Bob == Bob->Alice)",
            vec.sharedSecretAlice,
            vec.sharedSecretBob,
        )

        // Shared secret should be 32 bytes
        assertEquals("Shared secret length", 32, vec.sharedSecretAlice.size)
    }

    // =========================================================================
    // HKDF-SHA256
    // =========================================================================

    @Test
    fun `HKDF-SHA256 output is deterministic`() {
        val vectors = TestVectorGenerator.generateHkdfVectors()

        for (vec in vectors) {
            // Re-derive and verify identical output
            val actual = ECDHKeyExchange.hkdfSHA256(
                vec.ikm, vec.length, vec.info.toByteArray(Charsets.UTF_8),
            )
            assertEquals(
                "HKDF mismatch for info='${vec.info}'",
                TestVectorGenerator.bytesToHex(vec.derived),
                TestVectorGenerator.bytesToHex(actual),
            )
        }
    }

    @Test
    fun `HKDF-SHA256 different info strings produce different keys`() {
        val vectors = TestVectorGenerator.generateHkdfVectors()
        assert(vectors.size >= 2) { "Need at least 2 HKDF vectors" }

        val hex0 = TestVectorGenerator.bytesToHex(vectors[0].derived)
        val hex1 = TestVectorGenerator.bytesToHex(vectors[1].derived)
        assert(hex0 != hex1) {
            "Different info strings must produce different derived keys"
        }
    }

    // =========================================================================
    // AES-GCM
    // =========================================================================

    @Test
    fun `AES-GCM encrypt then decrypt round-trips`() {
        val vec = TestVectorGenerator.generateAesGcmVector()

        // Decrypt the ciphertext and verify it matches the original plaintext
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(vec.key, "AES")
        val gcmSpec = GCMParameterSpec(128, vec.nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        val decrypted = cipher.doFinal(vec.ciphertext)

        assertArrayEquals(
            "AES-GCM decrypt(encrypt(plaintext)) must equal plaintext",
            vec.plaintext,
            decrypted,
        )
    }

    @Test
    fun `AES-GCM ciphertext is deterministic for fixed key and nonce`() {
        val vec = TestVectorGenerator.generateAesGcmVector()

        // Re-encrypt with same key/nonce/plaintext
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(vec.key, "AES")
        val gcmSpec = GCMParameterSpec(128, vec.nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val ciphertext2 = cipher.doFinal(vec.plaintext)

        assertArrayEquals(
            "AES-GCM must be deterministic with fixed key+nonce",
            vec.ciphertext,
            ciphertext2,
        )
    }

    // =========================================================================
    // Shamir secret sharing
    // =========================================================================

    @Test
    fun `Shamir prime is Mersenne-127`() {
        val expected = BigInteger.valueOf(2).pow(127).subtract(BigInteger.ONE)
        assertEquals(expected, ShamirSecretSharing.MERSENNE_127)
    }

    @Test
    fun `Shamir reconstruct from threshold shares recovers secret`() {
        val shamir = ShamirSecretSharing()
        val vectors = TestVectorGenerator.generateShamirVectors()

        for (vec in vectors) {
            // Take exactly threshold shares (first t)
            val subset = vec.shares.take(vec.threshold)
            val reconstructed = shamir.reconstruct(subset)
            assertEquals(
                "Shamir reconstruct from first ${vec.threshold} of ${vec.numShares} shares failed",
                vec.secret,
                reconstructed,
            )
        }
    }

    @Test
    fun `Shamir reconstruct from different share subsets`() {
        val shamir = ShamirSecretSharing()
        val vectors = TestVectorGenerator.generateShamirVectors()

        for (vec in vectors) {
            // Try multiple subsets of size threshold
            val subsets = listOf(
                vec.shares.take(vec.threshold),                         // first t
                vec.shares.takeLast(vec.threshold),                     // last t
                listOf(vec.shares[0], vec.shares[2], vec.shares[4])     // indices 1, 3, 5
                    .take(vec.threshold),
                vec.shares,                                             // all shares
            )

            for ((idx, subset) in subsets.withIndex()) {
                if (subset.size < vec.threshold) continue
                val reconstructed = shamir.reconstruct(subset)
                assertEquals(
                    "Shamir reconstruct failed for subset #$idx (size=${subset.size})",
                    vec.secret,
                    reconstructed,
                )
            }
        }
    }

    // =========================================================================
    // Quantization (deterministic round-trip)
    // =========================================================================

    @Test
    fun `quantize then dequantize round-trips within tolerance`() {
        val vec = TestVectorGenerator.generateQuantizationVector()

        // Verify the dequantized values match what the real implementation produces
        val actual = Quantization.dequantize(vec.quantized, vec.clippingRange, vec.targetRange)

        for (j in actual.indices) {
            assertEquals(
                "Dequantize mismatch at index $j (input float=${vec.floatValues[j]})",
                vec.dequantized[j].toDouble(),
                actual[j].toDouble(),
                1e-4,
            )
        }
    }

    @Test
    fun `quantized values are in valid range`() {
        val vec = TestVectorGenerator.generateQuantizationVector()

        for ((j, q) in vec.quantized.withIndex()) {
            assert(q in 0..vec.targetRange) {
                "Quantized value $q at index $j out of range [0, ${vec.targetRange}]"
            }
        }
    }

    // =========================================================================
    // Pairwise mask direction
    // =========================================================================

    @Test
    fun `pairwise mask direction convention`() {
        val vectors = TestVectorGenerator.generateMaskDirectionVectors()

        for (vec in vectors) {
            val direction = when {
                vec.nodeId > vec.peerId -> "ADD"
                vec.nodeId < vec.peerId -> "SUBTRACT"
                else -> "SKIP"
            }
            assertEquals(
                "Direction mismatch for node=${vec.nodeId}, peer=${vec.peerId}",
                vec.expectedDirection,
                direction,
            )
        }
    }
}
