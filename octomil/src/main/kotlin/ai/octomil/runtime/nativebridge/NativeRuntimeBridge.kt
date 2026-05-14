package ai.octomil.runtime.nativebridge

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.generated.ErrorCode
import ai.octomil.generated.RuntimeCapability

/**
 * Minimal fail-closed JNI boundary for the OCT native runtime.
 *
 * The Android SDK carries runtime, model, session, event, and cache lifecycle
 * objects. The native path fails closed until a platform liboctomil_runtime.so
 * and required model artifacts are packaged with the app.
 */
class NativeRuntimeBridge internal constructor(
    private val jni: NativeRuntimeJni,
) {
    constructor(libraryName: String = DEFAULT_LIBRARY_NAME) : this(SystemNativeRuntimeJni(libraryName))

    fun open(config: NativeRuntimeConfig = NativeRuntimeConfig()): NativeRuntimeOpenResult {
        val available = jni.ensureAvailable()
        if (available is NativeRuntimeAvailability.Unavailable) {
            return NativeRuntimeResult.Skipped(available.toSkip())
        }

        val abi = nativeCall { jni.abiVersion() }
        when (abi) {
            is NativeRuntimeResult.Success -> {
                val version = abi.value
                if (version.major != REQUIRED_ABI_MAJOR || version.minor < REQUIRED_ABI_MINOR) {
                    return NativeRuntimeResult.Error(
                        NativeRuntimeIssue.fromStatus(
                            status = NativeRuntimeStatus.VERSION_MISMATCH,
                            message = "Native runtime ABI ${version.major}.${version.minor}.${version.patch} is incompatible; requires $REQUIRED_ABI_MAJOR.$REQUIRED_ABI_MINOR.x",
                        ),
                    )
                }
            }
            is NativeRuntimeResult.Error -> return abi
            is NativeRuntimeResult.Skipped -> return abi
        }

        return when (val opened = nativeCall { jni.open(config) }) {
            is NativeRuntimeResult.Success -> {
                val wire = opened.value
                val status = NativeRuntimeStatus.fromCode(wire.statusCode)
                when {
                    status == NativeRuntimeStatus.OK && wire.handle > 0L ->
                        NativeRuntimeResult.Success(NativeRuntime(this, wire.handle))

                    status == NativeRuntimeStatus.OK ->
                        NativeRuntimeResult.Error(
                            NativeRuntimeIssue.fromStatus(
                                NativeRuntimeStatus.INTERNAL,
                                wire.message ?: "Native runtime returned OK with a zero handle",
                            ),
                        )

                    else ->
                        NativeRuntimeResult.Error(
                            NativeRuntimeIssue.fromStatus(
                                status = status,
                                message = wire.message ?: safeLastThreadError()
                                    ?: "Native runtime open failed with ${status.cName}",
                            ),
                        )
                }
            }
            is NativeRuntimeResult.Error -> opened
            is NativeRuntimeResult.Skipped -> opened
        }
    }

    internal fun capabilities(handle: Long): NativeRuntimeResult<NativeRuntimeCapabilities> {
        if (handle <= 0L) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native runtime handle is not open",
                ),
            )
        }

        return when (val caps = nativeCall { jni.capabilities(handle) }) {
            is NativeRuntimeResult.Success -> {
                val wire = caps.value
                val status = NativeRuntimeStatus.fromCode(wire.statusCode)
                if (status == NativeRuntimeStatus.OK) {
                    NativeRuntimeResult.Success(NativeRuntimeCapabilities.fromWire(wire))
                } else {
                    NativeRuntimeResult.Error(
                        NativeRuntimeIssue.fromStatus(
                            status = status,
                            message = wire.message ?: safeLastError(handle)
                                ?: "Native runtime capabilities failed with ${status.cName}",
                        ),
                    )
                }
            }
            is NativeRuntimeResult.Error -> caps
            is NativeRuntimeResult.Skipped -> caps
        }
    }

    internal fun cacheIntrospect(handle: Long, bufferBytes: Int = 65_536): NativeRuntimeResult<String> {
        if (handle <= 0L) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native runtime handle is not open",
                ),
            )
        }
        if (bufferBytes <= 0) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native cache introspect bufferBytes must be > 0",
                ),
            )
        }

        return when (val inspected = nativeCall { jni.cacheIntrospect(handle, bufferBytes) }) {
            is NativeRuntimeResult.Success -> {
                val wire = inspected.value
                val status = NativeRuntimeStatus.fromCode(wire.statusCode)
                if (status == NativeRuntimeStatus.OK && wire.json != null) {
                    NativeRuntimeResult.Success(wire.json)
                } else {
                    NativeRuntimeResult.Error(
                        NativeRuntimeIssue.fromStatus(
                            status = status,
                            message = wire.message ?: safeLastError(handle)
                                ?: "Native runtime cache introspect failed with ${status.cName}",
                        ),
                    )
                }
            }
            is NativeRuntimeResult.Error -> inspected
            is NativeRuntimeResult.Skipped -> inspected
        }
    }

    internal fun openModel(runtimeHandle: Long, config: NativeModelConfig): NativeRuntimeResult<NativeModel> {
        if (runtimeHandle <= 0L) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native runtime handle is not open",
                ),
            )
        }

        return when (val opened = nativeCall { jni.modelOpen(runtimeHandle, config) }) {
            is NativeRuntimeResult.Success -> {
                val wire = opened.value
                val status = NativeRuntimeStatus.fromCode(wire.statusCode)
                when {
                    status == NativeRuntimeStatus.OK && wire.handle > 0L ->
                        NativeRuntimeResult.Success(NativeModel(this, runtimeHandle, wire.handle))
                    status == NativeRuntimeStatus.OK ->
                        NativeRuntimeResult.Error(
                            NativeRuntimeIssue.fromStatus(
                                NativeRuntimeStatus.INTERNAL,
                                wire.message ?: "Native runtime returned OK with a zero model handle",
                            ),
                        )
                    else ->
                        NativeRuntimeResult.Error(
                            NativeRuntimeIssue.fromStatus(
                                status = status,
                                message = wire.message ?: safeLastThreadError()
                                    ?: "Native model open failed with ${status.cName}",
                            ),
                        )
                }
            }
            is NativeRuntimeResult.Error -> opened
            is NativeRuntimeResult.Skipped -> opened
        }
    }

    internal fun openSession(
        runtimeHandle: Long,
        modelHandle: Long,
        config: NativeSessionConfig,
    ): NativeRuntimeResult<NativeSession> {
        if (runtimeHandle <= 0L) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native runtime handle is not open",
                ),
            )
        }
        if (modelHandle <= 0L) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native model handle is not open",
                ),
            )
        }

        return when (val opened = nativeCall { jni.sessionOpen(runtimeHandle, modelHandle, config) }) {
            is NativeRuntimeResult.Success -> {
                val wire = opened.value
                val status = NativeRuntimeStatus.fromCode(wire.statusCode)
                when {
                    status == NativeRuntimeStatus.OK && wire.handle > 0L ->
                        NativeRuntimeResult.Success(NativeSession(this, wire.handle))
                    status == NativeRuntimeStatus.OK ->
                        NativeRuntimeResult.Error(
                            NativeRuntimeIssue.fromStatus(
                                NativeRuntimeStatus.INTERNAL,
                                wire.message ?: "Native runtime returned OK with a zero session handle",
                            ),
                        )
                    else ->
                        NativeRuntimeResult.Error(
                            NativeRuntimeIssue.fromStatus(
                                status = status,
                                message = wire.message ?: safeLastThreadError()
                                    ?: "Native session open failed with ${status.cName}",
                            ),
                        )
                }
            }
            is NativeRuntimeResult.Error -> opened
            is NativeRuntimeResult.Skipped -> opened
        }
    }

    /**
     * Open a session without a pre-loaded model handle.
     *
     * Required for the model-free capabilities (`audio.vad`,
     * `audio.diarization`) whose adapters resolve their artifacts at
     * `oct_runtime_open` and accept session configs with no `oct_model_t`.
     * `audio.speaker.embedding` is model-bound and goes through the
     * standard `openModel + openSession` path; do NOT route it here.
     * Sets `oct_session_config_t.model = NULL` in the C ABI, delegating
     * model resolution to the runtime.
     */
    internal fun openSessionModelFree(
        runtimeHandle: Long,
        config: NativeSessionConfig,
    ): NativeRuntimeResult<NativeSession> {
        if (runtimeHandle <= 0L) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native runtime handle is not open",
                ),
            )
        }

        return when (val opened = nativeCall { jni.sessionOpenModelFree(runtimeHandle, config) }) {
            is NativeRuntimeResult.Success -> {
                val wire = opened.value
                val status = NativeRuntimeStatus.fromCode(wire.statusCode)
                when {
                    status == NativeRuntimeStatus.OK && wire.handle > 0L ->
                        NativeRuntimeResult.Success(NativeSession(this, wire.handle))
                    status == NativeRuntimeStatus.OK ->
                        NativeRuntimeResult.Error(
                            NativeRuntimeIssue.fromStatus(
                                NativeRuntimeStatus.INTERNAL,
                                wire.message ?: "Native runtime returned OK with a zero session handle",
                            ),
                        )
                    else ->
                        NativeRuntimeResult.Error(
                            NativeRuntimeIssue.fromStatus(
                                status = status,
                                message = wire.message ?: safeLastThreadError()
                                    ?: "Native model-free session open failed with ${status.cName}",
                            ),
                        )
                }
            }
            is NativeRuntimeResult.Error -> opened
            is NativeRuntimeResult.Skipped -> opened
        }
    }

    internal fun modelWarm(handle: Long): NativeRuntimeResult<Unit> {
        if (handle <= 0L) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native model handle is not open",
                ),
            )
        }

        return when (val warmed = nativeCall { jni.modelWarm(handle) }) {
            is NativeRuntimeResult.Success -> {
                val wire = warmed.value
                val status = NativeRuntimeStatus.fromCode(wire.statusCode)
                if (status == NativeRuntimeStatus.OK) {
                    NativeRuntimeResult.Success(Unit)
                } else {
                    NativeRuntimeResult.Error(
                        NativeRuntimeIssue.fromStatus(
                            status = status,
                            message = wire.message ?: safeLastError(handle)
                                ?: "Native model warm failed with ${status.cName}",
                        ),
                    )
                }
            }
            is NativeRuntimeResult.Error -> warmed
            is NativeRuntimeResult.Skipped -> warmed
        }
    }

    internal fun sessionSendAudio(handle: Long, audio: NativeAudioView): NativeRuntimeResult<Unit> {
        if (handle <= 0L) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native session handle is not open",
                ),
            )
        }

        return when (val sent = nativeCall { jni.sessionSendAudio(handle, audio) }) {
            is NativeRuntimeResult.Success -> {
                val wire = sent.value
                val status = NativeRuntimeStatus.fromCode(wire.statusCode)
                if (status == NativeRuntimeStatus.OK) {
                    NativeRuntimeResult.Success(Unit)
                } else {
                    NativeRuntimeResult.Error(
                        NativeRuntimeIssue.fromStatus(
                            status = status,
                            message = wire.message ?: safeLastError(handle)
                                ?: "Native session send_audio failed with ${status.cName}",
                        ),
                    )
                }
            }
            is NativeRuntimeResult.Error -> sent
            is NativeRuntimeResult.Skipped -> sent
        }
    }

    internal fun sessionSendText(handle: Long, text: String): NativeRuntimeResult<Unit> {
        if (handle <= 0L) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native session handle is not open",
                ),
            )
        }

        return when (val sent = nativeCall { jni.sessionSendText(handle, text) }) {
            is NativeRuntimeResult.Success -> {
                val wire = sent.value
                val status = NativeRuntimeStatus.fromCode(wire.statusCode)
                if (status == NativeRuntimeStatus.OK) {
                    NativeRuntimeResult.Success(Unit)
                } else {
                    NativeRuntimeResult.Error(
                        NativeRuntimeIssue.fromStatus(
                            status = status,
                            message = wire.message ?: safeLastError(handle)
                                ?: "Native session send_text failed with ${status.cName}",
                        ),
                    )
                }
            }
            is NativeRuntimeResult.Error -> sent
            is NativeRuntimeResult.Skipped -> sent
        }
    }

    internal fun sessionPollEvent(handle: Long, timeoutMs: Int): NativeRuntimeResult<NativeSessionEvent?> {
        if (handle <= 0L) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native session handle is not open",
                ),
            )
        }

        return when (val polled = nativeCall { jni.sessionPollEvent(handle, timeoutMs) }) {
            is NativeRuntimeResult.Success -> {
                val wire = polled.value
                val status = NativeRuntimeStatus.fromCode(wire.statusCode)
                when (status) {
                    NativeRuntimeStatus.OK -> NativeRuntimeResult.Success(wire.event?.toDomain())
                    NativeRuntimeStatus.TIMEOUT -> NativeRuntimeResult.Success(null)
                    else -> NativeRuntimeResult.Error(
                        NativeRuntimeIssue.fromStatus(
                            status = status,
                            message = wire.message ?: safeLastError(handle)
                                ?: "Native session poll_event failed with ${status.cName}",
                        ),
                    )
                }
            }
            is NativeRuntimeResult.Error -> polled
            is NativeRuntimeResult.Skipped -> polled
        }
    }

    internal fun sessionCancel(handle: Long): NativeRuntimeResult<Unit> {
        if (handle <= 0L) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native session handle is not open",
                ),
            )
        }

        return when (val cancelled = nativeCall { jni.sessionCancel(handle) }) {
            is NativeRuntimeResult.Success -> {
                val wire = cancelled.value
                val status = NativeRuntimeStatus.fromCode(wire.statusCode)
                if (status == NativeRuntimeStatus.OK || status == NativeRuntimeStatus.CANCELLED) {
                    NativeRuntimeResult.Success(Unit)
                } else {
                    NativeRuntimeResult.Error(
                        NativeRuntimeIssue.fromStatus(
                            status = status,
                            message = wire.message ?: safeLastError(handle)
                                ?: "Native session cancel failed with ${status.cName}",
                        ),
                    )
                }
            }
            is NativeRuntimeResult.Error -> cancelled
            is NativeRuntimeResult.Skipped -> cancelled
        }
    }

    internal fun close(handle: Long) {
        if (handle <= 0L) return
        if (jni.ensureAvailable() is NativeRuntimeAvailability.Available) {
            try {
                jni.close(handle)
            } catch (_: UnsatisfiedLinkError) {
                // Close is best-effort on an already unavailable JNI boundary.
            } catch (_: SecurityException) {
                // Close is best-effort on an already unavailable JNI boundary.
            }
        }
    }

    internal fun closeModel(handle: Long) {
        if (handle <= 0L) return
        if (jni.ensureAvailable() is NativeRuntimeAvailability.Available) {
            try {
                jni.modelClose(handle)
            } catch (_: UnsatisfiedLinkError) {
                // Best effort.
            } catch (_: SecurityException) {
                // Best effort.
            }
        }
    }

    internal fun closeSession(handle: Long) {
        if (handle <= 0L) return
        if (jni.ensureAvailable() is NativeRuntimeAvailability.Available) {
            try {
                jni.sessionClose(handle)
            } catch (_: UnsatisfiedLinkError) {
                // Best effort.
            } catch (_: SecurityException) {
                // Best effort.
            }
        }
    }

    private fun safeLastError(handle: Long): String? =
        try {
            jni.lastError(handle)
        } catch (_: UnsatisfiedLinkError) {
            null
        } catch (_: SecurityException) {
            null
        }

    private fun safeLastThreadError(): String? =
        try {
            jni.lastThreadError()
        } catch (_: UnsatisfiedLinkError) {
            null
        } catch (_: SecurityException) {
            null
        }

    private inline fun <T> nativeCall(call: () -> T): NativeRuntimeResult<T> =
        try {
            NativeRuntimeResult.Success(call())
        } catch (error: UnsatisfiedLinkError) {
            NativeRuntimeResult.Skipped(
                NativeRuntimeSkip(
                    message = "Native runtime JNI entry point is unavailable: ${error.message ?: "unsatisfied link"}",
                    cause = error,
                ),
            )
        } catch (error: SecurityException) {
            NativeRuntimeResult.Skipped(
                NativeRuntimeSkip(
                    message = "Native runtime JNI library could not be loaded: ${error.message ?: "blocked"}",
                    cause = error,
                ),
            )
        }

    companion object {
        const val DEFAULT_LIBRARY_NAME = "octomil_runtime_jni"
        const val REQUIRED_ABI_MAJOR = 0
        const val REQUIRED_ABI_MINOR = 10
    }
}

