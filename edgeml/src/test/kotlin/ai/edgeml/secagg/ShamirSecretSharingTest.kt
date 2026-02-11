package ai.edgeml.secagg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class ShamirSecretSharingTest {

    private val shamir = ShamirSecretSharing()

    @Test
    fun `split and reconstruct recovers the secret with exact threshold`() {
        val secret = BigInteger.valueOf(42)
        val shares = shamir.split(secret, threshold = 3, totalShares = 5)

        assertEquals(5, shares.size)

        // Reconstruct using exactly threshold shares
        val reconstructed = shamir.reconstruct(shares.take(3))
        assertEquals(secret, reconstructed)
    }

    @Test
    fun `reconstruct works with more than threshold shares`() {
        val secret = BigInteger.valueOf(123456789)
        val shares = shamir.split(secret, threshold = 2, totalShares = 5)

        // Using all 5 shares should also work
        val reconstructed = shamir.reconstruct(shares)
        assertEquals(secret, reconstructed)
    }

    @Test
    fun `reconstruct works with any subset of threshold shares`() {
        val secret = BigInteger.valueOf(99999)
        val shares = shamir.split(secret, threshold = 3, totalShares = 5)

        // Try different subsets of 3 shares
        val subsets = listOf(
            listOf(shares[0], shares[1], shares[2]),
            listOf(shares[0], shares[2], shares[4]),
            listOf(shares[1], shares[3], shares[4]),
            listOf(shares[2], shares[3], shares[4]),
        )

        for (subset in subsets) {
            val reconstructed = shamir.reconstruct(subset)
            assertEquals("Subset ${subset.map { it.index }} should reconstruct to secret", secret, reconstructed)
        }
    }

    @Test
    fun `split produces unique shares`() {
        val secret = BigInteger.valueOf(42)
        val shares = shamir.split(secret, threshold = 3, totalShares = 5)

        // All share values should be different
        val uniqueValues = shares.map { it.value }.toSet()
        assertEquals(shares.size, uniqueValues.size)
    }

    @Test
    fun `share indices are 1-based`() {
        val shares = shamir.split(BigInteger.TEN, threshold = 2, totalShares = 3)
        assertEquals(listOf(1, 2, 3), shares.map { it.index })
    }

    @Test
    fun `zero secret works`() {
        val secret = BigInteger.ZERO
        val shares = shamir.split(secret, threshold = 2, totalShares = 3)
        val reconstructed = shamir.reconstruct(shares.take(2))
        assertEquals(secret, reconstructed)
    }

    @Test
    fun `large secret near field boundary works`() {
        val secret = ShamirSecretSharing.MERSENNE_127.subtract(BigInteger.ONE)
        val shares = shamir.split(secret, threshold = 3, totalShares = 5)
        val reconstructed = shamir.reconstruct(shares.take(3))
        assertEquals(secret, reconstructed)
    }

    @Test
    fun `threshold of 1 means any single share suffices`() {
        val secret = BigInteger.valueOf(7777)
        val shares = shamir.split(secret, threshold = 1, totalShares = 5)

        // Each individual share (paired with any other for Lagrange) should still work,
        // but with threshold=1, each share IS the secret (constant polynomial)
        // We need at least 2 shares for Lagrange, but the values should all equal secret
        for (share in shares) {
            assertEquals(secret, share.value)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `threshold greater than totalShares throws`() {
        shamir.split(BigInteger.TEN, threshold = 5, totalShares = 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `threshold of zero throws`() {
        shamir.split(BigInteger.TEN, threshold = 0, totalShares = 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative secret throws`() {
        shamir.split(BigInteger.valueOf(-1), threshold = 2, totalShares = 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `secret exceeding field size throws`() {
        shamir.split(ShamirSecretSharing.MERSENNE_127, threshold = 2, totalShares = 3)
    }

    // -- splitMultiple / reconstructMultiple --

    @Test
    fun `splitMultiple and reconstructMultiple work for multiple secrets`() {
        val secrets = listOf(
            BigInteger.valueOf(100),
            BigInteger.valueOf(200),
            BigInteger.valueOf(300),
        )

        val participantBundles = shamir.splitMultiple(secrets, threshold = 2, totalShares = 3)
        assertEquals(3, participantBundles.size)
        assertEquals(3, participantBundles[0].size) // 3 secrets per participant

        val reconstructed = shamir.reconstructMultiple(participantBundles.take(2))
        assertEquals(secrets, reconstructed)
    }

    @Test
    fun `splitMultiple produces correct structure`() {
        val secrets = listOf(BigInteger.valueOf(10), BigInteger.valueOf(20))
        val bundles = shamir.splitMultiple(secrets, threshold = 2, totalShares = 4)

        assertEquals(4, bundles.size)

        for (bundle in bundles) {
            assertEquals(2, bundle.size) // 2 secrets
        }

        // All shares for the same participant should have the same index
        for (bundle in bundles) {
            val indices = bundle.map { it.index }.toSet()
            assertEquals(1, indices.size)
        }
    }

    @Test
    fun `field size is the correct Mersenne prime`() {
        val expected = BigInteger.TWO.pow(127).subtract(BigInteger.ONE)
        assertEquals(expected, ShamirSecretSharing.MERSENNE_127)
        assertTrue(ShamirSecretSharing.MERSENNE_127.isProbablePrime(20))
    }
}
