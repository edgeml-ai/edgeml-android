package ai.edgeml.inference

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Image generation engine for Android (MediaPipe / TFLite diffusion).
 *
 * Each denoising step emits a chunk containing the current image state.
 * This stub implementation emits placeholder pixel data per step. Swap in
 * a diffusion model runtime for production use.
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
            repeat(steps) { index ->
                emit(
                    InferenceChunk(
                        index = index,
                        data = ByteArray(64) { it.toByte() },
                        modality = Modality.IMAGE,
                        timestamp = System.currentTimeMillis(),
                        latencyMs = 0.0,
                    ),
                )
            }
        }
}
