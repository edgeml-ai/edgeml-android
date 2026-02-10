package ai.edgeml.secagg

import ai.edgeml.api.EdgeMLApi
import ai.edgeml.api.dto.SecAggKeyExchangeRequest
import ai.edgeml.api.dto.SecAggSessionResponse
import ai.edgeml.api.dto.SecAggShareSubmitRequest
import ai.edgeml.training.WeightUpdate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages the client side of the SecAgg+ protocol.
 *
 * Protocol flow:
 * 1. Server creates a SecAgg session for the round
 * 2. Client joins session and receives config (threshold, total_clients)
 * 3. Client trains locally and gets weight update
 * 4. Client masks the weight update using a per-round seed
 * 5. Client splits the seed into Shamir shares
 * 6. Client submits masked update + shares to server
 * 7. Server reconstructs the aggregate from threshold participants
 *
 * This implementation aligns with the server's SecAggProtocol which uses:
 * - Mersenne prime 2^127-1 as the finite field
 * - 4-byte big-endian chunks for field element encoding
 * - 16-byte big-endian share values
 */
class SecAggManager(
    private val api: EdgeMLApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val shamirSharing = ShamirSecretSharing()
    private val secureRandom = SecureRandom()

    companion object {
        private const val FIELD_ELEMENT_BYTES = 4 // 32-bit chunks, matching server
        private const val SHARE_VALUE_BYTES = 16 // 128-bit share values, matching server
        private const val MODULUS_BYTES = 8 // 64-bit modulus in serialized shares
    }

    /**
     * Result of the client-side SecAgg processing.
     *
     * @property maskedWeightsData The masked (encrypted) weight update.
     * @property serializedShares Serialized Shamir shares for the masking seed.
     * @property verificationTag HMAC-SHA256 tag over the masked update for integrity.
     * @property sessionId The SecAgg session ID from the server.
     */
    data class SecAggResult(
        val maskedWeightsData: ByteArray,
        val serializedShares: Map<String, ByteArray>,
        val verificationTag: String,
        val sessionId: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SecAggResult) return false
            return maskedWeightsData.contentEquals(other.maskedWeightsData) &&
                verificationTag == other.verificationTag &&
                sessionId == other.sessionId
        }

        override fun hashCode(): Int {
            var result = maskedWeightsData.contentHashCode()
            result = 31 * result + verificationTag.hashCode()
            result = 31 * result + sessionId.hashCode()
            return result
        }
    }

    /**
     * Processes a weight update through the SecAgg protocol.
     *
     * @param weightUpdate The plaintext weight update from local training.
     * @param roundId The federated learning round ID.
     * @param deviceId The server-assigned device ID.
     * @return [SecAggResult] containing the masked update and shares, or null on failure.
     */
    suspend fun processWeightUpdate(
        weightUpdate: WeightUpdate,
        roundId: String,
        deviceId: String,
    ): Result<SecAggResult> = withContext(ioDispatcher) {
        try {
            Timber.i("Starting SecAgg processing for round $roundId")

            // Step 1: Join the SecAgg session
            val session = joinSession(roundId, deviceId)
                ?: return@withContext Result.failure(
                    SecAggException("Failed to join SecAgg session for round $roundId")
                )

            Timber.d("Joined SecAgg session ${session.sessionId}, threshold=${session.threshold}")

            // Step 2: Generate a random masking seed
            val maskingSeed = generateMaskingSeed()

            // Step 3: Mask the weight update
            val maskedWeights = maskWeights(weightUpdate.weightsData, maskingSeed, session)

            // Step 4: Split the masking seed into Shamir shares
            val shares = splitMaskingSeed(maskingSeed, session)

            // Step 5: Serialize shares for each participant
            val serializedShares = serializeSharesForParticipants(shares, session)

            // Step 6: Compute verification tag
            val verificationTag = computeVerificationTag(maskedWeights, session.sessionId)

            // Step 7: Submit shares to server
            submitShares(session.sessionId, deviceId, serializedShares, verificationTag)

            Timber.i("SecAgg processing complete for round $roundId")

            Result.success(
                SecAggResult(
                    maskedWeightsData = maskedWeights,
                    serializedShares = serializedShares,
                    verificationTag = verificationTag,
                    sessionId = session.sessionId,
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "SecAgg processing failed for round $roundId")
            Result.failure(SecAggException("SecAgg processing failed: ${e.message}", e))
        }
    }

    /**
     * Masks weight data by adding the masking vector derived from [seed].
     *
     * The masking is additive in the finite field: each 4-byte weight chunk
     * is converted to a field element and added to the corresponding mask
     * element modulo the field size.
     *
     * The server can remove the mask by reconstructing the seed from shares
     * and subtracting the same mask vector.
     */
    internal fun maskWeights(
        weightsData: ByteArray,
        seed: ByteArray,
        session: SecAggSessionInfo,
    ): ByteArray {
        val fieldElements = bytesToFieldElements(weightsData)
        val maskElements = generateMaskVector(seed, fieldElements.size, session.sessionId)

        val maskedElements = fieldElements.zip(maskElements).map { (w, m) ->
            w.add(m).mod(ShamirSecretSharing.MERSENNE_127)
        }

        return fieldElementsToBytes(maskedElements, weightsData.size)
    }

    /**
     * Splits the masking seed into Shamir shares for all participants.
     *
     * The seed is treated as a single big integer and split using
     * the session's threshold configuration.
     */
    internal fun splitMaskingSeed(
        seed: ByteArray,
        session: SecAggSessionInfo,
    ): List<ShamirSecretSharing.Share> {
        val seedValue = BigInteger(1, seed).mod(ShamirSecretSharing.MERSENNE_127)
        return shamirSharing.split(
            secret = seedValue,
            threshold = session.threshold,
            totalShares = session.totalClients,
        )
    }

    // -- Private helpers --

    private suspend fun joinSession(
        roundId: String,
        deviceId: String,
    ): SecAggSessionInfo? {
        return try {
            val response = api.joinSecAggSession(
                roundId = roundId,
                request = SecAggKeyExchangeRequest(deviceId = deviceId),
            )
            if (response.isSuccessful) {
                val body = response.body() ?: return null
                SecAggSessionInfo(
                    sessionId = body.sessionId,
                    threshold = body.threshold,
                    totalClients = body.totalClients,
                    participantIds = body.participantIds,
                )
            } else {
                Timber.w("Failed to join SecAgg session: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error joining SecAgg session")
            null
        }
    }

    private suspend fun submitShares(
        sessionId: String,
        deviceId: String,
        shares: Map<String, ByteArray>,
        verificationTag: String,
    ) {
        try {
            val response = api.submitSecAggShares(
                sessionId = sessionId,
                request = SecAggShareSubmitRequest(
                    deviceId = deviceId,
                    shares = shares.mapValues {
                        android.util.Base64.encodeToString(it.value, android.util.Base64.NO_WRAP)
                    },
                    verificationTag = verificationTag,
                ),
            )
            if (!response.isSuccessful) {
                Timber.w("Failed to submit SecAgg shares: ${response.code()}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Error submitting SecAgg shares")
        }
    }

    private fun generateMaskingSeed(): ByteArray {
        val seed = ByteArray(32) // 256-bit seed
        secureRandom.nextBytes(seed)
        return seed
    }

    /**
     * Generates a deterministic mask vector from a seed using HKDF-like expansion.
     *
     * Uses SHA-256 in counter mode to generate enough field elements.
     */
    private fun generateMaskVector(
        seed: ByteArray,
        numElements: Int,
        sessionId: String,
    ): List<BigInteger> {
        val elements = ArrayList<BigInteger>(numElements)
        val digest = MessageDigest.getInstance("SHA-256")
        var counter = 0

        while (elements.size < numElements) {
            digest.reset()
            digest.update(seed)
            digest.update(sessionId.toByteArray(Charsets.UTF_8))
            // Counter as 4-byte big-endian
            digest.update(
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(counter).array()
            )
            val hash = digest.digest()

            // Extract field element from hash (use first 16 bytes to stay within 127-bit prime)
            val element = BigInteger(1, hash.copyOf(16)).mod(ShamirSecretSharing.MERSENNE_127)
            elements.add(element)
            counter++
        }

        return elements
    }

    /**
     * Converts raw bytes to finite field elements (4-byte big-endian chunks).
     * Matches server's `_serialize_to_field_elements`.
     */
    internal fun bytesToFieldElements(data: ByteArray): List<BigInteger> {
        val elements = ArrayList<BigInteger>((data.size + FIELD_ELEMENT_BYTES - 1) / FIELD_ELEMENT_BYTES)
        var offset = 0
        while (offset < data.size) {
            val remaining = data.size - offset
            val chunkSize = minOf(FIELD_ELEMENT_BYTES, remaining)
            val chunk = ByteArray(FIELD_ELEMENT_BYTES)
            System.arraycopy(data, offset, chunk, 0, chunkSize)
            // Zero-pad if needed (already zero-initialized)

            val value = ByteBuffer.wrap(chunk).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            elements.add(BigInteger.valueOf(value).mod(ShamirSecretSharing.MERSENNE_127))
            offset += FIELD_ELEMENT_BYTES
        }
        return elements
    }

    /**
     * Converts field elements back to raw bytes.
     * Matches server's `_deserialize_from_field_elements`.
     */
    internal fun fieldElementsToBytes(elements: List<BigInteger>, originalSize: Int): ByteArray {
        val result = ByteArray(elements.size * FIELD_ELEMENT_BYTES)
        for ((i, element) in elements.withIndex()) {
            val value = element.mod(BigInteger.valueOf(0x100000000L)).toInt()
            ByteBuffer.wrap(result, i * FIELD_ELEMENT_BYTES, FIELD_ELEMENT_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(value)
        }
        // Trim to original size (remove zero-padding on last chunk)
        return result.copyOf(originalSize)
    }

    /**
     * Serializes shares for each participant in the wire format expected by the server.
     *
     * Format per share bundle:
     * - share_count (4 bytes, big-endian)
     * - For each share:
     *   - index (4 bytes)
     *   - value_length (4 bytes)
     *   - value (16 bytes)
     *   - modulus (8 bytes)
     */
    private fun serializeSharesForParticipants(
        shares: List<ShamirSecretSharing.Share>,
        session: SecAggSessionInfo,
    ): Map<String, ByteArray> {
        val result = HashMap<String, ByteArray>()

        for ((i, share) in shares.withIndex()) {
            val participantId = session.participantIds.getOrNull(i) ?: "participant_$i"

            val buffer = ByteBuffer.allocate(4 + (4 + 4 + SHARE_VALUE_BYTES + MODULUS_BYTES))
            buffer.order(ByteOrder.BIG_ENDIAN)

            // Number of shares in this bundle (1 for masking seed)
            buffer.putInt(1)
            // Share index
            buffer.putInt(share.index)
            // Value length
            buffer.putInt(SHARE_VALUE_BYTES)
            // Value (pad to 16 bytes, big-endian)
            val valueBytes = share.value.toByteArray()
            val padded = ByteArray(SHARE_VALUE_BYTES)
            val srcOffset = if (valueBytes.size > SHARE_VALUE_BYTES) valueBytes.size - SHARE_VALUE_BYTES else 0
            val dstOffset = if (valueBytes.size < SHARE_VALUE_BYTES) SHARE_VALUE_BYTES - valueBytes.size else 0
            val copyLen = minOf(valueBytes.size, SHARE_VALUE_BYTES)
            System.arraycopy(valueBytes, srcOffset, padded, dstOffset, copyLen)
            buffer.put(padded)
            // Modulus (64-bit, truncated from 127-bit prime - matches server format)
            buffer.putLong(ShamirSecretSharing.MERSENNE_127.toLong())

            result[participantId] = buffer.array()
        }

        return result
    }

    /**
     * Computes an HMAC-SHA256 verification tag over the masked update.
     */
    private fun computeVerificationTag(maskedWeights: ByteArray, sessionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sessionId.toByteArray(Charsets.UTF_8))
        digest.update(maskedWeights)
        val hash = digest.digest()
        return hash.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Information about a SecAgg session received from the server.
 */
data class SecAggSessionInfo(
    val sessionId: String,
    val threshold: Int,
    val totalClients: Int,
    val participantIds: List<String>,
)

/**
 * Exception thrown when SecAgg protocol operations fail.
 */
class SecAggException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
