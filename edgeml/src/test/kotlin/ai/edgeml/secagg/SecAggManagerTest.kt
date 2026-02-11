package ai.edgeml.secagg

import ai.edgeml.api.EdgeMLApi
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SecAggManagerTest {

    private lateinit var api: EdgeMLApi
    private lateinit var manager: SecAggManager

    @Before
    fun setUp() {
        api = mockk(relaxed = true)
        manager = SecAggManager(api)
    }

    // =========================================================================
    // Field element encoding
    // =========================================================================

    @Test
    fun `bytesToFieldElements converts 4-byte chunks correctly`() {
        // 4 bytes = one field element
        val data = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(42).array()
        val elements = manager.bytesToFieldElements(data)

        assertEquals(1, elements.size)
        assertEquals(BigInteger.valueOf(42), elements[0])
    }

    @Test
    fun `bytesToFieldElements handles multiple chunks`() {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(100)
        buffer.putInt(200)
        val data = buffer.array()

        val elements = manager.bytesToFieldElements(data)
        assertEquals(2, elements.size)
        assertEquals(BigInteger.valueOf(100), elements[0])
        assertEquals(BigInteger.valueOf(200), elements[1])
    }

    @Test
    fun `bytesToFieldElements pads incomplete last chunk with zeros`() {
        // 6 bytes = 1 full chunk + 1 padded chunk
        val data = byteArrayOf(0, 0, 0, 1, 0, 2)
        val elements = manager.bytesToFieldElements(data)

        assertEquals(2, elements.size)
        assertEquals(BigInteger.ONE, elements[0])

        // Second chunk: bytes [0x00, 0x02, 0x00, 0x00] (padded)
        val expected = ByteBuffer.wrap(byteArrayOf(0, 2, 0, 0)).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        assertEquals(BigInteger.valueOf(expected), elements[1])
    }

    @Test
    fun `fieldElementsToBytes roundtrips with bytesToFieldElements`() {
        val original = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putInt(1000)
            .putInt(2000)
            .putInt(3000)
            .array()

        val elements = manager.bytesToFieldElements(original)
        val roundTripped = manager.fieldElementsToBytes(elements, original.size)

        assertArrayEquals(original, roundTripped)
    }

    @Test
    fun `fieldElementsToBytes trims to original size`() {
        // 5 bytes -> 2 field elements (8 bytes), but should trim back to 5
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val elements = manager.bytesToFieldElements(original)
        val result = manager.fieldElementsToBytes(elements, original.size)

        assertEquals(5, result.size)
        // First 4 bytes should match
        assertArrayEquals(original.copyOf(4), result.copyOf(4))
    }

    @Test
    fun `empty data produces no field elements`() {
        val elements = manager.bytesToFieldElements(byteArrayOf())
        assertTrue(elements.isEmpty())
    }

    // =========================================================================
    // Masking
    // =========================================================================

    @Test
    fun `maskWeights produces different output from input`() {
        val session = SecAggSessionInfo(
            sessionId = "test-session",
            threshold = 2,
            totalClients = 3,
            participantIds = listOf("a", "b", "c"),
        )

        val weightsData = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(100)
            .putInt(200)
            .array()
        val seed = ByteArray(32) { it.toByte() }

        val masked = manager.maskWeights(weightsData, seed, session)

        assertEquals(weightsData.size, masked.size)
        // With overwhelming probability, masked data differs from original
        var different = false
        for (i in weightsData.indices) {
            if (weightsData[i] != masked[i]) {
                different = true
                break
            }
        }
        assertTrue("Masked weights should differ from plaintext", different)
    }

    @Test
    fun `maskWeights preserves data size`() {
        val session = SecAggSessionInfo(
            sessionId = "test-session",
            threshold = 2,
            totalClients = 3,
            participantIds = listOf("a", "b", "c"),
        )

        val sizes = listOf(4, 8, 16, 100, 1000)
        for (size in sizes) {
            val data = ByteArray(size) { (it % 256).toByte() }
            val seed = ByteArray(32) { it.toByte() }
            val masked = manager.maskWeights(data, seed, session)
            assertEquals("Masked size should equal original for size=$size", size, masked.size)
        }
    }

    @Test
    fun `same seed and session produce same mask`() {
        val session = SecAggSessionInfo(
            sessionId = "deterministic-session",
            threshold = 2,
            totalClients = 3,
            participantIds = listOf("a", "b", "c"),
        )

        val data = ByteArray(20) { (it * 7).toByte() }
        val seed = ByteArray(32) { (it + 1).toByte() }

        val masked1 = manager.maskWeights(data, seed, session)
        val masked2 = manager.maskWeights(data, seed, session)

        assertArrayEquals("Same seed should produce same masked output", masked1, masked2)
    }

    // =========================================================================
    // Seed splitting
    // =========================================================================

    @Test
    fun `splitMaskingSeed produces correct number of shares`() {
        val session = SecAggSessionInfo(
            sessionId = "test",
            threshold = 3,
            totalClients = 5,
            participantIds = (1..5).map { "p$it" },
        )
        val seed = ByteArray(32) { it.toByte() }

        val shares = manager.splitMaskingSeed(seed, session)
        assertEquals(5, shares.size)
    }

    @Test
    fun `splitMaskingSeed shares reconstruct to same value`() {
        val session = SecAggSessionInfo(
            sessionId = "test",
            threshold = 3,
            totalClients = 5,
            participantIds = (1..5).map { "p$it" },
        )
        val seed = ByteArray(32) { it.toByte() }

        val shares = manager.splitMaskingSeed(seed, session)
        val seedValue = BigInteger(1, seed).mod(ShamirSecretSharing.MERSENNE_127)

        val reconstructed = ShamirSecretSharing().reconstruct(shares.take(3))
        assertEquals(seedValue, reconstructed)
    }

    @Test
    fun `splitMaskingSeed with threshold 2 allows any 2 shares`() {
        val session = SecAggSessionInfo(
            sessionId = "test",
            threshold = 2,
            totalClients = 4,
            participantIds = (1..4).map { "p$it" },
        )
        val seed = ByteArray(32) { (it * 3).toByte() }
        val seedValue = BigInteger(1, seed).mod(ShamirSecretSharing.MERSENNE_127)

        val shares = manager.splitMaskingSeed(seed, session)
        val shamir = ShamirSecretSharing()

        // Any pair of 2 shares should reconstruct
        for (i in 0 until 4) {
            for (j in i + 1 until 4) {
                val reconstructed = shamir.reconstruct(listOf(shares[i], shares[j]))
                assertEquals(
                    "Shares at indices $i,$j should reconstruct seed",
                    seedValue,
                    reconstructed,
                )
            }
        }
    }
}
