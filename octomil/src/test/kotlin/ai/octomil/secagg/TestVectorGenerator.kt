package ai.octomil.secagg

import java.math.BigInteger
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Generates SecAgg+ test vectors at runtime using the actual crypto
 * implementations. No hardcoded expected outputs -- everything is derived
 * from the source-of-truth implementations in this module.
 *
 * Fixed inputs (seeds, IKM, nonces, float values) are defined here.
 * Expected outputs are computed by calling the real code.
 */
object TestVectorGenerator {

    // =========================================================================
    // Hex helpers
    // =========================================================================

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    // =========================================================================
    // PRG vectors
    // =========================================================================

    data class PrgVector(
        val seed: ByteArray,
        val modRange: Long,
        val count: Int,
        /** Computed at runtime by [SecAggPlusClient.pseudoRandGen]. */
        val expected: LongArray,
    )

    /** Fixed seeds for PRG tests. */
    private val PRG_SEED_A = hexToBytes(
        "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
    )
    private val PRG_SEED_B = hexToBytes(
        "cafebabecafebabecafebabecafebabecafebabecafebabecafebabecafebabe"
    )

    fun generatePrgVectors(): List<PrgVector> {
        val modDefault = SecAggPlusConfig.DEFAULT_MOD_RANGE // 2^32
        val modSmall = 1000L
        val count = 20
        val countSmall = 10

        return listOf(
            PrgVector(
                seed = PRG_SEED_A,
                modRange = modDefault,
                count = count,
                expected = SecAggPlusClient.pseudoRandGen(PRG_SEED_A, modDefault, count),
            ),
            PrgVector(
                seed = PRG_SEED_B,
                modRange = modDefault,
                count = count,
                expected = SecAggPlusClient.pseudoRandGen(PRG_SEED_B, modDefault, count),
            ),
            PrgVector(
                seed = PRG_SEED_A,
                modRange = modSmall,
                count = countSmall,
                expected = SecAggPlusClient.pseudoRandGen(PRG_SEED_A, modSmall, countSmall),
            ),
        )
    }

    // =========================================================================
    // ECDH vectors
    // =========================================================================

    data class EcdhVector(
        val alicePublicKey: ByteArray,
        val alicePrivateKey: ByteArray,
        val bobPublicKey: ByteArray,
        val bobPrivateKey: ByteArray,
        val sharedSecretAlice: ByteArray,
        val sharedSecretBob: ByteArray,
    )

