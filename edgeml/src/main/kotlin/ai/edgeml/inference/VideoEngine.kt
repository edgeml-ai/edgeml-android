package ai.edgeml.inference

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Video generation engine for Android.
 *
 * Each video frame is emitted as a chunk containing raw pixel data.
 *
 * **STATUS: NOT IMPLEMENTED.** This engine requires integration with an
 * on-device video generation model. Calling [generate] will throw
 * [NotImplementedError]. To use streaming video generation, provide your own
 * [StreamingInferenceEngine] implementation to [ai.edgeml.client.EdgeMLClient.generateStream].
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
            throw NotImplementedError(
                "VideoEngine is not yet implemented. " +
                    "Provide a custom StreamingInferenceEngine to generateStream() " +
                    "with your video model integration.",
            )
        }
}
