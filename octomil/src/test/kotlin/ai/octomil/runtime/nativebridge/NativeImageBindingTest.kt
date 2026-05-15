package ai.octomil.runtime.nativebridge

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.errors.OctomilException
import ai.octomil.generated.RuntimeCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for the optional ABI-11 image bindings (v0.1.12).
 *
 * Coverage:
 * 1. [NativeImageMime] codes match the OCT_IMAGE_MIME_* numeric values
 *    documented in include/octomil/runtime.h.
 * 2. [NativeRuntimeBridge.sendImage] enforces ABI minor >= 11 AND
 *    [RuntimeCapability.EMBEDDINGS_IMAGE] presence at the inner gate.
 * 3. Runtime open does NOT require minor 11 — [NativeRuntimeBridge.REQUIRED_ABI_MINOR]
 *    stays at 10 so a v0.1.10 runtime still loads cleanly. Only the
 *    image-send path hits the 11-floor.
 *
 * Critical: there is NO integration test that expects a successful
 * image-send round-trip. The runtime stub returns UNSUPPORTED until the
 * adapter PR lands; SDK image-send is BLOCKED at the capability level.
 */
class NativeImageBindingTest {

    // ── MIME enum (numeric ABI) ──────────────────────────────────────────────

    @Test
    fun `mime enum codes match runtime ABI numeric values`() {
        assertEquals(0, NativeImageMime.UNKNOWN.code)
        assertEquals(1, NativeImageMime.PNG.code)
        assertEquals(2, NativeImageMime.JPEG.code)
        assertEquals(3, NativeImageMime.WEBP.code)
        assertEquals(4, NativeImageMime.RGB8.code)
    }

    @Test
    fun `mime fromCode round-trips every known value`() {
        for (mime in NativeImageMime.entries) {
            assertEquals(mime, NativeImageMime.fromCode(mime.code))
        }
        assertNull(NativeImageMime.fromCode(99))
        assertNull(NativeImageMime.fromCode(-1))
    }

    // ── NativeImageView construction ─────────────────────────────────────────

