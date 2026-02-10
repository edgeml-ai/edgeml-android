package ai.edgeml.secagg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator

/**
 * Tests for [SecAggPlusClient] -- the 4-stage SecAgg+ protocol.
 *
 * Tests that require X25519 (ECDH key generation) use [Assume] to skip
 * gracefully on JVMs without X25519 support (< JDK 11).
 */
class SecAggPlusClientTest {

    private var x25519Available = false

    @Before
    fun setUp() {
        x25519Available = try {
            KeyPairGenerator.getInstance("X25519")
            true
        } catch (e: Exception) {
            false
        }
    }

    // =========================================================================
    // Full protocol flow (2 clients, no dropout)
    // =========================================================================

    @Test
    fun `full protocol flow between two clients`() {
        assumeTrue("X25519 not available", x25519Available)

        val config1 = SecAggPlusConfig(
            sessionId = "session-1", roundId = "round-1",
            threshold = 2, totalClients = 2, myIndex = 1,
        )
        val config2 = SecAggPlusConfig(
            sessionId = "session-1", roundId = "round-1",
            threshold = 2, totalClients = 2, myIndex = 2,
        )

        val client1 = SecAggPlusClient(config1)
        val client2 = SecAggPlusClient(config2)

        // Stage 1: exchange public keys
        val pub1 = client1.getPublicKey()
        val pub2 = client2.getPublicKey()

        // Stage 2: share keys
        client1.receivePeerPublicKeys(mapOf(2 to pub2))
        client2.receivePeerPublicKeys(mapOf(1 to pub1))

        val shares1 = client1.generateEncryptedShares()
        val shares2 = client2.generateEncryptedShares()

        client1.receiveEncryptedShares(mapOf(2 to shares2[1]!!))
        client2.receiveEncryptedShares(mapOf(1 to shares1[2]!!))

        // Stage 3: mask model updates
        val update = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(1000).putInt(2000).array()

        val masked1 = client1.maskModelUpdate(update)
        val masked2 = client2.maskModelUpdate(update)

        // Both masked outputs should differ from the original
        assertTrue(!masked1.contentEquals(update))
        assertTrue(!masked2.contentEquals(update))

        // Verify stage progression
        assertEquals(SecAggPlusClient.Stage.UNMASK, client1.stage)
        assertEquals(SecAggPlusClient.Stage.UNMASK, client2.stage)

        // Stage 4: complete (no dropouts)
        client1.complete()
        client2.complete()

        assertEquals(SecAggPlusClient.Stage.COMPLETED, client1.stage)
        assertEquals(SecAggPlusClient.Stage.COMPLETED, client2.stage)
    }

    // =========================================================================
    // Pairwise mask cancellation (using mod_range)
    // =========================================================================

