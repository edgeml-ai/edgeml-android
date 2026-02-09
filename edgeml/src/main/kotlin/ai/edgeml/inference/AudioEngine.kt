package ai.edgeml.inference

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Audio generation engine for Android.
 *
 * Audio frames are emitted as chunks of PCM sample data.
 *
 * @param context Android application context.
 * @param totalFrames Number of audio frames to generate.
 */
class AudioEngine(
    private val context: Context,
    private val totalFrames: Int = 80,
) : StreamingInferenceEngine {

    override fun generate(input: Any, modality: Modality): Flow<InferenceChunk> = flow {
        for (frame in 0 until totalFrames) {
            val data = ByteArray(1024 * 2) // 1024 samples × 2 bytes
            emit(
                InferenceChunk(
                    index = frame,
                    data = data,
                    modality = Modality.AUDIO,
                    timestamp = System.currentTimeMillis(),
                    latencyMs = 0.0,
                )
            )
            delay(10)
        }
    }
}
