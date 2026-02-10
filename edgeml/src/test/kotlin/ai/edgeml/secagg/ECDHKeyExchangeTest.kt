package ai.edgeml.secagg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator

class ECDHKeyExchangeTest {

    private var x25519Available = false

    @Before
    fun setUp() {
        // X25519 requires JDK 11+ or Android 8.0+ with Conscrypt.
        // Skip ECDH-specific tests if not available.
        x25519Available = try {
            KeyPairGenerator.getInstance("X25519")
            true
        } catch (e: Exception) {
            false
        }
    }

    // =========================================================================
    // HKDF-SHA256 (pure Java, always works)
    // =========================================================================

    @Test
    fun `hkdfSHA256 produces deterministic output`() {
        val ikm = ByteArray(32) { it.toByte() }
        val info = "test-info".toByteArray()

        val out1 = ECDHKeyExchange.hkdfSHA256(ikm, 32, info)
        val out2 = ECDHKeyExchange.hkdfSHA256(ikm, 32, info)

        assertArrayEquals(out1, out2)
    }

    @Test
    fun `hkdfSHA256 produces requested length`() {
        val ikm = ByteArray(32) { it.toByte() }
        val info = "test".toByteArray()

        for (length in listOf(16, 32, 48, 64, 128)) {
            val result = ECDHKeyExchange.hkdfSHA256(ikm, length, info)
            assertEquals("Output should be $length bytes", length, result.size)
        }
    }

    @Test
    fun `hkdfSHA256 different info produces different output`() {
        val ikm = ByteArray(32) { it.toByte() }

        val out1 = ECDHKeyExchange.hkdfSHA256(ikm, 32, "info-a".toByteArray())
        val out2 = ECDHKeyExchange.hkdfSHA256(ikm, 32, "info-b".toByteArray())

        assertTrue("Different info should yield different output",
            !out1.contentEquals(out2))
    }

    @Test
    fun `hkdfSHA256 different ikm produces different output`() {
        val ikm1 = ByteArray(32) { it.toByte() }
        val ikm2 = ByteArray(32) { (it + 1).toByte() }
        val info = "same".toByteArray()

        val out1 = ECDHKeyExchange.hkdfSHA256(ikm1, 32, info)
        val out2 = ECDHKeyExchange.hkdfSHA256(ikm2, 32, info)

        assertTrue(!out1.contentEquals(out2))
    }

    @Test
    fun `hkdfSHA256 with salt produces different output than without`() {
        val ikm = ByteArray(32) { it.toByte() }
        val info = "test".toByteArray()
        val salt = ByteArray(16) { (it * 7).toByte() }

        val withoutSalt = ECDHKeyExchange.hkdfSHA256(ikm, 32, info)
        val withSalt = ECDHKeyExchange.hkdfSHA256(ikm, 32, info, salt)

        assertTrue(!withoutSalt.contentEquals(withSalt))
    }

    @Test
    fun `hkdfSHA256 uses standardized info strings`() {
        val sharedSecret = ByteArray(32) { it.toByte() }

        // Verify the standardized info strings produce deterministic output
        val pairwiseKey = ECDHKeyExchange.hkdfSHA256(
            sharedSecret, 32, "secagg-pairwise-mask".toByteArray(Charsets.UTF_8),
        )
        val encryptionKey = ECDHKeyExchange.hkdfSHA256(
            sharedSecret, 32, "secagg-share-encryption".toByteArray(Charsets.UTF_8),
        )

        assertEquals(32, pairwiseKey.size)
        assertEquals(32, encryptionKey.size)
        // Different info strings produce different keys
        assertTrue(!pairwiseKey.contentEquals(encryptionKey))
    }

    // =========================================================================
    // Pairwise mask derivation (using mod_range modulus)
    // =========================================================================

    @Test
    fun `derivePairwiseMask produces correct count`() {
        val sharedSecret = ByteArray(32) { it.toByte() }
        val modRange = BigInteger.valueOf(1L shl 32)

        val mask = ECDHKeyExchange.derivePairwiseMask(sharedSecret, 100, modRange)
        assertEquals(100, mask.size)
    }

