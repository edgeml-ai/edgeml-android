package ai.edgeml.inference

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Video generation engine for Android.
 *
 * Each video frame is emitted as a chunk containing raw pixel data.
 *
 * @param context Android application context.
 * @param frameCount Number of frames to generate.
 */
class VideoEngine(
    private val context: Context,
    private val frameCount: Int = 30,
) : StreamingInferenceEngine {

    override fun generate(input: Any, modality: Modality): Flow<InferenceChunk> = flow {
        for (frame in 0 until frameCount) {
            val data = ByteArray(1024) { (frame % 256).toByte() }
            emit(
                InferenceChunk(
                    index = frame,
                    data = data,
                    modality = Modality.VIDEO,
                    timestamp = System.currentTimeMillis(),
                    latencyMs = 0.0,
                )
            )
            delay(33) // ~30fps
        }
    }
}
