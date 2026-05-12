package ai.octomil.runtime.nativebridge

import ai.octomil.errors.OctomilErrorCode

enum class NativeRuntimeStatus(
    val code: Int,
    val cName: String,
) {
    OK(0, "OCT_STATUS_OK"),
    INVALID_INPUT(1, "OCT_STATUS_INVALID_INPUT"),
    UNSUPPORTED(2, "OCT_STATUS_UNSUPPORTED"),
    NOT_FOUND(3, "OCT_STATUS_NOT_FOUND"),
    BUSY(4, "OCT_STATUS_BUSY"),
    TIMEOUT(5, "OCT_STATUS_TIMEOUT"),
    CANCELLED(6, "OCT_STATUS_CANCELLED"),
    INTERNAL(7, "OCT_STATUS_INTERNAL"),
    VERSION_MISMATCH(8, "OCT_STATUS_VERSION_MISMATCH"),
    RUNTIME_UNAVAILABLE(-1, "NATIVE_RUNTIME_UNAVAILABLE"),
    UNKNOWN(Int.MIN_VALUE, "OCT_STATUS_UNKNOWN");

    fun toSdkErrorCode(): OctomilErrorCode? = when (this) {
        OK -> null
        INVALID_INPUT -> OctomilErrorCode.INVALID_INPUT
        UNSUPPORTED -> OctomilErrorCode.UNSUPPORTED_MODALITY
        NOT_FOUND -> OctomilErrorCode.MODEL_NOT_FOUND
        BUSY -> OctomilErrorCode.RUNTIME_UNAVAILABLE
        TIMEOUT -> OctomilErrorCode.STREAM_INTERRUPTED
        CANCELLED -> OctomilErrorCode.CANCELLED
        INTERNAL -> OctomilErrorCode.INFERENCE_FAILED
        VERSION_MISMATCH -> OctomilErrorCode.RUNTIME_UNAVAILABLE
        RUNTIME_UNAVAILABLE -> OctomilErrorCode.RUNTIME_UNAVAILABLE
        UNKNOWN -> OctomilErrorCode.UNKNOWN
    }

    companion object {
        fun fromCode(code: Int): NativeRuntimeStatus =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
