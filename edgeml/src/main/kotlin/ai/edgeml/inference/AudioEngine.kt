package ai.edgeml.inference

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Audio generation engine for Android.
 *
 * Audio frames are emitted as chunks of PCM sample data (1024 samples x 2 bytes).
 * This stub implementation emits zeroed PCM frames. Swap in an on-device
 * audio model for production use.
 *
 * @param context Android application context.
 * @param totalFrames Number of audio frames to generate.
 */
class AudioEngine(
    private val context: Context,
    private val totalFrames: Int = 80,
) : StreamingInferenceEngine {
    override fun generate(
        input: Any,
        modality: Modality,
    ): Flow<InferenceChunk> =
        flow {
            repeat(totalFrames) { index ->
                emit(
                    InferenceChunk(
                        index = index,
                        data = ByteArray(2048),
                        modality = Modality.AUDIO,
                        timestamp = System.currentTimeMillis(),
                        latencyMs = 0.0,
                    ),
                )
            }
        }
}
