package ai.octomil.runtime.nativebridge

internal interface NativeRuntimeJni {
    fun ensureAvailable(): NativeRuntimeAvailability
    fun abiVersion(): NativeRuntimeAbiVersion
    fun open(config: NativeRuntimeConfig): NativeRuntimeOpenWire
    fun capabilities(handle: Long): NativeRuntimeCapabilitiesWire
    fun cacheIntrospect(runtimeHandle: Long, bufferBytes: Int): NativeRuntimeCacheIntrospectWire
    fun modelOpen(runtimeHandle: Long, config: NativeModelConfig): NativeModelOpenWire
    fun modelWarm(modelHandle: Long): NativeRuntimeStatusWire
    fun modelClose(modelHandle: Long)
    fun sessionOpen(runtimeHandle: Long, modelHandle: Long, config: NativeSessionConfig): NativeSessionOpenWire
    fun sessionOpenModelFree(runtimeHandle: Long, config: NativeSessionConfig): NativeSessionOpenWire
    fun sessionSendAudio(sessionHandle: Long, audio: NativeAudioView): NativeRuntimeStatusWire
    fun sessionSendText(sessionHandle: Long, text: String): NativeRuntimeStatusWire
    fun sessionPollEvent(sessionHandle: Long, timeoutMs: Int): NativeSessionPollWire
    fun sessionCancel(sessionHandle: Long): NativeRuntimeStatusWire
    fun sessionClose(sessionHandle: Long)
    fun lastError(handle: Long): String?
    fun lastThreadError(): String?
    fun close(handle: Long)
}

internal sealed class NativeRuntimeAvailability {
    data object Available : NativeRuntimeAvailability()

    data class Unavailable(
        val libraryName: String,
        val message: String,
        val cause: Throwable,
    ) : NativeRuntimeAvailability() {
        fun toSkip(): NativeRuntimeSkip =
            NativeRuntimeSkip(
                message = "Native runtime JNI library '$libraryName' is unavailable: $message",
                cause = cause,
            )
    }
}