    /**
     * Generates an ECDH test vector by creating two real key pairs and
     * computing the shared secret from both sides. The test asserts symmetry.
     *
     * Returns null if X25519 is not available on this JVM.
     */
    fun generateEcdhVector(): EcdhVector? {
        return try {
            KeyPairGenerator.getInstance("X25519") // availability check
            val alice = ECDHKeyExchange.generateKeyPair()
            val bob = ECDHKeyExchange.generateKeyPair()
            val secretAB = ECDHKeyExchange.computeSharedSecret(
                alice.privateKeyBytes, bob.publicKeyBytes,
            )
            val secretBA = ECDHKeyExchange.computeSharedSecret(
                bob.privateKeyBytes, alice.publicKeyBytes,
            )
            EcdhVector(
                alicePublicKey = alice.publicKeyBytes,
                alicePrivateKey = alice.privateKeyBytes,
                bobPublicKey = bob.publicKeyBytes,
                bobPrivateKey = bob.privateKeyBytes,
                sharedSecretAlice = secretAB,
                sharedSecretBob = secretBA,
            )
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // HKDF vectors
    // =========================================================================

    data class HkdfVector(
        val ikm: ByteArray,
        val info: String,
        val length: Int,
        /** Computed at runtime by [ECDHKeyExchange.hkdfSHA256]. */
        val derived: ByteArray,
    )

    /** Fixed 32-byte IKM for HKDF tests. */
    private val HKDF_IKM = hexToBytes(
        "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
    )

    /** Generic info strings -- NOT protocol-specific. */
    private val HKDF_INFO_STRINGS = listOf(
        "test-key-derivation-alpha",
        "test-key-derivation-beta",
    )

    fun generateHkdfVectors(): List<HkdfVector> {
        val length = 32
        return HKDF_INFO_STRINGS.map { info ->
            val infoBytes = info.toByteArray(Charsets.UTF_8)
            HkdfVector(
                ikm = HKDF_IKM,
                info = info,
                length = length,
                derived = ECDHKeyExchange.hkdfSHA256(HKDF_IKM, length, infoBytes),
            )
        }
    }

    // =========================================================================
    // AES-GCM vectors
    // =========================================================================

    data class AesGcmVector(
        val key: ByteArray,
        val nonce: ByteArray,
        val plaintext: ByteArray,
        /** Ciphertext + 16-byte GCM tag, computed at runtime via JCA. */
        val ciphertext: ByteArray,
    )

    /** Fixed 12-byte nonce for AES-GCM test. */
    private val AES_NONCE = hexToBytes("000102030405060708090a0b")

    /** Fixed plaintext: "hello secagg test" in UTF-8. */
    private val AES_PLAINTEXT = "hello secagg test".toByteArray(Charsets.UTF_8)

    fun generateAesGcmVector(): AesGcmVector {
        // Derive a key using HKDF from fixed IKM with a generic info string
        val aesKey = ECDHKeyExchange.hkdfSHA256(
            HKDF_IKM, 32, "test-aes-gcm-key".toByteArray(Charsets.UTF_8),
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(128, AES_NONCE)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val ciphertext = cipher.doFinal(AES_PLAINTEXT)

        return AesGcmVector(
            key = aesKey,
            nonce = AES_NONCE,
            plaintext = AES_PLAINTEXT,
            ciphertext = ciphertext,
        )
    }

    // =========================================================================
    // Shamir vectors
    // =========================================================================

    data class ShamirVector(
        val secret: BigInteger,
        val threshold: Int,
        val numShares: Int,
        val shares: List<ShamirSecretSharing.Share>,
    )

    fun generateShamirVectors(): List<ShamirVector> {
        val shamir = ShamirSecretSharing()

        val secretSmall = BigInteger.valueOf(42)
        val secretLarge = BigInteger("1339673755198158349044581307228491536")

        return listOf(
            run {
                val t = 3; val n = 5
                val shares = shamir.split(secretSmall, t, n)
                ShamirVector(secretSmall, t, n, shares)
            },
            run {
                val t = 3; val n = 5
                val shares = shamir.split(secretLarge, t, n)
                ShamirVector(secretLarge, t, n, shares)
            },
        )
    }

    // =========================================================================
    // Quantization vectors
    // =========================================================================

    data class QuantizationVector(
        val clippingRange: Float,
        val targetRange: Long,
        val floatValues: List<Float>,
        val quantized: List<Long>,
        val dequantized: List<Float>,
    )

    /** Fixed float values for deterministic quantization test. */
    private val QUANT_FLOATS = listOf(
        0.0f, 1.0f, -1.0f, 4.0f, -4.0f, 8.0f, -8.0f, 0.5f, -0.5f, 3.14159f,
    )

    /**
     * Generates quantization vectors using deterministic floor rounding
     * (not stochastic) so values are reproducible.
     *
     * Quantize is done manually with floor rounding here because
     * [Quantization.quantize] uses stochastic rounding which is
     * non-deterministic. Dequantize uses the real implementation.
     */
    fun generateQuantizationVector(): QuantizationVector {
        val clip = 8.0f
        val target = 4194304L

        // Deterministic (floor) quantization -- matches the JSON vectors
        val quantizer = target.toDouble() / (2.0 * clip)
        val quantized = QUANT_FLOATS.map { v ->
            val clipped = v.toDouble().coerceIn(-clip.toDouble(), clip.toDouble())
            val preQuantized = (clipped + clip) * quantizer
            kotlin.math.floor(preQuantized).toLong()
        }

        // Dequantize using the actual implementation
        val dequantized = Quantization.dequantize(quantized, clip, target)

        return QuantizationVector(
            clippingRange = clip,
            targetRange = target,
            floatValues = QUANT_FLOATS,
            quantized = quantized,
            dequantized = dequantized,
        )
    }

    // =========================================================================
    // Mask direction vectors
    // =========================================================================

    data class MaskDirectionVector(
        val nodeId: Int,
        val peerId: Int,
        val expectedDirection: String,
    )

    fun generateMaskDirectionVectors(): List<MaskDirectionVector> = listOf(
        MaskDirectionVector(nodeId = 3, peerId = 1, expectedDirection = "ADD"),
        MaskDirectionVector(nodeId = 1, peerId = 3, expectedDirection = "SUBTRACT"),
        MaskDirectionVector(nodeId = 5, peerId = 5, expectedDirection = "SKIP"),
    )
}