    @Test
    fun `imageView rejects UNKNOWN mime sentinel`() {
        try {
            NativeImageView(bytes = byteArrayOf(1, 2, 3), mime = NativeImageMime.UNKNOWN)
            fail("expected IllegalArgumentException for UNKNOWN mime sentinel")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("UNKNOWN"))
        }
    }

    @Test
    fun `imageView rejects empty buffer`() {
        try {
            NativeImageView(bytes = ByteArray(0), mime = NativeImageMime.PNG)
            fail("expected IllegalArgumentException for empty buffer")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("byteLength"))
        }
    }

    @Test
    fun `imageView rejects byteLength exceeding buffer size`() {
        try {
            NativeImageView(bytes = byteArrayOf(1, 2), byteLength = 99, mime = NativeImageMime.JPEG)
            fail("expected IllegalArgumentException for oversized byteLength")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("byteLength"))
        }
    }

    // ── sendImage gate: ABI minor floor ──────────────────────────────────────

    @Test
    fun `sendImage throws RUNTIME_UNAVAILABLE when runtime advertises ABI minor 10`() {
        val fake = ImageBindingFakeJni()
        val bridge = NativeRuntimeBridge(fake)
        val session = openSession(bridge)
        val image = NativeImageView(bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), mime = NativeImageMime.PNG)

        try {
            bridge.sendImage(
                session = session,
                image = image,
                runtimeAbiMinor = 10,
                capabilities = setOf(RuntimeCapability.EMBEDDINGS_IMAGE),
            )
            fail("expected OctomilException when ABI minor < 11")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.RUNTIME_UNAVAILABLE, e.errorCode)
            assertTrue(e.message!!.contains("embeddings.image is not available"))
            assertTrue(e.message!!.contains("ABI minor 10"))
        }
        // The lower-level JNI sendImage MUST NOT be called when the gate fails.
        assertFalse(fake.calls.any { it.startsWith("sendImage") })
    }

    @Test
    fun `sendImage throws RUNTIME_UNAVAILABLE when capability is not advertised`() {
        val fake = ImageBindingFakeJni()
        val bridge = NativeRuntimeBridge(fake)
        val session = openSession(bridge)
        val image = NativeImageView(bytes = byteArrayOf(1, 2, 3), mime = NativeImageMime.JPEG)

        try {
            bridge.sendImage(
                session = session,
                image = image,
                runtimeAbiMinor = 11,
                capabilities = setOf(RuntimeCapability.EMBEDDINGS_TEXT),
            )
            fail("expected OctomilException when capability is missing")
        } catch (e: OctomilException) {
            assertEquals(OctomilErrorCode.RUNTIME_UNAVAILABLE, e.errorCode)
            assertTrue(e.message!!.contains("embeddings.image"))
            assertTrue(e.message!!.contains("capability not advertised"))
        }
        assertFalse(fake.calls.any { it.startsWith("sendImage") })
    }

    @Test
    fun `sendImage routes to JNI when both gates pass`() {
        val fake = ImageBindingFakeJni()
        val bridge = NativeRuntimeBridge(fake)
        val session = openSession(bridge)
        val image = NativeImageView(bytes = byteArrayOf(1, 2, 3, 4), mime = NativeImageMime.JPEG)

        // NOTE: this test asserts wiring, NOT that the runtime actually supports
        // image embeddings end-to-end. The session-level wire status is honored
        // straight through — no integration claim is made. The runtime adapter is
        // still BLOCKED; this only covers the binding bridge call path.
        val result = bridge.sendImage(
            session = session,
            image = image,
            runtimeAbiMinor = 11,
            capabilities = setOf(RuntimeCapability.EMBEDDINGS_IMAGE),
        )
        assertTrue(result is NativeRuntimeResult.Success)
        assertTrue(fake.calls.contains("sendImage:4:2"))
    }

    @Test
    fun `sendImage with both gates passing surfaces JNI UNSUPPORTED as Error`() {
        // Simulates a runtime that advertises ABI 11 and the capability but whose
        // session-level send_image returns UNSUPPORTED — e.g. the v0.1.12 stub
        // before the SigLIP adapter PR lands. The wrapper must NOT mask this.
        val fake = ImageBindingFakeJni(
            sendImageWire = NativeRuntimeStatusWire(
                statusCode = NativeRuntimeStatus.UNSUPPORTED.code,
                message = "image adapter not yet implemented",
            ),
        )
        val bridge = NativeRuntimeBridge(fake)
        val session = openSession(bridge)

        val result = bridge.sendImage(
            session = session,
            image = NativeImageView(bytes = byteArrayOf(1), mime = NativeImageMime.RGB8),
            runtimeAbiMinor = 11,
            capabilities = setOf(RuntimeCapability.EMBEDDINGS_IMAGE),
        )
        assertTrue(result is NativeRuntimeResult.Error)
        val error = (result as NativeRuntimeResult.Error).error
        assertEquals(NativeRuntimeStatus.UNSUPPORTED, error.status)
        assertEquals("image adapter not yet implemented", error.message)
    }

    // ── Runtime open floor stays at 10 ───────────────────────────────────────

    @Test
    fun `runtime open does not require ABI minor 11 — image floor is inner only`() {
        val fake = ImageBindingFakeJni()  // advertises abi minor = 10
        val bridge = NativeRuntimeBridge(fake)

        val opened = bridge.open()

        assertTrue(
            "open() must succeed against a minor-10 runtime; image-send 11-floor is an INNER gate only",
            opened is NativeRuntimeResult.Success,
        )
    }

    @Test
    fun `REQUIRED_ABI_MINOR stays at 10`() {
        // Sentinel test: bumping the hard load-time floor to 11 would be a
        // breaking change for every v0.1.10 deployment, and the v0.1.12
        // runtime stub still rejects every embeddings.image call. The
        // image-send path is gated by REQUIRED_ABI_MINOR_FOR_IMAGE instead.
        assertEquals(10, NativeRuntimeBridge.REQUIRED_ABI_MINOR)
        assertEquals(11, NativeRuntimeBridge.REQUIRED_ABI_MINOR_FOR_IMAGE)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun openSession(bridge: NativeRuntimeBridge): NativeSession {
        val runtime = bridge.open()
        assertTrue(runtime is NativeRuntimeResult.Success)
        val model = (runtime as NativeRuntimeResult.Success).value.openModel()
        assertTrue(model is NativeRuntimeResult.Success)
        val session = (model as NativeRuntimeResult.Success).value.openSession(NativeSessionConfig())
        assertTrue(session is NativeRuntimeResult.Success)
        return (session as NativeRuntimeResult.Success).value
    }
}