internal class SystemNativeRuntimeJni(
    private val libraryName: String,
) : NativeRuntimeJni {
    @Volatile
    private var availability: NativeRuntimeAvailability? = null

    override fun ensureAvailable(): NativeRuntimeAvailability {
        val cached = availability
        if (cached != null) return cached

        return synchronized(this) {
            availability ?: loadLibrary().also { availability = it }
        }
    }

    override fun abiVersion(): NativeRuntimeAbiVersion {
        ensureAvailable()
        return NativeRuntimeAbiVersion(
            major = nativeAbiVersionMajor(),
            minor = nativeAbiVersionMinor(),
            patch = nativeAbiVersionPatch(),
        )
    }

    override fun open(config: NativeRuntimeConfig): NativeRuntimeOpenWire {
        ensureAvailable()
        return nativeOpen(
            artifactRoot = config.artifactRoot,
            maxSessions = config.maxSessions,
        )
    }

    override fun capabilities(handle: Long): NativeRuntimeCapabilitiesWire {
        ensureAvailable()
        return nativeCapabilities(handle)
    }

    override fun cacheIntrospect(runtimeHandle: Long, bufferBytes: Int): NativeRuntimeCacheIntrospectWire {
        ensureAvailable()
        return nativeCacheIntrospect(runtimeHandle, bufferBytes)
    }

    override fun modelOpen(runtimeHandle: Long, config: NativeModelConfig): NativeModelOpenWire {
        ensureAvailable()
        return nativeModelOpen(
            runtimeHandle = runtimeHandle,
            modelUri = config.modelUri,
            artifactDigest = config.artifactDigest,
            engineHint = config.engineHint,
            policyPreset = config.policyPreset,
            acceleratorPref = config.acceleratorPref,
            ramBudgetBytes = config.ramBudgetBytes,
            userData = config.userData,
        )
    }

    override fun modelWarm(modelHandle: Long): NativeRuntimeStatusWire {
        ensureAvailable()
        return nativeModelWarm(modelHandle)
    }

    override fun modelClose(modelHandle: Long) {
        ensureAvailable()
        nativeModelClose(modelHandle)
    }

    override fun sessionOpen(runtimeHandle: Long, modelHandle: Long, config: NativeSessionConfig): NativeSessionOpenWire {
        ensureAvailable()
        return nativeSessionOpen(
            runtimeHandle = runtimeHandle,
            modelHandle = modelHandle,
            capability = config.capability?.code,
            modelUri = config.modelUri,
            locality = config.locality,
            policyPreset = config.policyPreset,
            speakerId = config.speakerId,
            sampleRateIn = config.sampleRateIn,
            sampleRateOut = config.sampleRateOut,
            priority = config.priority,
            userData = config.userData,
        )
    }

    override fun sessionOpenModelFree(runtimeHandle: Long, config: NativeSessionConfig): NativeSessionOpenWire {
        ensureAvailable()
        return nativeSessionOpenModelFree(
            runtimeHandle = runtimeHandle,
            capability = config.capability?.code,
            modelUri = config.modelUri,
            locality = config.locality,
            policyPreset = config.policyPreset,
            speakerId = config.speakerId,
            sampleRateIn = config.sampleRateIn,
            sampleRateOut = config.sampleRateOut,
            priority = config.priority,
            userData = config.userData,
        )
    }

    override fun sessionSendAudio(sessionHandle: Long, audio: NativeAudioView): NativeRuntimeStatusWire {
        ensureAvailable()
        return nativeSessionSendAudio(
            sessionHandle = sessionHandle,
            samples = audio.samples,
            sampleRate = audio.sampleRate,
            channels = audio.channels,
        )
    }

    override fun sessionSendText(sessionHandle: Long, text: String): NativeRuntimeStatusWire {
        ensureAvailable()
        return nativeSessionSendText(sessionHandle, text)
    }

    override fun sessionPollEvent(sessionHandle: Long, timeoutMs: Int): NativeSessionPollWire {
        ensureAvailable()
        return nativeSessionPollEvent(sessionHandle, timeoutMs)
    }

    override fun sessionCancel(sessionHandle: Long): NativeRuntimeStatusWire {
        ensureAvailable()
        return nativeSessionCancel(sessionHandle)
    }

    override fun sessionClose(sessionHandle: Long) {
        ensureAvailable()
        nativeSessionClose(sessionHandle)
    }

    override fun lastError(handle: Long): String? {
        ensureAvailable()
        return nativeLastError(handle)
    }

    override fun lastThreadError(): String? {
        ensureAvailable()
        return nativeLastThreadError()
    }

    override fun close(handle: Long) {
        ensureAvailable()
        nativeClose(handle)
    }

    private fun loadLibrary(): NativeRuntimeAvailability =
        try {
            System.loadLibrary(libraryName)
            val runtimeError = nativeRuntimeLoadError()
            if (runtimeError == null) {
                NativeRuntimeAvailability.Available
            } else {
                NativeRuntimeAvailability.Unavailable(
                    libraryName = libraryName,
                    message = runtimeError,
                    cause = UnsatisfiedLinkError(runtimeError),
                )
            }
        } catch (error: UnsatisfiedLinkError) {
            NativeRuntimeAvailability.Unavailable(
                libraryName = libraryName,
                message = error.message ?: "unsatisfied link",
                cause = error,
            )
        } catch (error: SecurityException) {
            NativeRuntimeAvailability.Unavailable(
                libraryName = libraryName,
                message = error.message ?: "blocked by security manager",
                cause = error,
            )
        }

    private external fun nativeRuntimeLoadError(): String?
    private external fun nativeAbiVersionMajor(): Int
    private external fun nativeAbiVersionMinor(): Int
    private external fun nativeAbiVersionPatch(): Int
    private external fun nativeOpen(artifactRoot: String?, maxSessions: Int): NativeRuntimeOpenWire
    private external fun nativeCapabilities(handle: Long): NativeRuntimeCapabilitiesWire
    private external fun nativeCacheIntrospect(runtimeHandle: Long, bufferBytes: Int): NativeRuntimeCacheIntrospectWire
    private external fun nativeModelOpen(
        runtimeHandle: Long,
        modelUri: String?,
        artifactDigest: String?,
        engineHint: String?,
        policyPreset: String?,
        acceleratorPref: Int,
        ramBudgetBytes: Long,
        userData: Long,
    ): NativeModelOpenWire
    private external fun nativeModelWarm(modelHandle: Long): NativeRuntimeStatusWire
    private external fun nativeModelClose(modelHandle: Long)
    private external fun nativeSessionOpen(
        runtimeHandle: Long,
        modelHandle: Long,
        capability: String?,
        modelUri: String?,
        locality: String,
        policyPreset: String?,
        speakerId: String?,
        sampleRateIn: Int,
        sampleRateOut: Int,
        priority: Int,
        userData: Long,
    ): NativeSessionOpenWire
    private external fun nativeSessionOpenModelFree(
        runtimeHandle: Long,
        capability: String?,
        modelUri: String?,
        locality: String,
        policyPreset: String?,
        speakerId: String?,
        sampleRateIn: Int,
        sampleRateOut: Int,
        priority: Int,
        userData: Long,
    ): NativeSessionOpenWire
    private external fun nativeSessionSendAudio(
        sessionHandle: Long,
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
    ): NativeRuntimeStatusWire
    private external fun nativeSessionSendText(sessionHandle: Long, text: String): NativeRuntimeStatusWire
    private external fun nativeSessionPollEvent(sessionHandle: Long, timeoutMs: Int): NativeSessionPollWire
    private external fun nativeSessionCancel(sessionHandle: Long): NativeRuntimeStatusWire
    private external fun nativeSessionClose(sessionHandle: Long)
    private external fun nativeLastError(handle: Long): String?
    private external fun nativeLastThreadError(): String?
    private external fun nativeClose(handle: Long)
}

internal data class NativeRuntimeAbiVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
)

internal data class NativeRuntimeOpenWire(
    val statusCode: Int,
    val handle: Long,
    val message: String?,
)

internal class NativeRuntimeCapabilitiesWire(
    val statusCode: Int,
    val message: String?,
    val supportedEngines: Array<String>,
    val supportedCapabilities: Array<String>,
    val supportedArchs: Array<String>,
    val ramTotalBytes: Long,
    val ramAvailableBytes: Long,
    val hasAppleSilicon: Boolean,
    val hasCuda: Boolean,
    val hasMetal: Boolean,
)

internal data class NativeRuntimeStatusWire(
    val statusCode: Int,
    val message: String? = null,
)

internal data class NativeRuntimeCacheIntrospectWire(
    val statusCode: Int,
    val message: String? = null,
    val json: String? = null,
)

internal data class NativeModelOpenWire(
    val statusCode: Int,
    val handle: Long,
    val message: String? = null,
)

internal data class NativeSessionOpenWire(
    val statusCode: Int,
    val handle: Long,
    val message: String? = null,
)

internal data class NativeSessionPollWire(
    val statusCode: Int,
    val message: String? = null,
    val event: NativeSessionEventWire? = null,
)
