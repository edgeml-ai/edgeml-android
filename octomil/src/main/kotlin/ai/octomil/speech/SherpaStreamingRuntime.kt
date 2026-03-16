package ai.octomil.speech

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "SherpaStreamingRuntime"
private const val SAMPLE_RATE = 16000

/**
 * [SpeechRuntime] implementation backed by sherpa-onnx streaming recognizer.
 *
 * Supports zipformer transducer models with endpoint detection.
 * Thread-safe: [SherpaSession.feed] from recording thread,
 * [SherpaSession.transcript] collected from UI thread.
 *
 * @param modelDir Directory containing model files (encoder, decoder, joiner, tokens.txt).
 */
internal class SherpaStreamingRuntime(private val modelDir: File) : SpeechRuntime {

    private val recognizer: OnlineRecognizer

    init {
        val config = buildConfig(modelDir)
        recognizer = OnlineRecognizer(config = config)
        Log.i(TAG, "Initialized recognizer from ${modelDir.absolutePath}")
    }

    override fun startSession(): SpeechSession = SherpaSession(recognizer)

    override fun release() {
        recognizer.release()
        Log.i(TAG, "Released recognizer")
    }

    companion object {
        /**
         * Auto-detect model files in the directory and build config.
         *
         * Looks for transducer model files (encoder*.onnx, decoder*.onnx, joiner*.onnx)
         * and tokens.txt. Prefers int8 variants when available.
         */
        internal fun buildConfig(modelDir: File): OnlineRecognizerConfig {
            val files = modelDir.listFiles()?.map { it.name } ?: emptyList()

            val encoder = files.firstOrNull { it.contains("encoder") && it.endsWith(".onnx") }
                ?: error("No encoder .onnx file found in ${modelDir.absolutePath}")
            val decoder = files.firstOrNull { it.contains("decoder") && it.endsWith(".onnx") }
                ?: error("No decoder .onnx file found in ${modelDir.absolutePath}")
            val joiner = files.firstOrNull { it.contains("joiner") && it.endsWith(".onnx") }
                ?: error("No joiner .onnx file found in ${modelDir.absolutePath}")
            val tokens = files.firstOrNull { it == "tokens.txt" }
                ?: error("No tokens.txt found in ${modelDir.absolutePath}")

            val dir = modelDir.absolutePath

            return OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$dir/$encoder",
                        decoder = "$dir/$decoder",
                        joiner = "$dir/$joiner",
                    ),
                    tokens = "$dir/$tokens",
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    modelType = "zipformer2",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.4f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
        }
    }
}

/**
 * Single streaming session wrapping a sherpa-onnx [OnlineStream].
 *
 * Thread-safe: [feed] synchronized on [lock], [transcript] is a StateFlow.
 */
private class SherpaSession(
    private val recognizer: OnlineRecognizer,
) : SpeechSession {

    private val stream: OnlineStream = recognizer.createStream()
    private val lock = Any()
    private val accumulated = StringBuilder()

    private val _transcript = MutableStateFlow("")
    override val transcript: StateFlow<String> = _transcript.asStateFlow()

    override fun feed(samples: FloatArray) {
        synchronized(lock) {
            stream.acceptWaveform(samples, SAMPLE_RATE)

            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }

            // Check for endpoint — finalized segment
            if (recognizer.isEndpoint(stream)) {
                val result = recognizer.getResult(stream)
                if (result.text.isNotBlank()) {
                    accumulated.append(result.text)
                }
                recognizer.reset(stream)
            }

            // Update transcript with accumulated + current partial
            val current = recognizer.getResult(stream).text
            _transcript.value = (accumulated.toString() + current).trim()
        }
    }

    override suspend fun finalize(): String = withContext(Dispatchers.Default) {
        synchronized(lock) {
            // Signal end of audio
            stream.inputFinished()

            // Drain remaining decodes
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }

            val result = recognizer.getResult(stream)
            if (result.text.isNotBlank()) {
                accumulated.append(result.text)
            }

            val finalText = accumulated.toString().trim()
            _transcript.value = finalText
            Log.i(TAG, "Finalized: $finalText")
            finalText
        }
    }

    override fun reset() {
        synchronized(lock) {
            recognizer.reset(stream)
            accumulated.clear()
            _transcript.value = ""
        }
    }

    override fun release() {
        synchronized(lock) {
            stream.release()
            Log.i(TAG, "Session released")
        }
    }
}