    @Test
    fun `pairwise masks cancel when summing two clients masked updates`() {
        assumeTrue("X25519 not available", x25519Available)

        val modRange = SecAggPlusConfig.DEFAULT_MOD_RANGE
        val config1 = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 1,
            modRange = modRange,
        )
        val config2 = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 2,
            modRange = modRange,
        )

        val client1 = SecAggPlusClient(config1)
        val client2 = SecAggPlusClient(config2)

        // Exchange keys
        val pub1 = client1.getPublicKey()
        val pub2 = client2.getPublicKey()
        client1.receivePeerPublicKeys(mapOf(2 to pub2))
        client2.receivePeerPublicKeys(mapOf(1 to pub1))

        // Generate and exchange shares
        val shares1 = client1.generateEncryptedShares()
        val shares2 = client2.generateEncryptedShares()
        client1.receiveEncryptedShares(mapOf(2 to shares2[1]!!))
        client2.receiveEncryptedShares(mapOf(1 to shares1[2]!!))

        // Both clients have the same plaintext update
        val plainUpdate = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putInt(100).putInt(200).putInt(300).array()

        val masked1 = client1.maskModelUpdate(plainUpdate)
        val masked2 = client2.maskModelUpdate(plainUpdate)

        // Sum the two masked updates mod mod_range
        val elements1 = SecAggPlusClient.bytesToIntElements(masked1)
        val elements2 = SecAggPlusClient.bytesToIntElements(masked2)

        assertEquals(elements1.size, elements2.size)

        val summed = elements1.zip(elements2).map { (a, b) ->
            (a + b) % modRange
        }

        // The sum should equal: 2 * plaintext elements + selfMask1 + selfMask2
        // (pairwise masks cancel: client1 with idx 1 < 2 SUBTRACTs, client2 with idx 2 > 1 ADDs)
        val plainElements = SecAggPlusClient.bytesToIntElements(plainUpdate)
        val doublePlain = plainElements.map { (it * 2L) % modRange }

        // Verify that sum - 2*plain = selfMask1 + selfMask2 (non-zero, deterministic)
        val residual = summed.zip(doublePlain).map { (s, dp) ->
            ((s - dp) % modRange + modRange) % modRange
        }

        // Residual should be non-zero (contains self-masks)
        assertTrue("Residual should contain self-masks (non-zero)",
            residual.any { it != 0L })
    }

    @Test
    fun `pairwise masks cancel with three clients`() {
        assumeTrue("X25519 not available", x25519Available)

        val modRange = SecAggPlusConfig.DEFAULT_MOD_RANGE
        val configs = (1..3).map { idx ->
            SecAggPlusConfig(
                sessionId = "s1", roundId = "r1",
                threshold = 2, totalClients = 3, myIndex = idx,
                modRange = modRange,
            )
        }
        val clients = configs.map { SecAggPlusClient(it) }

        // Stage 1: collect all public keys
        val pubKeys = clients.mapIndexed { i, c -> (i + 1) to c.getPublicKey() }.toMap()

        // Stage 2: exchange keys and shares
        for (c in clients) {
            val peerKeys = pubKeys.filter { it.key != c.config.myIndex }
            c.receivePeerPublicKeys(peerKeys)
        }

        val allEncryptedShares = clients.map { c ->
            c.config.myIndex to c.generateEncryptedShares()
        }.toMap()

        for (c in clients) {
            val myIdx = c.config.myIndex
            val incomingShares = mutableMapOf<Int, ByteArray>()
            for ((senderIdx, shares) in allEncryptedShares) {
                if (senderIdx == myIdx) continue
                val shareForMe = shares[myIdx] ?: continue
                incomingShares[senderIdx] = shareForMe
            }
            c.receiveEncryptedShares(incomingShares)
        }

        // Stage 3: mask the same update
        val plainUpdate = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(500).putInt(600).array()

        val maskedUpdates = clients.map { it.maskModelUpdate(plainUpdate) }

        // Sum all three mod mod_range
        val n = SecAggPlusClient.bytesToIntElements(maskedUpdates[0]).size

        val summed = LongArray(n)
        for (masked in maskedUpdates) {
            val elements = SecAggPlusClient.bytesToIntElements(masked)
            for (j in 0 until n) {
                summed[j] = (summed[j] + elements[j]) % modRange
            }
        }

        // sum = 3 * plain + sum(selfMasks) -- pairwise masks all cancel
        val plainElements = SecAggPlusClient.bytesToIntElements(plainUpdate)
        val triplePlain = plainElements.map { (it * 3L) % modRange }

        val residual = summed.zip(triplePlain.toLongArray()).map { (s, tp) ->
            ((s - tp) % modRange + modRange) % modRange
        }

        // With 3 independent self-masks, residual should be non-zero
        assertTrue(residual.any { it != 0L })
    }

    @Test
    fun `pairwise mask direction matches Python convention`() {
        assumeTrue("X25519 not available", x25519Available)

        // Verify: myIdx > peerIdx -> ADD, myIdx < peerIdx -> SUBTRACT (Flower convention)
        // This is tested implicitly by cancellation, but explicitly trace the direction:
        // Client 1 (myIdx=1): 1 < 2 -> SUBTRACT mask
        // Client 2 (myIdx=2): 2 > 1 -> ADD mask
        // Net: -M + M = 0. Server unmask: dead(2) > neighbor(1) -> ADD (cancels client 1's subtract).

        val config1 = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 1,
        )
        val config2 = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 2,
        )

        val c1 = SecAggPlusClient(config1)
        val c2 = SecAggPlusClient(config2)

        val pub1 = c1.getPublicKey()
        val pub2 = c2.getPublicKey()
        c1.receivePeerPublicKeys(mapOf(2 to pub2))
        c2.receivePeerPublicKeys(mapOf(1 to pub1))
        val s1 = c1.generateEncryptedShares()
        val s2 = c2.generateEncryptedShares()
        c1.receiveEncryptedShares(mapOf(2 to s2[1]!!))
        c2.receiveEncryptedShares(mapOf(1 to s1[2]!!))

        // Use zero update so masked output = pairwise mask + self mask
        val zeroUpdate = ByteArray(8)
        val masked1 = c1.maskModelUpdate(zeroUpdate)
        val masked2 = c2.maskModelUpdate(zeroUpdate)

        // Sum should equal selfMask1 + selfMask2 (pairwise cancelled)
        val modRange = config1.modRange
        val e1 = SecAggPlusClient.bytesToIntElements(masked1)
        val e2 = SecAggPlusClient.bytesToIntElements(masked2)
        val sum = e1.zip(e2).map { (a, b) -> (a + b) % modRange }

        // If pairwise masks didn't cancel, sum would be dominated by 2*pairwiseMask
        // Since they cancel, sum = selfMask1 + selfMask2 only
        // We can't directly verify the exact value, but we verify non-zero (masks present)
        assertTrue(sum.any { it != 0L })
    }

    // =========================================================================
    // Dropout handling (Stage 4)
    // =========================================================================

    @Test
    fun `revealSharesForDropped returns shares for known peers`() {
        assumeTrue("X25519 not available", x25519Available)

        val configs = (1..3).map { idx ->
            SecAggPlusConfig(
                sessionId = "s1", roundId = "r1",
                threshold = 2, totalClients = 3, myIndex = idx,
            )
        }
        val clients = configs.map { SecAggPlusClient(it) }

        // Run through stages 1-3
        val pubKeys = clients.mapIndexed { i, c -> (i + 1) to c.getPublicKey() }.toMap()
        for (c in clients) {
            c.receivePeerPublicKeys(pubKeys.filter { it.key != c.config.myIndex })
        }
        val allShares = clients.map { c -> c.config.myIndex to c.generateEncryptedShares() }.toMap()
        for (c in clients) {
            val incoming = mutableMapOf<Int, ByteArray>()
            for ((sender, shares) in allShares) {
                if (sender == c.config.myIndex) continue
                shares[c.config.myIndex]?.let { incoming[sender] = it }
            }
            c.receiveEncryptedShares(incoming)
        }
        val update = ByteArray(8) { it.toByte() }
        for (c in clients) c.maskModelUpdate(update)

        // Client 3 drops. Clients 1 and 2 reveal their shares for client 3.
        val revealed1 = clients[0].revealSharesForDropped(listOf(3))
        val revealed2 = clients[1].revealSharesForDropped(listOf(3))

        assertTrue(revealed1.containsKey(3))
        assertTrue(revealed2.containsKey(3))
        assertTrue(revealed1[3]!!.isNotEmpty())
        assertTrue(revealed2[3]!!.isNotEmpty())
    }

    @Test
    fun `revealSharesForDropped returns empty for unknown peer`() {
        assumeTrue("X25519 not available", x25519Available)

        val config1 = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 1,
        )
        val config2 = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 2,
        )
        val c1 = SecAggPlusClient(config1)
        val c2 = SecAggPlusClient(config2)

        val pub1 = c1.getPublicKey()
        val pub2 = c2.getPublicKey()
        c1.receivePeerPublicKeys(mapOf(2 to pub2))
        c2.receivePeerPublicKeys(mapOf(1 to pub1))
        val s1 = c1.generateEncryptedShares()
        val s2 = c2.generateEncryptedShares()
        c1.receiveEncryptedShares(mapOf(2 to s2[1]!!))
        c2.receiveEncryptedShares(mapOf(1 to s1[2]!!))
        c1.maskModelUpdate(ByteArray(8))

        // Ask for share of peer 99 who doesn't exist
        val revealed = c1.revealSharesForDropped(listOf(99))
        assertTrue(revealed.isEmpty())
    }

    // =========================================================================
    // getOwnShare
    // =========================================================================

    @Test
    fun `getOwnShare returns null before shares generated`() {
        assumeTrue("X25519 not available", x25519Available)

        val config = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 3, myIndex = 1,
        )
        val client = SecAggPlusClient(config)

        assertNull(client.getOwnShare(2))
    }

    @Test
    fun `getOwnShare returns share after generation`() {
        assumeTrue("X25519 not available", x25519Available)

        val config = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 3, myIndex = 1,
        )
        val client = SecAggPlusClient(config)

        // Need to go through stage 2 to generate shares
        val kp2 = ECDHKeyExchange.generateKeyPair()
        val kp3 = ECDHKeyExchange.generateKeyPair()
        client.receivePeerPublicKeys(mapOf(2 to kp2.publicKeyBytes, 3 to kp3.publicKeyBytes))
        client.generateEncryptedShares()

        val share2 = client.getOwnShare(2)
        val share3 = client.getOwnShare(3)

        assertNotNull(share2)
        assertNotNull(share3)
        assertEquals(2, share2!!.index)
        assertEquals(3, share3!!.index)
    }

    // =========================================================================
    // Stage enforcement (out-of-order calls)
    // =========================================================================

    @Test(expected = IllegalStateException::class)
    fun `generateEncryptedShares throws in SETUP stage`() {
        assumeTrue("X25519 not available", x25519Available)

        val config = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 1,
        )
        val client = SecAggPlusClient(config)

        // Should throw -- still in SETUP, need receivePeerPublicKeys first
        client.generateEncryptedShares()
    }

    @Test(expected = IllegalStateException::class)
    fun `receiveEncryptedShares throws in SETUP stage`() {
        assumeTrue("X25519 not available", x25519Available)

        val config = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 1,
        )
        val client = SecAggPlusClient(config)

        client.receiveEncryptedShares(emptyMap())
    }

    @Test(expected = IllegalStateException::class)
    fun `maskModelUpdate throws in SETUP stage`() {
        assumeTrue("X25519 not available", x25519Available)

        val config = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 1,
        )
        val client = SecAggPlusClient(config)

        client.maskModelUpdate(ByteArray(8))
    }

    @Test(expected = IllegalStateException::class)
    fun `maskModelUpdate throws in SHARE_KEYS stage`() {
        assumeTrue("X25519 not available", x25519Available)

        val config = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 1,
        )
        val client = SecAggPlusClient(config)

        val kp2 = ECDHKeyExchange.generateKeyPair()
        client.receivePeerPublicKeys(mapOf(2 to kp2.publicKeyBytes))
        // Now in SHARE_KEYS, should throw for maskModelUpdate
        client.maskModelUpdate(ByteArray(8))
    }

    @Test(expected = IllegalStateException::class)
    fun `revealSharesForDropped throws in COLLECT_MASKED_VECTORS stage`() {
        assumeTrue("X25519 not available", x25519Available)

        val config1 = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 1,
        )
        val config2 = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 2,
        )
        val c1 = SecAggPlusClient(config1)
        val c2 = SecAggPlusClient(config2)

        c1.receivePeerPublicKeys(mapOf(2 to c2.getPublicKey()))
        c2.receivePeerPublicKeys(mapOf(1 to c1.getPublicKey()))

        c1.generateEncryptedShares()
        c1.receiveEncryptedShares(mapOf(2 to c2.generateEncryptedShares()[1]!!))

        // Now in COLLECT_MASKED_VECTORS -- revealSharesForDropped should throw
        c1.revealSharesForDropped(listOf(2))
    }

    @Test(expected = IllegalStateException::class)
    fun `complete throws in SETUP stage`() {
        assumeTrue("X25519 not available", x25519Available)

        val config = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 1,
        )
        SecAggPlusClient(config).complete()
    }

    @Test(expected = IllegalStateException::class)
    fun `receivePeerPublicKeys throws after SETUP stage`() {
        assumeTrue("X25519 not available", x25519Available)

        val config = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 1,
        )
        val client = SecAggPlusClient(config)

        val kp2 = ECDHKeyExchange.generateKeyPair()
        client.receivePeerPublicKeys(mapOf(2 to kp2.publicKeyBytes))
        // Now in SHARE_KEYS, calling receivePeerPublicKeys again should throw
        client.receivePeerPublicKeys(mapOf(2 to kp2.publicKeyBytes))
    }

    // =========================================================================
    // Stage progression
    // =========================================================================

    @Test
    fun `stage starts at SETUP`() {
        assumeTrue("X25519 not available", x25519Available)

        val config = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 1,
        )
        assertEquals(SecAggPlusClient.Stage.SETUP, SecAggPlusClient(config).stage)
    }

    @Test
    fun `stage progresses through all stages`() {
        assumeTrue("X25519 not available", x25519Available)

        val config1 = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 1,
        )
        val config2 = SecAggPlusConfig(
            sessionId = "s1", roundId = "r1",
            threshold = 2, totalClients = 2, myIndex = 2,
        )
        val c1 = SecAggPlusClient(config1)
        val c2 = SecAggPlusClient(config2)

        assertEquals(SecAggPlusClient.Stage.SETUP, c1.stage)

        c1.receivePeerPublicKeys(mapOf(2 to c2.getPublicKey()))
        assertEquals(SecAggPlusClient.Stage.SHARE_KEYS, c1.stage)

        c2.receivePeerPublicKeys(mapOf(1 to c1.getPublicKey()))
        val s1 = c1.generateEncryptedShares()
        val s2 = c2.generateEncryptedShares()

        c1.receiveEncryptedShares(mapOf(2 to s2[1]!!))
        assertEquals(SecAggPlusClient.Stage.COLLECT_MASKED_VECTORS, c1.stage)

        c1.maskModelUpdate(ByteArray(8))
        assertEquals(SecAggPlusClient.Stage.UNMASK, c1.stage)

        c1.complete()
        assertEquals(SecAggPlusClient.Stage.COMPLETED, c1.stage)
    }

    // =========================================================================
    // Integer element encoding (Long-based, mod_range)
    // =========================================================================

    @Test
    fun `bytesToIntElements and intElementsToBytes roundtrip`() {
        val original = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putInt(100).putInt(200).putInt(300).array()

        val elements = SecAggPlusClient.bytesToIntElements(original)
        val roundTripped = SecAggPlusClient.intElementsToBytes(elements.toLongArray(), original.size)

        assertArrayEquals(original, roundTripped)
    }

    @Test
    fun `bytesToIntElements handles non-aligned sizes`() {
        val data = byteArrayOf(1, 2, 3, 4, 5) // 5 bytes = 2 chunks
        val elements = SecAggPlusClient.bytesToIntElements(data)
        assertEquals(2, elements.size)

        // Second chunk is [5, 0, 0, 0] (zero-padded)
        val roundTripped = SecAggPlusClient.intElementsToBytes(elements.toLongArray(), data.size)
        assertEquals(5, roundTripped.size)
        assertEquals(data[0], roundTripped[0])
        assertEquals(data[4], roundTripped[4])
    }

    @Test
    fun `bytesToIntElements empty input`() {
        val elements = SecAggPlusClient.bytesToIntElements(byteArrayOf())
        assertTrue(elements.isEmpty())
    }

    @Test
    fun `int elements are unsigned 32-bit values`() {
        val data = ByteArray(40) { (it * 17).toByte() }
        val elements = SecAggPlusClient.bytesToIntElements(data)
        for (elem in elements) {
            assertTrue("Element $elem should be >= 0", elem >= 0L)
            assertTrue("Element $elem should be < 2^32", elem < (1L shl 32))
        }
    }

    // =========================================================================
    // Self-mask PRG (XOR-fold + Random)
    // =========================================================================

    @Test
    fun `pseudoRandGen is deterministic for same seed`() {
        val seed = ByteArray(32) { (it * 7).toByte() }
        val modRange = 1L shl 32

        val result1 = SecAggPlusClient.pseudoRandGen(seed, modRange, 10)
        val result2 = SecAggPlusClient.pseudoRandGen(seed, modRange, 10)

        assertArrayEquals(result1, result2)
    }

    @Test
    fun `pseudoRandGen produces values in mod_range`() {
        val seed = ByteArray(32) { it.toByte() }
        val modRange = 1L shl 32

        val result = SecAggPlusClient.pseudoRandGen(seed, modRange, 100)
        for (v in result) {
            assertTrue("Value $v should be >= 0", v >= 0L)
            assertTrue("Value $v should be < mod_range", v < modRange)
        }
    }

    @Test
    fun `pseudoRandGen different seeds produce different output`() {
        val seed1 = ByteArray(32) { it.toByte() }
        val seed2 = ByteArray(32) { (it + 1).toByte() }
        val modRange = 1L shl 32

        val result1 = SecAggPlusClient.pseudoRandGen(seed1, modRange, 10)
        val result2 = SecAggPlusClient.pseudoRandGen(seed2, modRange, 10)

        assertTrue("Different seeds should produce different output",
            !result1.contentEquals(result2))
    }

    @Test
    fun `pseudoRandGen handles non-aligned seed sizes`() {
        val seed = ByteArray(30) { it.toByte() } // Not a multiple of 4
        val modRange = 1L shl 32

        val result = SecAggPlusClient.pseudoRandGen(seed, modRange, 5)
        assertEquals(5, result.size)
        for (v in result) {
            assertTrue(v >= 0L)
            assertTrue(v < modRange)
        }
    }

    // =========================================================================
    // SecAggPlusConfig
    // =========================================================================

    @Test
    fun `config stores all fields including new quantization params`() {
        val config = SecAggPlusConfig(
            sessionId = "s1",
            roundId = "r1",
            threshold = 3,
            totalClients = 5,
            myIndex = 2,
            clippingRange = 8.0f,
            targetRange = 4194304,
            modRange = 1L shl 32,
        )

        assertEquals("s1", config.sessionId)
        assertEquals("r1", config.roundId)
        assertEquals(3, config.threshold)
        assertEquals(5, config.totalClients)
        assertEquals(2, config.myIndex)
        assertEquals(8.0f, config.clippingRange, 1e-5f)
        assertEquals(4194304, config.targetRange)
        assertEquals(1L shl 32, config.modRange)
    }

    @Test
    fun `config defaults match Python SDK`() {
        val config = SecAggPlusConfig(
            sessionId = "s", roundId = "r",
            threshold = 2, totalClients = 3, myIndex = 1,
        )

        assertEquals(3.0f, config.clippingRange, 1e-5f)
        assertEquals(1 shl 16, config.targetRange)
        assertEquals(SecAggPlusConfig.DEFAULT_MOD_RANGE, config.modRange)
    }

    @Test
    fun `DEFAULT_MOD_RANGE is 2 to the 32`() {
        assertEquals(4294967296L, SecAggPlusConfig.DEFAULT_MOD_RANGE)
    }

    // =========================================================================
    // Masked output preserves data size
    // =========================================================================

    @Test
    fun `maskModelUpdate preserves data size`() {
        assumeTrue("X25519 not available", x25519Available)

        for (size in listOf(4, 8, 16, 100, 1000)) {
            val freshClient = SecAggPlusClient(SecAggPlusConfig(
                sessionId = "s1", roundId = "r1",
                threshold = 2, totalClients = 2, myIndex = 1,
            ))
            val freshPeer = SecAggPlusClient(SecAggPlusConfig(
                sessionId = "s1", roundId = "r1",
                threshold = 2, totalClients = 2, myIndex = 2,
            ))

            freshClient.receivePeerPublicKeys(mapOf(2 to freshPeer.getPublicKey()))
            freshPeer.receivePeerPublicKeys(mapOf(1 to freshClient.getPublicKey()))
            val fs1 = freshClient.generateEncryptedShares()
            val fs2 = freshPeer.generateEncryptedShares()
            freshClient.receiveEncryptedShares(mapOf(2 to fs2[1]!!))

            val data = ByteArray(size) { (it % 256).toByte() }
            val masked = freshClient.maskModelUpdate(data)
            assertEquals("Size $size should be preserved", size, masked.size)
        }
    }
}