data class NativeRuntimeConfig(
    val artifactRoot: String? = null,
    val maxSessions: Int = 0,
) {
    init {
        require(maxSessions >= 0) { "maxSessions must be >= 0" }
    }
}

class NativeRuntime internal constructor(
    private val bridge: NativeRuntimeBridge,
    val handle: Long,
) : AutoCloseable {
    private var closed = false

    fun capabilities(): NativeRuntimeResult<NativeRuntimeCapabilities> {
        if (closed) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native runtime handle is already closed",
                ),
            )
        }
        return bridge.capabilities(handle)
    }

    fun cacheIntrospect(bufferBytes: Int = 65_536): NativeRuntimeResult<String> {
        if (closed) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native runtime handle is already closed",
                ),
            )
        }
        return bridge.cacheIntrospect(handle, bufferBytes)
    }

    fun openModel(config: NativeModelConfig = NativeModelConfig()): NativeRuntimeResult<NativeModel> {
        if (closed) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native runtime handle is already closed",
                ),
            )
        }
        return bridge.openModel(handle, config)
    }

    /**
     * Open a session without a pre-loaded model handle.
     *
     * The runtime resolves the model via [NativeSessionConfig.capability]
     * or [NativeSessionConfig.modelUri]. Used by the model-free
     * capabilities (`audio.vad`, `audio.diarization`) whose adapters load
     * their artifacts at `oct_runtime_open`. `audio.speaker.embedding`
     * is model-bound and uses the standard `openModel + openSession`
     * path; do NOT route it here.
     */
    fun openSessionModelFree(config: NativeSessionConfig): NativeRuntimeResult<NativeSession> {
        if (closed) {
            return NativeRuntimeResult.Error(
                NativeRuntimeIssue.fromStatus(
                    NativeRuntimeStatus.INVALID_INPUT,
                    "Native runtime handle is already closed",
                ),
            )
        }
        return bridge.openSessionModelFree(handle, config)
    }

    override fun close() {
        if (!closed) {
            closed = true
            bridge.close(handle)
        }
    }
}

