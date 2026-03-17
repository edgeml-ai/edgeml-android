package ai.octomil.speech

import kotlinx.coroutines.flow.StateFlow

/**
 * Pluggable interface for on-device streaming speech-to-text.
 *
 * Implement this to integrate any STT engine (sherpa-onnx, Whisper, etc.)
 * with the Octomil audio API.
 *
 * ```kotlin
 * val runtime: SpeechRuntime = SherpaStreamingRuntime(modelDir)
 * val session = runtime.startSession()
 * session.feed(samples)           // 16kHz mono float [-1,1]
 * session.transcript.collect {}   // live partial text
 * val final = session.finalize()  // drain + return final text
 * session.release()
 * runtime.release()
 * ```
 */
interface SpeechRuntime {
    /** Create a new streaming session. Each session owns its own decoder state. */
    fun startSession(): SpeechSession

    /** Release all native resources held by this runtime. */
    fun release()
}

/**
 * A single streaming transcription session.
 *
 * Thread safety: [feed] may be called from a recording thread while
 * [transcript] is collected from the UI thread.
 */
interface SpeechSession {
    /** Live partial + finalized transcript text. */
    val transcript: StateFlow<String>

    /** Feed 16kHz mono float samples in [-1, 1]. */
    fun feed(samples: FloatArray)

    /** Signal end of audio, drain decoder, return final transcript. */
    suspend fun finalize(): String

    /** Clear transcript text and decoder state for reuse. */
    fun reset()

    /** Free native resources. Session cannot be reused after this. */
    fun release()
}
