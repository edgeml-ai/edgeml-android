package ai.edgeml.inference

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Audio generation engine for Android.
 *
 * Audio frames are emitted as chunks of PCM sample data.
 *
 * **STATUS: NOT IMPLEMENTED.** This engine requires integration with an
 * on-device audio generation model. Calling [generate] will throw
 * [NotImplementedError]. To use streaming audio generation, provide your own
 * [StreamingInferenceEngine] implementation to [ai.edgeml.client.EdgeMLClient.generateStream].
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
            throw NotImplementedError(
                "AudioEngine is not yet implemented. " +
                    "Provide a custom StreamingInferenceEngine to generateStream() " +
                    "with your audio model integration.",
            )
        }
}