/**
 * Minimal NativeRuntimeJni fake for image-binding tests. Advertises ABI minor 10
 * by default so the runtime open path matches the hard floor; image-send tests
 * pass an explicit abi-minor override into [NativeRuntimeBridge.sendImage].
 */
private class ImageBindingFakeJni(
    private val sendImageWire: NativeRuntimeStatusWire = NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code),
) : NativeRuntimeJni {

    val calls = mutableListOf<String>()

    override fun ensureAvailable(): NativeRuntimeAvailability = NativeRuntimeAvailability.Available

    override fun abiVersion(): NativeRuntimeAbiVersion = NativeRuntimeAbiVersion(0, 10, 0)

    override fun open(config: NativeRuntimeConfig): NativeRuntimeOpenWire {
        calls += "open"
        return NativeRuntimeOpenWire(statusCode = NativeRuntimeStatus.OK.code, handle = 11L, message = null)
    }

    override fun capabilities(handle: Long): NativeRuntimeCapabilitiesWire = NativeRuntimeCapabilitiesWire(
        statusCode = NativeRuntimeStatus.OK.code,
        message = null,
        supportedEngines = arrayOf("fake"),
        supportedCapabilities = emptyArray(),
        supportedArchs = arrayOf("android-arm64"),
        ramTotalBytes = 0L,
        ramAvailableBytes = 0L,
        hasAppleSilicon = false,
        hasCuda = false,
        hasMetal = false,
    )

    override fun cacheIntrospect(runtimeHandle: Long, bufferBytes: Int): NativeRuntimeCacheIntrospectWire =
        NativeRuntimeCacheIntrospectWire(statusCode = NativeRuntimeStatus.OK.code, json = "{}")

    override fun modelOpen(runtimeHandle: Long, config: NativeModelConfig): NativeModelOpenWire {
        calls += "modelOpen"
        return NativeModelOpenWire(statusCode = NativeRuntimeStatus.OK.code, handle = 21L)
    }

    override fun modelWarm(modelHandle: Long): NativeRuntimeStatusWire =
        NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)

    override fun modelClose(modelHandle: Long) {
        calls += "modelClose"
    }

    override fun sessionOpen(
        runtimeHandle: Long,
        modelHandle: Long,
        config: NativeSessionConfig,
    ): NativeSessionOpenWire {
        calls += "sessionOpen"
        return NativeSessionOpenWire(statusCode = NativeRuntimeStatus.OK.code, handle = 31L)
    }

    override fun sessionOpenModelFree(
        runtimeHandle: Long,
        config: NativeSessionConfig,
    ): NativeSessionOpenWire {
        calls += "sessionOpenModelFree"
        return NativeSessionOpenWire(statusCode = NativeRuntimeStatus.OK.code, handle = 31L)
    }

    override fun sessionSendAudio(sessionHandle: Long, audio: NativeAudioView): NativeRuntimeStatusWire =
        NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)

    override fun sessionSendText(sessionHandle: Long, text: String): NativeRuntimeStatusWire =
        NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)

    override fun sessionSendImage(sessionHandle: Long, image: NativeImageView): NativeRuntimeStatusWire {
        calls += "sendImage:${image.byteLength}:${image.mime.code}"
        return sendImageWire
    }

    override fun sessionPollEvent(sessionHandle: Long, timeoutMs: Int): NativeSessionPollWire =
        NativeSessionPollWire(statusCode = NativeRuntimeStatus.OK.code, event = null)

    override fun sessionCancel(sessionHandle: Long): NativeRuntimeStatusWire =
        NativeRuntimeStatusWire(NativeRuntimeStatus.OK.code)

    override fun sessionClose(sessionHandle: Long) {
        calls += "sessionClose"
    }

    override fun lastError(handle: Long): String? = null

    override fun lastThreadError(): String? = null

    override fun close(handle: Long) {
        calls += "close"
    }
}