    @Test
    fun `derivePairwiseMask elements are in mod_range`() {
        val sharedSecret = ByteArray(32) { it.toByte() }
        val modRange = BigInteger.valueOf(1L shl 32)

        val mask = ECDHKeyExchange.derivePairwiseMask(sharedSecret, 50, modRange)
        for (elem in mask) {
            assertTrue("Element should be >= 0", elem >= BigInteger.ZERO)
            assertTrue("Element should be < mod_range", elem < modRange)
        }
    }

    @Test
    fun `derivePairwiseMask is deterministic`() {
        val sharedSecret = ByteArray(32) { (it * 3).toByte() }
        val modRange = BigInteger.valueOf(1L shl 32)

        val mask1 = ECDHKeyExchange.derivePairwiseMask(sharedSecret, 20, modRange)
        val mask2 = ECDHKeyExchange.derivePairwiseMask(sharedSecret, 20, modRange)

        assertEquals(mask1, mask2)
    }

    @Test
    fun `derivePairwiseMask different context produces different masks`() {
        val sharedSecret = ByteArray(32) { it.toByte() }
        val modRange = BigInteger.valueOf(1L shl 32)

        val mask1 = ECDHKeyExchange.derivePairwiseMask(sharedSecret, 10, modRange, "round-1".toByteArray())
        val mask2 = ECDHKeyExchange.derivePairwiseMask(sharedSecret, 10, modRange, "round-2".toByteArray())

        assertTrue("Different context should yield different masks", mask1 != mask2)
    }

    @Test
    fun `derivePairwiseMask different secrets produce different masks`() {
        val secret1 = ByteArray(32) { it.toByte() }
        val secret2 = ByteArray(32) { (it + 1).toByte() }
        val modRange = BigInteger.valueOf(1L shl 32)

        val mask1 = ECDHKeyExchange.derivePairwiseMask(secret1, 10, modRange)
        val mask2 = ECDHKeyExchange.derivePairwiseMask(secret2, 10, modRange)

        assertTrue(mask1 != mask2)
    }

    // =========================================================================
    // AES-GCM encryption (pure Java)
    // =========================================================================

