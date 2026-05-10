package ai.octomil.runtime.nativebridge

import ai.octomil.errors.OctomilErrorCode
import ai.octomil.generated.ErrorCode
import ai.octomil.generated.RuntimeCapability

/**
 * Minimal fail-closed JNI boundary for the OCT native runtime.
 *
 * This layer intentionally stops at runtime open, capabilities, last-error
 * mapping, and close. Model/session/event inference support is not exposed
 * until the Android module has real native build wiring and tests that prove it.
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
        const val REQUIRED_ABI_MINOR = 9
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
        val NON_ADVERTISED_PROFILES: Set<RuntimeCapability> = setOf(RuntimeCapability.CHAT_STREAM)

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
