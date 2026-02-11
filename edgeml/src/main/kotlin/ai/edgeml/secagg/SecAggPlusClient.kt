package ai.edgeml.secagg

import timber.log.Timber
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Client-side SecAgg+ state machine implementing the 4-stage protocol
 * from Bonawitz et al., aligned with the Python SDK's `SecAggPlusClient`
 * and the server's `SecAggProtocol`.
 *
 * ## Protocol stages
 *
 * **Stage 1 -- Setup:**
 *   Generate X25519 ECDH key pair, publish public key.
 *
 * **Stage 2 -- Share Keys:**
 *   After receiving all peer public keys, compute ECDH shared secrets,
 *   Shamir-share the self-mask seed, encrypt each share with AES-GCM
 *   using the pairwise shared secret, and send them.
 *
 * **Stage 3 -- Masked Upload:**
 *   Quantize weight update, apply pairwise masks (cancel in aggregate)
 *   and self-mask (Shamir-protected), upload masked vector.
 *
 * **Stage 4 -- Unmask:**
 *   Reveal decrypted Shamir shares for dropped peers so the server can
 *   reconstruct their self-mask and remove it from the aggregate.
 *
 * ## Wire format for AES-GCM encrypted shares
 *
 * ```
 * [12-byte random IV] [ciphertext + 16-byte GCM authentication tag]
 * ```
 *
 * The AES-256-GCM key is derived via HKDF-SHA256 from the ECDH shared
 * secret with `info = "secagg-share-encryption"`. All platforms (Android,
 * iOS, Python) must use this same format for cross-platform share exchange.
 *
 * ## Usage
 *
 * ```kotlin
 * val client = SecAggPlusClient(config)
 *
 * // Stage 1
 * val myPubKey = client.getPublicKey()
 * // ... exchange via server ...
 *
 * // Stage 2
 * client.receivePeerPublicKeys(peerKeys)
 * val encryptedShares = client.generateEncryptedShares()
 * // ... send encryptedShares[peerIdx] to each peer via server ...
 * client.receiveEncryptedShares(sharesFromPeers)
 *
 * // Stage 3
 * val maskedUpdate = client.maskModelUpdate(rawWeightsBytes)
 * // ... upload maskedUpdate ...
 *
 * // Stage 4 (only if peers dropped)
 * val revealed = client.revealSharesForDropped(droppedIndices)
 * // ... send to server ...
 * ```
 */
