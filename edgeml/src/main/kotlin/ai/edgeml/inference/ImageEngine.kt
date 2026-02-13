package ai.edgeml.inference

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Image generation engine for Android (MediaPipe / TFLite diffusion).
 *
 * Each denoising step emits a chunk containing the current image state.
 *
 * **STATUS: NOT IMPLEMENTED.** This engine requires integration with an
 * on-device image generation runtime. Calling [generate] will throw
 * [NotImplementedError]. To use streaming image generation, provide your own
 * [StreamingInferenceEngine] implementation to [ai.edgeml.client.EdgeMLClient.generateStream].
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
            throw NotImplementedError(
                "ImageEngine is not yet implemented. " +
                    "Provide a custom StreamingInferenceEngine to generateStream() " +
                    "with your diffusion model integration.",
            )
        }
}