typealias NativeRuntimeOpenResult = NativeRuntimeResult<NativeRuntime>

sealed class NativeRuntimeResult<out T> {
    data class Success<T>(val value: T) : NativeRuntimeResult<T>()
    data class Error(val error: NativeRuntimeIssue) : NativeRuntimeResult<Nothing>()
    data class Skipped(val reason: NativeRuntimeSkip) : NativeRuntimeResult<Nothing>()
}

data class NativeRuntimeSkip(
    val message: String,
    val cause: Throwable? = null,
    val status: NativeRuntimeStatus = NativeRuntimeStatus.RUNTIME_UNAVAILABLE,
    val sdkErrorCode: OctomilErrorCode = OctomilErrorCode.RUNTIME_UNAVAILABLE,
    val contractErrorCode: ErrorCode = ErrorCode.RUNTIME_UNAVAILABLE,
)

data class NativeRuntimeIssue(
    val status: NativeRuntimeStatus,
    val sdkErrorCode: OctomilErrorCode,
    val contractErrorCode: ErrorCode?,
    val message: String,
) {
    companion object {
        fun fromStatus(status: NativeRuntimeStatus, message: String): NativeRuntimeIssue {
            val sdkCode = status.toSdkErrorCode() ?: OctomilErrorCode.UNKNOWN
            val contractCode = ErrorCode.fromCode(sdkCode.name.lowercase())
            return NativeRuntimeIssue(
                status = status,
                sdkErrorCode = sdkCode,
                contractErrorCode = contractCode,
                message = message,
            )
        }
    }
}