class SecAggPlusClient(
    val config: SecAggPlusConfig,
) {
    private val keyPair: ECDHKeyExchange.KeyPair = ECDHKeyExchange.generateKeyPair()
    private val selfSeed: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var selfShares: List<ShamirSecretSharing.Share>? = null

    // Populated during Stage 2
    private val peerPublicKeys = mutableMapOf<Int, ByteArray>()     // peer_index -> pub_key
    private val sharedSecrets = mutableMapOf<Int, ByteArray>()      // peer_index -> ECDH secret
    private val encryptedOutgoing = mutableMapOf<Int, ByteArray>()  // peer_index -> encrypted share
    private val receivedShares = mutableMapOf<Int, ShamirSecretSharing.Share>() // from_index -> share

    private val shamirSharing = ShamirSecretSharing()

    private var _stage: Stage = Stage.SETUP

    /** Current protocol stage. */
    val stage: Stage get() = _stage

    /**
     * Protocol stages for the SecAgg+ state machine.
     */
    enum class Stage {
        SETUP,
        SHARE_KEYS,
        COLLECT_MASKED_VECTORS,
        UNMASK,
        COMPLETED,
    }

    // =========================================================================
    // Stage 1: Setup
    // =========================================================================

    /**
     * Return this client's X25519 public key (raw bytes).
     */
    fun getPublicKey(): ByteArray = keyPair.publicKeyBytes.copyOf()

    // =========================================================================
    // Stage 2: Share Keys
    // =========================================================================

    /**
     * Store the public keys received from all peers and compute ECDH shared
     * secrets with each.
     *
     * @param peerKeys Map of peer index (1-based) to raw public key bytes.
     *   Must NOT include this client's own index.
     */
    fun receivePeerPublicKeys(peerKeys: Map<Int, ByteArray>) {
        check(_stage == Stage.SETUP) {
            "receivePeerPublicKeys() must be called in SETUP stage, current: $_stage"
        }

        peerPublicKeys.clear()
        sharedSecrets.clear()

        for ((idx, pubKey) in peerKeys) {
            if (idx == config.myIndex) continue
            peerPublicKeys[idx] = pubKey.copyOf()
            sharedSecrets[idx] = ECDHKeyExchange.computeSharedSecret(
                keyPair.privateKeyBytes,
                pubKey,
            )
        }

        Timber.d("Computed ${sharedSecrets.size} ECDH shared secrets")
        _stage = Stage.SHARE_KEYS
    }

    /**
     * Generate Shamir shares of the self-mask seed and encrypt each one
     * with the pairwise ECDH shared secret.
     *
     * @return Map of peer index to encrypted share bytes. Each entry should
     *   be delivered to the corresponding peer via the server.
     */
    fun generateEncryptedShares(): Map<Int, ByteArray> {
        check(_stage == Stage.SHARE_KEYS) {
            "generateEncryptedShares() must be called in SHARE_KEYS stage, current: $_stage"
        }

        // Convert seed to a big integer for Shamir sharing
        val seedInt = BigInteger(1, selfSeed).mod(ShamirSecretSharing.MERSENNE_127)
        selfShares = shamirSharing.split(
            secret = seedInt,
            threshold = config.threshold,
            totalShares = config.totalClients,
        )

        encryptedOutgoing.clear()

        for (share in selfShares!!) {
            val peerIdx = share.index
            if (peerIdx == config.myIndex) continue

            val sharedSecret = sharedSecrets[peerIdx] ?: continue
            val shareBytes = serializeShare(share)
            encryptedOutgoing[peerIdx] = ECDHKeyExchange.encryptShare(shareBytes, sharedSecret)
        }

        Timber.d("Generated ${encryptedOutgoing.size} encrypted shares")
        return encryptedOutgoing.toMap()
    }

    /**
     * Receive and decrypt Shamir shares from peers.
     *
     * @param shares Map of sender index to encrypted share bytes.
     */
    fun receiveEncryptedShares(shares: Map<Int, ByteArray>) {
        check(_stage == Stage.SHARE_KEYS) {
            "receiveEncryptedShares() must be called in SHARE_KEYS stage, current: $_stage"
        }

        for ((senderIdx, encrypted) in shares) {
            val sharedSecret = sharedSecrets[senderIdx]
            if (sharedSecret == null) {
                Timber.w("No shared secret for peer $senderIdx, skipping")
                continue
            }

            try {
                val decrypted = ECDHKeyExchange.decryptShare(encrypted, sharedSecret)
                val share = deserializeShare(decrypted)
                receivedShares[senderIdx] = share
            } catch (e: Exception) {
                Timber.w(e, "Failed to decrypt share from peer $senderIdx")
            }
        }

        Timber.d("Received ${receivedShares.size} decrypted shares from peers")
        _stage = Stage.COLLECT_MASKED_VECTORS
    }

    // =========================================================================
    // Stage 3: Masked Upload
    // =========================================================================

    /**
     * Apply pairwise masks and self-mask to the model update.
     *
     * The update bytes are converted to field elements, then:
     * 1. For each peer, derive a pairwise mask from the ECDH shared secret.
     *    If my_index > peer_index, ADD the mask; otherwise SUBTRACT it.
     *    These cancel out in the aggregate (matching Flower/server convention).
     * 2. Add the self-mask (derived from the Shamir-shared seed).
     *    The server removes this by reconstructing the seed from shares.
     *
     * All mask arithmetic uses `mod_range` (default 2^32), NOT the Mersenne prime.
     * The Mersenne prime is only used for Shamir field arithmetic.
     *
     * @param updateBytes Raw model weight update bytes.
     * @return Masked update bytes.
     */
    fun maskModelUpdate(updateBytes: ByteArray): ByteArray {
        check(_stage == Stage.COLLECT_MASKED_VECTORS) {
            "maskModelUpdate() must be called in COLLECT_MASKED_VECTORS stage, current: $_stage"
        }

        val modRange = config.modRange
        val myIdx = config.myIndex

        // Convert to 4-byte big-endian unsigned ints
        val elements = bytesToIntElements(updateBytes)
        val n = elements.size

        // Start with a mutable copy
        val masked = elements.toLongArray()

        // Apply pairwise masks (using HKDF-derived PRG)
        val context = config.roundId.toByteArray(Charsets.UTF_8)
        for ((peerIdx, sharedSecret) in sharedSecrets) {
            val pairwiseMask = ECDHKeyExchange.derivePairwiseMask(
                sharedSecret, n, BigInteger.valueOf(modRange), context,
            )
            if (myIdx > peerIdx) {
                // Add pairwise mask (Flower convention: my_idx > peer_idx -> ADD)
                for (j in 0 until n) {
                    masked[j] = (masked[j] + pairwiseMask[j].toLong()) % modRange
                }
            } else {
                // Subtract pairwise mask (Flower convention: my_idx < peer_idx -> SUBTRACT)
                for (j in 0 until n) {
                    masked[j] = ((masked[j] - pairwiseMask[j].toLong()) % modRange + modRange) % modRange
                }
            }
        }

        // Apply self-mask using SHA-256 counter mode PRG (platform-independent)
        val selfMask = pseudoRandGen(selfSeed, modRange, n)
        for (j in 0 until n) {
            masked[j] = (masked[j] + selfMask[j]) % modRange
        }

        _stage = Stage.UNMASK

        Timber.d("Masked model update: $n elements, ${sharedSecrets.size} pairwise masks")
        return intElementsToBytes(masked, updateBytes.size)
    }

    // =========================================================================
    // Stage 4: Unmask (dropout handling)
    // =========================================================================

    /**
     * Reveal this client's Shamir shares for dropped peers.
     *
     * For each dropped peer, the server collects shares from surviving clients
     * to reconstruct the dropped peer's seed and remove their self-mask.
     *
     * @param droppedIndices 1-based indices of peers that dropped.
     * @return Map of dropped peer index to serialized Shamir share bytes.
     */
    fun revealSharesForDropped(droppedIndices: List<Int>): Map<Int, ByteArray> {
        check(_stage == Stage.UNMASK) {
            "revealSharesForDropped() must be called in UNMASK stage, current: $_stage"
        }

        val revealed = mutableMapOf<Int, ByteArray>()
        for (droppedIdx in droppedIndices) {
            val share = receivedShares[droppedIdx]
            if (share != null) {
                revealed[droppedIdx] = serializeShare(share)
            } else {
                Timber.w("No share for dropped peer $droppedIdx")
            }
        }

        _stage = Stage.COMPLETED

        Timber.d("Revealed ${revealed.size} shares for ${droppedIndices.size} dropped peers")
        return revealed
    }

    /**
     * Get this client's own Shamir share destined for a specific peer.
     *
     * Used when THIS client is the one that might drop and other peers
     * need to reveal their shares of this client's seed.
     *
     * @param peerIndex 1-based target peer index.
     * @return The share, or null if shares haven't been generated yet.
     */
    fun getOwnShare(peerIndex: Int): ShamirSecretSharing.Share? {
        return selfShares?.firstOrNull { it.index == peerIndex }
    }

    /**
     * Complete the protocol without unmasking (no dropouts).
     */
    fun complete() {
        check(_stage == Stage.UNMASK) {
            "complete() must be called in UNMASK stage, current: $_stage"
        }
        _stage = Stage.COMPLETED
    }

    // =========================================================================
    // Integer element encoding / decoding (mod_range, not Mersenne prime)
    // =========================================================================

    companion object {
        private const val CHUNK_BYTES = 4

        /**
         * Convert raw bytes to unsigned 4-byte big-endian integers.
         * Used for mod_range (2^32) arithmetic, not Mersenne field arithmetic.
         */
        internal fun bytesToIntElements(data: ByteArray): List<Long> {
            val elements = ArrayList<Long>((data.size + CHUNK_BYTES - 1) / CHUNK_BYTES)
            var offset = 0
            while (offset < data.size) {
                val remaining = data.size - offset
                val chunkSize = minOf(CHUNK_BYTES, remaining)
                val chunk = ByteArray(CHUNK_BYTES)
                System.arraycopy(data, offset, chunk, 0, chunkSize)
                val value = ByteBuffer.wrap(chunk).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                elements.add(value)
                offset += CHUNK_BYTES
            }
            return elements
        }

        /**
         * Convert unsigned 4-byte integers back to raw bytes.
         */
        internal fun intElementsToBytes(elements: LongArray, originalSize: Int): ByteArray {
            val result = ByteArray(elements.size * CHUNK_BYTES)
            for ((i, element) in elements.withIndex()) {
                val value = (element and 0xFFFFFFFFL).toInt()
                ByteBuffer.wrap(result, i * CHUNK_BYTES, CHUNK_BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(value)
            }
            return result.copyOf(originalSize)
        }

        /**
         * Platform-independent pseudo-random generator using SHA-256 counter mode.
         *
         * `SHA-256(seed || counter_BE)` -> take first 4 bytes as big-endian uint32
         * -> mod modRange. Produces identical output in Java, Python, Swift, etc.
         *
         * @param seed Raw seed bytes.
         * @param modRange Modular arithmetic range (e.g., 2^32).
         * @param count Number of random integers to generate.
         * @return Array of random longs in [0, modRange).
         */
        internal fun pseudoRandGen(seed: ByteArray, modRange: Long, count: Int): LongArray {
            val result = LongArray(count)
            val digest = MessageDigest.getInstance("SHA-256")

            for (i in 0 until count) {
                digest.reset()
                digest.update(seed)
                digest.update(
                    ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(i).array(),
                )
                val hash = digest.digest()

                // Take first 4 bytes as unsigned big-endian int
                val value = ByteBuffer.wrap(hash, 0, 4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .int.toLong() and 0xFFFFFFFFL
                result[i] = value % modRange
            }
            return result
        }
    }

    // =========================================================================
    // Share serialization
    // =========================================================================

    /**
     * Serialize a Shamir share to bytes.
     *
     * Format: [index: 4 bytes BE] [value: 16 bytes BE] [modulus_len: 4 bytes] [modulus]
     */
    private fun serializeShare(share: ShamirSecretSharing.Share): ByteArray {
        val modBytes = ShamirSecretSharing.MERSENNE_127.toByteArray()
        // Ensure positive representation (no leading 0x00 sign byte issues)
        val cleanModBytes = if (modBytes[0] == 0.toByte() && modBytes.size > 16) {
            modBytes.copyOfRange(1, modBytes.size)
        } else {
            modBytes
        }

        val valueBytes = share.value.toByteArray()
        val paddedValue = ByteArray(16)
        val srcOffset = if (valueBytes.size > 16) valueBytes.size - 16 else 0
        val dstOffset = if (valueBytes.size < 16) 16 - valueBytes.size else 0
        val copyLen = minOf(valueBytes.size, 16)
        System.arraycopy(valueBytes, srcOffset, paddedValue, dstOffset, copyLen)

        val buffer = ByteBuffer.allocate(4 + 16 + 4 + cleanModBytes.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(share.index)
        buffer.put(paddedValue)
        buffer.putInt(cleanModBytes.size)
        buffer.put(cleanModBytes)

        return buffer.array()
    }

    /**
     * Deserialize a Shamir share from bytes.
     */
    private fun deserializeShare(data: ByteArray): ShamirSecretSharing.Share {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val index = bb.int
        val valueBytes = ByteArray(16)
        bb.get(valueBytes)
        val modLen = bb.int
        val modBytes = ByteArray(modLen)
        bb.get(modBytes)

        val value = BigInteger(1, valueBytes)
        return ShamirSecretSharing.Share(index = index, value = value)
    }
}

/**
 * Configuration for the SecAgg+ protocol.
 *
 * Parameters match the server's `SecAggConfig` and the Python SDK's
 * `SecAggPlusConfig`. The server sends `clipping_range`, `target_range`,
 * and `mod_range` during Stage 0 setup.
 */
data class SecAggPlusConfig(
    /** Server-assigned session ID. */
    val sessionId: String,
    /** Federated learning round ID. */
    val roundId: String,
    /** Minimum shares needed for secret reconstruction. */
    val threshold: Int,
    /** Total number of participating clients. */
    val totalClients: Int,
    /** This client's 1-based participant index. */
    val myIndex: Int,
    /** Symmetric clipping range for quantization (server default: 8.0). */
    val clippingRange: Float = 3.0f,
    /** Quantization target range (server default: 2^22 = 4194304). */
    val targetRange: Long = (1L shl 16),
    /** Modular arithmetic range for masks (server default: 2^32). Uses Long to hold 4294967296. */
    val modRange: Long = DEFAULT_MOD_RANGE,
) {
    companion object {
        /** Default mod range: 2^32 (matches Flower / server). */
        const val DEFAULT_MOD_RANGE = 1L shl 32  // 4294967296
    }
}
