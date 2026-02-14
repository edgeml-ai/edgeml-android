package ai.edgeml.inference

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Video generation engine for Android.
 *
 * Each video frame is emitted as a chunk containing raw pixel data (1024 bytes).
 * This stub implementation emits zeroed frame buffers. Swap in an on-device
 * video model for production use.
 *
 * @param context Android application context.
 * @param frameCount Number of frames to generate.
 */
class VideoEngine(
    private val context: Context,
    private val frameCount: Int = 30,
) : StreamingInferenceEngine {
    override fun generate(
        input: Any,
        modality: Modality,
    ): Flow<InferenceChunk> =
        flow {
            repeat(frameCount) { index ->
                emit(
                    InferenceChunk(
                        index = index,
                        data = ByteArray(1024),
                        modality = Modality.VIDEO,
                        timestamp = System.currentTimeMillis(),
                        latencyMs = 0.0,
                    ),
                )
            }
        }
}
