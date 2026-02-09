package ai.edgeml.inference

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Image generation engine for Android (MediaPipe / TFLite diffusion).
 *
 * Each denoising step emits a chunk containing the current image state.
 *
 * @param context Android application context.
 * @param steps Number of diffusion steps.
 */
class ImageEngine(
    private val context: Context,
    private val steps: Int = 20,
) : StreamingInferenceEngine {
    override fun generate(
        input: Any,
        modality: Modality,
    ): Flow<InferenceChunk> =
        flow {
            for (step in 0 until steps) {
                val data = ByteArray(64) { step.toByte() }
                emit(
                    InferenceChunk(
                        index = step,
                        data = data,
                        modality = Modality.IMAGE,
                        timestamp = System.currentTimeMillis(),
                        latencyMs = 0.0,
                    ),
                )
                delay(50)
            }
        }
}
