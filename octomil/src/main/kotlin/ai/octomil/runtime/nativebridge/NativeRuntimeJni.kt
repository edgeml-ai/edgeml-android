package ai.octomil.runtime.nativebridge

internal interface NativeRuntimeJni {
    fun ensureAvailable(): NativeRuntimeAvailability
    fun abiVersion(): NativeRuntimeAbiVersion
    fun open(config: NativeRuntimeConfig): NativeRuntimeOpenWire
    fun capabilities(handle: Long): NativeRuntimeCapabilitiesWire
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
            NativeRuntimeAvailability.Available
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

    private external fun nativeAbiVersionMajor(): Int
    private external fun nativeAbiVersionMinor(): Int
    private external fun nativeAbiVersionPatch(): Int
    private external fun nativeOpen(artifactRoot: String?, maxSessions: Int): NativeRuntimeOpenWire
    private external fun nativeCapabilities(handle: Long): NativeRuntimeCapabilitiesWire
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
