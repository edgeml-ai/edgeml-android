package ai.edgeml.tryitout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * State emitted by [TryItOutViewModel] for the Try It Out screen.
 */
sealed class TryItOutState {
    /** No inference has been run yet. */
    data object Idle : TryItOutState()

    /** Inference is currently running. */
    data object Loading : TryItOutState()

    /** Inference completed successfully. */
    data class Result(
        /** Raw output float array from the model. */
        val output: FloatArray,
        /** Inference latency in milliseconds. */
        val latencyMs: Long,
    ) : TryItOutState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            return output.contentEquals(other.output) && latencyMs == other.latencyMs
        }

        override fun hashCode(): Int {
            var result = output.contentHashCode()
            result = 31 * result + latencyMs.hashCode()
            return result
        }
    }

    /** Inference failed. */
    data class Error(val message: String) : TryItOutState()
}

/**
 * Holds model metadata passed from [PairingState.Success] to the Try It Out screen.
 */
data class ModelInfo(
    val modelName: String,
    val modelVersion: String,
    val sizeBytes: Long,
    val runtime: String,
    val modality: String?,
)

/**
 * Abstraction over the inference engine so the ViewModel can be tested without
 * a real TFLite interpreter.
 */
fun interface InferenceRunner {
    /**
     * Run inference on the given float input and return the output float array.
     * Implementations must be safe to call from a coroutine context.
     */
    suspend fun runInference(input: FloatArray): FloatArray
}

/**
 * ViewModel managing the Try It Out inference screen.
 *
 * Accepts model metadata from the pairing success state and an [InferenceRunner]
 * that wraps the actual TFLite interpreter. Tracks latency via [System.nanoTime]
 * and emits [TryItOutState] for the UI to observe.
 *
 * @param modelInfo Metadata about the deployed model.
 * @param inferenceRunner Abstraction over the inference call.
 */
class TryItOutViewModel(
    val modelInfo: ModelInfo,
    private val inferenceRunner: InferenceRunner,
) : ViewModel() {

    private val _state = MutableStateFlow<TryItOutState>(TryItOutState.Idle)
    val state: StateFlow<TryItOutState> = _state.asStateFlow()

    /**
     * The effective modality used for UI routing. Defaults to "text" when
     * the server does not provide a modality.
     */
    val effectiveModality: String
        get() = modelInfo.modality?.lowercase()?.trim() ?: "text"

    /**
     * Run inference on the given float input.
     *
     * Transitions state: Idle/Result/Error -> Loading -> Result | Error.
     */
    fun runInference(input: FloatArray) {
        _state.value = TryItOutState.Loading

        viewModelScope.launch {
            try {
                val startNs = System.nanoTime()
                val output = inferenceRunner.runInference(input)
                val latencyMs = (System.nanoTime() - startNs) / 1_000_000

                _state.value = TryItOutState.Result(
                    output = output,
                    latencyMs = latencyMs,
                )

                Timber.d(
                    "TryItOut: inference complete model=%s latency=%dms outputSize=%d",
                    modelInfo.modelName,
                    latencyMs,
                    output.size,
                )
            } catch (e: Exception) {
                Timber.e(e, "TryItOut: inference failed for model=%s", modelInfo.modelName)
                _state.value = TryItOutState.Error(
                    message = e.message ?: "Inference failed",
                )
            }
        }
    }

    /**
     * Reset the state back to Idle.
     */
    fun reset() {
        _state.value = TryItOutState.Idle
    }

    /**
     * Factory for creating [TryItOutViewModel] with constructor injection.
     */
    class Factory(
        private val modelInfo: ModelInfo,
        private val inferenceRunner: InferenceRunner,
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TryItOutViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return TryItOutViewModel(modelInfo, inferenceRunner) as T
        }
    }
}