    @Test
    fun `encryptShare and decryptShare roundtrip`() {
        val plaintext = "hello, secagg shares!".toByteArray()
        val sharedSecret = ByteArray(32) { (it * 5).toByte() }

        val encrypted = ECDHKeyExchange.encryptShare(plaintext, sharedSecret)
        val decrypted = ECDHKeyExchange.decryptShare(encrypted, sharedSecret)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypted output is longer than plaintext`() {
        val plaintext = ByteArray(24) { it.toByte() }
        val sharedSecret = ByteArray(32) { it.toByte() }

        val encrypted = ECDHKeyExchange.encryptShare(plaintext, sharedSecret)

        // Should include 12-byte IV + ciphertext + 16-byte GCM tag
        assertTrue(encrypted.size > plaintext.size)
        assertEquals(12 + plaintext.size + 16, encrypted.size)
    }

    @Test
    fun `different encryptions of same plaintext produce different ciphertext`() {
        val plaintext = ByteArray(16) { it.toByte() }
        val sharedSecret = ByteArray(32) { it.toByte() }

        val enc1 = ECDHKeyExchange.encryptShare(plaintext, sharedSecret)
        val enc2 = ECDHKeyExchange.encryptShare(plaintext, sharedSecret)

        // Random IV should make ciphertexts different
        assertTrue(!enc1.contentEquals(enc2))
    }

    @Test(expected = Exception::class)
    fun `decryptShare fails with wrong secret`() {
        val plaintext = ByteArray(16) { it.toByte() }
        val secret1 = ByteArray(32) { it.toByte() }
        val secret2 = ByteArray(32) { (it + 1).toByte() }

        val encrypted = ECDHKeyExchange.encryptShare(plaintext, secret1)
        ECDHKeyExchange.decryptShare(encrypted, secret2)
    }

    @Test(expected = Exception::class)
    fun `decryptShare fails with tampered ciphertext`() {
        val plaintext = ByteArray(16) { it.toByte() }
        val sharedSecret = ByteArray(32) { it.toByte() }

        val encrypted = ECDHKeyExchange.encryptShare(plaintext, sharedSecret)
        // Tamper with ciphertext (not IV)
        encrypted[15] = (encrypted[15].toInt() xor 0xFF).toByte()
        ECDHKeyExchange.decryptShare(encrypted, sharedSecret)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decryptShare rejects too-short input`() {
        val sharedSecret = ByteArray(32) { it.toByte() }
        ECDHKeyExchange.decryptShare(ByteArray(5), sharedSecret)
    }

    @Test
    fun `encryptShare handles empty plaintext`() {
        val sharedSecret = ByteArray(32) { it.toByte() }

        val encrypted = ECDHKeyExchange.encryptShare(ByteArray(0), sharedSecret)
        val decrypted = ECDHKeyExchange.decryptShare(encrypted, sharedSecret)

        assertEquals(0, decrypted.size)
    }

    @Test
    fun `encryptShare handles large plaintext`() {
        val plaintext = ByteArray(4096) { (it % 256).toByte() }
        val sharedSecret = ByteArray(32) { it.toByte() }

        val encrypted = ECDHKeyExchange.encryptShare(plaintext, sharedSecret)
        val decrypted = ECDHKeyExchange.decryptShare(encrypted, sharedSecret)

        assertArrayEquals(plaintext, decrypted)
    }

    // =========================================================================
    // X25519 ECDH (requires JDK 11+)
    // =========================================================================

    @Test
    fun `generateKeyPair produces non-null key pair`() {
        assumeTrue("X25519 not available", x25519Available)

        val kp = ECDHKeyExchange.generateKeyPair()
        assertNotNull(kp.privateKeyBytes)
        assertNotNull(kp.publicKeyBytes)
        assertTrue(kp.privateKeyBytes.isNotEmpty())
        assertTrue(kp.publicKeyBytes.isNotEmpty())
    }

    @Test
    fun `generateKeyPair produces different keys each time`() {
        assumeTrue("X25519 not available", x25519Available)

        val kp1 = ECDHKeyExchange.generateKeyPair()
        val kp2 = ECDHKeyExchange.generateKeyPair()

        assertTrue(!kp1.publicKeyBytes.contentEquals(kp2.publicKeyBytes))
    }

    @Test
    fun `computeSharedSecret is symmetric`() {
        assumeTrue("X25519 not available", x25519Available)

        val kp1 = ECDHKeyExchange.generateKeyPair()
        val kp2 = ECDHKeyExchange.generateKeyPair()

        val secret1 = ECDHKeyExchange.computeSharedSecret(kp1.privateKeyBytes, kp2.publicKeyBytes)
        val secret2 = ECDHKeyExchange.computeSharedSecret(kp2.privateKeyBytes, kp1.publicKeyBytes)

        assertArrayEquals("Shared secrets should be identical", secret1, secret2)
    }

    @Test
    fun `computeSharedSecret produces 32 bytes`() {
        assumeTrue("X25519 not available", x25519Available)

        val kp1 = ECDHKeyExchange.generateKeyPair()
        val kp2 = ECDHKeyExchange.generateKeyPair()

        val secret = ECDHKeyExchange.computeSharedSecret(kp1.privateKeyBytes, kp2.publicKeyBytes)
        assertEquals(32, secret.size)
    }

    @Test
    fun `different key pairs produce different shared secrets`() {
        assumeTrue("X25519 not available", x25519Available)

        val kp1 = ECDHKeyExchange.generateKeyPair()
        val kp2 = ECDHKeyExchange.generateKeyPair()
        val kp3 = ECDHKeyExchange.generateKeyPair()

        val secret12 = ECDHKeyExchange.computeSharedSecret(kp1.privateKeyBytes, kp2.publicKeyBytes)
        val secret13 = ECDHKeyExchange.computeSharedSecret(kp1.privateKeyBytes, kp3.publicKeyBytes)

        assertTrue(!secret12.contentEquals(secret13))
    }
}
