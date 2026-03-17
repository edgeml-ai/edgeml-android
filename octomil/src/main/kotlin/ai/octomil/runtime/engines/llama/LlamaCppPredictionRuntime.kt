package ai.octomil.runtime.engines.llama

import ai.octomil.text.PredictionCandidate
import ai.octomil.text.PredictionRuntime
import android.content.Context
import com.arm.aichat.AiChat

/**
 * [PredictionRuntime] backed by llama.cpp's handle-based multi-model API.
 *
 * Uses the same singleton [com.arm.aichat.InferenceEngine] as [LlamaCppRuntime].
 * The handle-based API supports concurrent models (chat + prediction).
 *
 * TRANSITIONAL: singleton engine shared with chat runtime needs stress testing
 * for concurrent access safety before this is promoted beyond internal use.
 */
internal class LlamaCppPredictionRuntime(context: Context) : PredictionRuntime {

    private val engine = AiChat.getInferenceEngine(context)

    override suspend fun loadHandle(modelPath: String): Long =
        engine.loadModelHandle(modelPath)

    override suspend fun predictNext(handle: Long, text: String, k: Int): List<PredictionCandidate> =
        engine.predictNext(handle, text, k).map {
            PredictionCandidate(text = it.text, confidence = it.probability)
        }

    override suspend fun unloadHandle(handle: Long) =
        engine.unloadHandle(handle)
}
