package ai.edgeml.secagg

import timber.log.Timber
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * X25519 Elliptic-Curve Diffie-Hellman key exchange, HKDF key derivation,
 * and AES-GCM share encryption for the SecAgg+ protocol.
 *
 * Uses the Android JCA provider's X25519 support (available via Conscrypt
 * on Android 8.0+). Falls back gracefully if the algorithm is not available.
 */
object ECDHKeyExchange {

    private const val KEY_ALGORITHM = "X25519"
    private const val KEY_AGREEMENT_ALGORITHM = "XDH"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH = 12
    private const val HKDF_HASH = "HmacSHA256"

    /**
     * An X25519 key pair for Diffie-Hellman key exchange.
     *
     * @property privateKeyBytes 32-byte raw private key.
     * @property publicKeyBytes 32-byte raw public key.
     */
    data class KeyPair(
        val privateKeyBytes: ByteArray,
        val publicKeyBytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KeyPair) return false
            return privateKeyBytes.contentEquals(other.privateKeyBytes) &&
                publicKeyBytes.contentEquals(other.publicKeyBytes)
        }

        override fun hashCode(): Int {
            var result = privateKeyBytes.contentHashCode()
            result = 31 * result + publicKeyBytes.contentHashCode()
            return result
        }
    }

    /**
     * Generate a fresh X25519 key pair.
     *
     * @return [KeyPair] with 32-byte private and public keys.
     * @throws SecAggException if X25519 is not available on this device.
     */
    fun generateKeyPair(): KeyPair {
        return try {
            val kpg = KeyPairGenerator.getInstance(KEY_ALGORITHM)
            val jcaKeyPair = kpg.generateKeyPair()

            // Extract raw key bytes
            val privBytes = jcaKeyPair.private.encoded
            val pubBytes = jcaKeyPair.public.encoded

            // JCA may return encoded keys (PKCS#8 / X.509). Extract raw 32 bytes.
            KeyPair(
                privateKeyBytes = extractRawPrivateKey(privBytes),
                publicKeyBytes = extractRawPublicKey(pubBytes),
            )
        } catch (e: Exception) {
            throw SecAggException(
                "X25519 key generation failed. Ensure your device supports X25519 (Android 8.0+): ${e.message}",
                e,
            )
        }
    }

    /**
     * Compute the ECDH shared secret from a local private key and a peer's
     * public key.
     *
     * @param myPrivateKeyBytes 32-byte raw private key (or PKCS#8 encoded).
     * @param peerPublicKeyBytes 32-byte raw public key (or X.509 encoded).
     * @return 32-byte shared secret.
     */
    fun computeSharedSecret(
        myPrivateKeyBytes: ByteArray,
        peerPublicKeyBytes: ByteArray,
    ): ByteArray {
        return try {
            val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)

            // Reconstruct JCA key objects from raw bytes
            val privateKey = keyFactory.generatePrivate(
                java.security.spec.PKCS8EncodedKeySpec(
                    wrapAsPKCS8(myPrivateKeyBytes),
                ),
            )
            val publicKey = keyFactory.generatePublic(
                java.security.spec.X509EncodedKeySpec(
                    wrapAsX509(peerPublicKeyBytes),
                ),
            )

            val keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM)
            keyAgreement.init(privateKey)
            keyAgreement.doPhase(publicKey, true)
            keyAgreement.generateSecret()
        } catch (e: Exception) {
            throw SecAggException("ECDH key agreement failed: ${e.message}", e)
        }
    }

    // =========================================================================
    // HKDF-SHA256 key derivation
    // =========================================================================

    /**
     * Derive key material from a shared secret using HKDF-SHA256.
     *
     * @param ikm Input keying material (e.g., ECDH shared secret).
     * @param length Desired output length in bytes.
     * @param info Context/application-specific info string.
     * @param salt Optional salt (defaults to zero-filled).
     * @return Derived key material of the requested length.
     */
    fun hkdfSHA256(
        ikm: ByteArray,
        length: Int,
        info: ByteArray,
        salt: ByteArray? = null,
    ): ByteArray {
        // HKDF-Extract
        val actualSalt = salt ?: ByteArray(32) // SHA-256 output length
        val prk = hmacSHA256(actualSalt, ikm)

        // HKDF-Expand
        val n = (length + 31) / 32 // ceil(length / hashLen)
        val okm = ByteArray(n * 32)
        var t = ByteArray(0)
        for (i in 1..n) {
            val input = t + info + byteArrayOf(i.toByte())
            t = hmacSHA256(prk, input)
            System.arraycopy(t, 0, okm, (i - 1) * 32, 32)
        }
        return okm.copyOf(length)
    }

    private fun hmacSHA256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HKDF_HASH)
        mac.init(SecretKeySpec(key, HKDF_HASH))
        return mac.doFinal(data)
    }

    // =========================================================================
    // Pairwise mask derivation
    // =========================================================================

    /**
     * Derive a pairwise mask vector from an ECDH shared secret.
     *
     * Uses HKDF-SHA256 to expand the shared secret, then splits into
     * 4-byte field elements (matching the Python SDK's `derive_pairwise_mask`).
     *
     * @param sharedSecret 32-byte ECDH shared secret.
     * @param count Number of mask elements needed.
     * @param modulus The finite-field modulus.
     * @param context Round-specific context bytes (e.g., round_id).
     * @return List of mask elements in [0, modulus).
     */
    fun derivePairwiseMask(
        sharedSecret: ByteArray,
        count: Int,
        modulus: BigInteger,
        context: ByteArray = byteArrayOf(),
    ): List<BigInteger> {
        val neededBytes = count * 4 // 4 bytes per element
        val info = "secagg-pairwise-mask".toByteArray(Charsets.UTF_8) + context
        val derived = hkdfSHA256(sharedSecret, neededBytes, info)

        val elements = ArrayList<BigInteger>(count)
        val bb = ByteBuffer.wrap(derived).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until count) {
            val value = bb.int.toLong() and 0xFFFFFFFFL
            elements.add(BigInteger.valueOf(value).mod(modulus))
        }
        return elements
    }

    // =========================================================================
    // AES-GCM encrypted share transport
    // =========================================================================

    /**
     * Encrypt a serialized Shamir share using AES-GCM with a key derived
     * from the ECDH shared secret.
     *
     * Format: [12-byte IV] [ciphertext + GCM tag]
     *
     * @param plaintext The share bytes to encrypt.
     * @param sharedSecret The ECDH shared secret.
     * @return Encrypted bytes (IV + ciphertext).
     */
    fun encryptShare(plaintext: ByteArray, sharedSecret: ByteArray): ByteArray {
        val aesKey = hkdfSHA256(
            ikm = sharedSecret,
            length = 32,
            info = "secagg-share-encryption".toByteArray(Charsets.UTF_8),
        )

        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val keySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext)

        // Prepend IV to ciphertext
        return iv + ciphertext
    }

    /**
     * Decrypt a share encrypted with [encryptShare].
     *
     * @param encrypted The encrypted bytes (IV + ciphertext).
     * @param sharedSecret The ECDH shared secret.
     * @return Decrypted plaintext bytes.
     */
    fun decryptShare(encrypted: ByteArray, sharedSecret: ByteArray): ByteArray {
        require(encrypted.size > GCM_IV_LENGTH) {
            "Encrypted data too short (${encrypted.size} bytes)"
        }

        val aesKey = hkdfSHA256(
            ikm = sharedSecret,
            length = 32,
            info = "secagg-share-encryption".toByteArray(Charsets.UTF_8),
        )

        val iv = encrypted.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encrypted.copyOfRange(GCM_IV_LENGTH, encrypted.size)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val keySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(ciphertext)
    }

    // =========================================================================
    // Raw key encoding helpers
    // =========================================================================

    /**
     * Extract 32-byte raw private key from PKCS#8 encoded key.
     * X25519 PKCS#8 encoding: ASN.1 header (16 bytes) + 04 20 + 32-byte key.
     */
    private fun extractRawPrivateKey(encoded: ByteArray): ByteArray {
        // If already 32 bytes, assume raw
        if (encoded.size == 32) return encoded
        // PKCS#8 X25519: last 32 bytes are the raw key
        // Structure: SEQUENCE { ... OCTET STRING { 04 20 <32 bytes> } }
        if (encoded.size >= 34) {
            // Look for 0x04 0x20 prefix before the 32-byte key
            for (i in 0 until encoded.size - 33) {
                if (encoded[i] == 0x04.toByte() && encoded[i + 1] == 0x20.toByte()) {
                    return encoded.copyOfRange(i + 2, i + 34)
                }
            }
        }
        // Fallback: take the last 32 bytes
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    /**
     * Extract 32-byte raw public key from X.509/SubjectPublicKeyInfo encoded key.
     * X25519 X.509: ASN.1 header (12 bytes) + 32-byte key.
     */
    private fun extractRawPublicKey(encoded: ByteArray): ByteArray {
        if (encoded.size == 32) return encoded
        // X.509 X25519: last 32 bytes are the raw key
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    /**
     * Wrap a 32-byte raw X25519 private key in PKCS#8 DER encoding.
     *
     * ASN.1 structure:
     * SEQUENCE {
     *   INTEGER 0
     *   SEQUENCE { OID 1.3.101.110 }
     *   OCTET STRING { OCTET STRING { <32 bytes> } }
     * }
     */
    private fun wrapAsPKCS8(rawKey: ByteArray): ByteArray {
        if (rawKey.size > 32) return rawKey // Assume already encoded

        // Pre-built ASN.1 header for X25519 PKCS#8
        val header = byteArrayOf(
            0x30, 0x2E, // SEQUENCE (46 bytes)
            0x02, 0x01, 0x00, // INTEGER 0 (version)
            0x30, 0x05, // SEQUENCE (5 bytes)
            0x06, 0x03, 0x2B, 0x65, 0x6E, // OID 1.3.101.110 (X25519)
            0x04, 0x22, // OCTET STRING (34 bytes)
            0x04, 0x20, // OCTET STRING (32 bytes) -- the actual key
        )
        return header + rawKey
    }

    /**
     * Wrap a 32-byte raw X25519 public key in X.509/SubjectPublicKeyInfo DER encoding.
     *
     * ASN.1 structure:
     * SEQUENCE {
     *   SEQUENCE { OID 1.3.101.110 }
     *   BIT STRING { <32 bytes> }
     * }
     */
    private fun wrapAsX509(rawKey: ByteArray): ByteArray {
        if (rawKey.size > 32) return rawKey // Assume already encoded

        // Pre-built ASN.1 header for X25519 SubjectPublicKeyInfo
        val header = byteArrayOf(
            0x30, 0x2A, // SEQUENCE (42 bytes)
            0x30, 0x05, // SEQUENCE (5 bytes)
            0x06, 0x03, 0x2B, 0x65, 0x6E, // OID 1.3.101.110 (X25519)
            0x03, 0x21, // BIT STRING (33 bytes)
            0x00, // no unused bits
        )
        return header + rawKey
    }
}