data class NativeRuntimeCapabilities(
    val supportedEngines: List<String>,
    val supportedCapabilities: Set<RuntimeCapability>,
    val rawSupportedCapabilityCodes: List<String>,
    val unknownCapabilityCodes: List<String>,
    val rejectedProfileCodes: Set<String>,
    val supportedArchs: List<String>,
    val ramTotalBytes: Long,
    val ramAvailableBytes: Long,
    val hasAppleSilicon: Boolean,
    val hasCuda: Boolean,
    val hasMetal: Boolean,
) {
    companion object {
        /**
         * Capabilities that are currently live only when the native runtime
         * ships the backing implementation and advertises them at open-time.
         */
        val LIVE_NATIVE_CONDITIONAL_PROFILES: Set<RuntimeCapability> =
            setOf(
                RuntimeCapability.AUDIO_STT_BATCH,
                RuntimeCapability.AUDIO_STT_STREAM,
                RuntimeCapability.AUDIO_VAD,
                RuntimeCapability.AUDIO_SPEAKER_EMBEDDING,
                RuntimeCapability.AUDIO_DIARIZATION,
                RuntimeCapability.AUDIO_TTS_STREAM,
                RuntimeCapability.CACHE_INTROSPECT,
            )

        val NON_ADVERTISED_PROFILES: Set<RuntimeCapability> = emptySet()

        internal fun fromWire(wire: NativeRuntimeCapabilitiesWire): NativeRuntimeCapabilities {
            val parsed = linkedSetOf<RuntimeCapability>()
            val unknown = mutableListOf<String>()
            val rejectedProfiles = linkedSetOf<String>()

            wire.supportedCapabilities.forEach { code ->
                val capability = RuntimeCapability.fromCode(code)
                when {
                    capability == null -> unknown += code
                    capability in NON_ADVERTISED_PROFILES -> rejectedProfiles += code
                    else -> parsed += capability
                }
            }

            return NativeRuntimeCapabilities(
                supportedEngines = wire.supportedEngines.toList(),
                supportedCapabilities = parsed,
                rawSupportedCapabilityCodes = wire.supportedCapabilities.toList(),
                unknownCapabilityCodes = unknown,
                rejectedProfileCodes = rejectedProfiles,
                supportedArchs = wire.supportedArchs.toList(),
                ramTotalBytes = wire.ramTotalBytes,
                ramAvailableBytes = wire.ramAvailableBytes,
                hasAppleSilicon = wire.hasAppleSilicon,
                hasCuda = wire.hasCuda,
                hasMetal = wire.hasMetal,
            )
        }
    }
}
