package ai.edgeml.secagg

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Shamir's Secret Sharing implementation over a finite field.
 *
 * Splits a secret into `n` shares such that any `t` (threshold) shares can
 * reconstruct the original secret, but fewer than `t` shares reveal nothing.
 *
 * Uses the Mersenne prime 2^127 - 1 as the field modulus for compatibility
 * with the server-side SecAgg implementation.
 */
class ShamirSecretSharing(
    private val fieldSize: BigInteger = MERSENNE_127,
) {
    private val random = SecureRandom()

    companion object {
        /** Mersenne prime 2^127 - 1, matching server field_size. */
        val MERSENNE_127: BigInteger = BigInteger.TWO.pow(127).subtract(BigInteger.ONE)
    }

    /**
     * A single Shamir share: the evaluation of the polynomial at [index].
     *
     * @property index The x-coordinate (1-based participant index).
     * @property value The y-coordinate (polynomial evaluation mod fieldSize).
     */
    data class Share(
        val index: Int,
        val value: BigInteger,
    )

    /**
     * Splits [secret] into [totalShares] shares with reconstruction [threshold].
     *
     * @param secret The secret value (must be in [0, fieldSize)).
     * @param threshold Minimum shares needed to reconstruct (t).
     * @param totalShares Total number of shares to generate (n).
     * @return List of [totalShares] shares.
     * @throws IllegalArgumentException if parameters are invalid.
     */
    fun split(secret: BigInteger, threshold: Int, totalShares: Int): List<Share> {
        require(threshold in 1..totalShares) {
            "threshold must be in [1, totalShares], got t=$threshold n=$totalShares"
        }
        require(secret >= BigInteger.ZERO && secret < fieldSize) {
            "secret must be in [0, fieldSize)"
        }

        // Build random polynomial: a_0 = secret, a_1..a_{t-1} random
        val coefficients = ArrayList<BigInteger>(threshold)
        coefficients.add(secret)
        for (i in 1 until threshold) {
            coefficients.add(randomFieldElement())
        }

        // Evaluate at x = 1, 2, ..., totalShares
        return (1..totalShares).map { x ->
            val xBig = BigInteger.valueOf(x.toLong())
            val y = evaluatePolynomial(coefficients, xBig)
            Share(index = x, value = y)
        }
    }

    /**
     * Splits a list of secret values into per-participant share bundles.
     *
     * Returns a list of size [totalShares], where each element is the list
     * of shares for one participant (one share per secret value).
     *
     * @param secrets List of secret integer values.
     * @param threshold Minimum shares for reconstruction.
     * @param totalShares Total participants.
     * @return Per-participant share bundles.
     */
    fun splitMultiple(
        secrets: List<BigInteger>,
        threshold: Int,
        totalShares: Int,
    ): List<List<Share>> {
        // For each secret, generate all shares
        val allShares = secrets.map { secret -> split(secret, threshold, totalShares) }

        // Transpose: group by participant index
        return (0 until totalShares).map { participantIdx ->
            allShares.map { sharesForSecret -> sharesForSecret[participantIdx] }
        }
    }

    /**
     * Reconstructs the secret from [shares] using Lagrange interpolation at x=0.
     *
     * @param shares At least [threshold] shares.
     * @return The reconstructed secret value.
     * @throws IllegalArgumentException if fewer than 2 shares provided.
     */
    fun reconstruct(shares: List<Share>): BigInteger {
        require(shares.size >= 2) { "Need at least 2 shares for reconstruction" }

        var result = BigInteger.ZERO

        for (i in shares.indices) {
            var numerator = BigInteger.ONE
            var denominator = BigInteger.ONE
            val xi = BigInteger.valueOf(shares[i].index.toLong())

            for (j in shares.indices) {
                if (i == j) continue
                val xj = BigInteger.valueOf(shares[j].index.toLong())
                // numerator *= (0 - xj) mod p
                numerator = numerator.multiply(xj.negate()).mod(fieldSize)
                // denominator *= (xi - xj) mod p
                denominator = denominator.multiply(xi.subtract(xj)).mod(fieldSize)
            }

            val denominatorInv = denominator.modInverse(fieldSize)
            val contribution = shares[i].value
                .multiply(numerator)
                .multiply(denominatorInv)
                .mod(fieldSize)

            result = result.add(contribution).mod(fieldSize)
        }

        return result
    }

    /**
     * Reconstructs multiple secrets from per-participant share bundles.
     *
     * @param participantShares List of share bundles, one per participant.
     *   Each bundle has one share per secret value.
     * @return Reconstructed list of secret values.
     */
    fun reconstructMultiple(participantShares: List<List<Share>>): List<BigInteger> {
        if (participantShares.isEmpty()) return emptyList()
        val numSecrets = participantShares[0].size

        return (0 until numSecrets).map { secretIdx ->
            val sharesForSecret = participantShares.map { it[secretIdx] }
            reconstruct(sharesForSecret)
        }
    }

    // -- internal helpers --

    private fun evaluatePolynomial(coefficients: List<BigInteger>, x: BigInteger): BigInteger {
        // Horner's method: result = c[n-1]*x + c[n-2] ...
        var result = coefficients.last()
        for (i in coefficients.size - 2 downTo 0) {
            result = result.multiply(x).add(coefficients[i]).mod(fieldSize)
        }
        return result
    }

    private fun randomFieldElement(): BigInteger {
        var r: BigInteger
        do {
            r = BigInteger(fieldSize.bitLength(), random)
        } while (r >= fieldSize)
        return r
    }
}
