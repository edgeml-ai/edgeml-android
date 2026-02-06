package ai.edgeml.sample

import ai.edgeml.client.ClientState
import ai.edgeml.client.EdgeMLClient
import ai.edgeml.client.ModelInfo
import ai.edgeml.models.DownloadState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ViewModel for the main activity.
 */
class MainViewModel : ViewModel() {

    private val _clientState = MutableStateFlow(ClientState.UNINITIALIZED)
    val clientState: StateFlow<ClientState> = _clientState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState?>(null)
    val downloadState: StateFlow<DownloadState?> = _downloadState.asStateFlow()

    private val _inferenceResult = MutableStateFlow<InferenceResultUI?>(null)
    val inferenceResult: StateFlow<InferenceResultUI?> = _inferenceResult.asStateFlow()

    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()

    private val _modelInfo = MutableStateFlow<ModelInfo?>(null)
    val modelInfo: StateFlow<ModelInfo?> = _modelInfo.asStateFlow()

    private var client: EdgeMLClient? = null

    init {
        // Get client if already created
        try {
            client = EdgeMLClient.getInstance()
            observeClient()
        } catch (e: Exception) {
            Timber.d("Client not yet initialized")
        }
    }

    /**
     * Initialize the EdgeML SDK.
     */
    fun initializeSDK() {
        viewModelScope.launch {
            try {
                client = EdgeMLClient.getInstance()
                observeClient()

                val result = client?.initialize()
                result?.onSuccess {
                    Timber.i("SDK initialized successfully")
                    _modelInfo.value = client?.getModelInfo()
                }?.onFailure { error ->
                    Timber.e(error, "SDK initialization failed")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get EdgeML client")
            }
        }
    }

    /**
     * Observe client state flows.
     */
    private fun observeClient() {
        client?.let { edgemlClient ->
            viewModelScope.launch {
                edgemlClient.state.collect { state ->
                    _clientState.value = state
                }
            }

            viewModelScope.launch {
                edgemlClient.deviceId.collect { id ->
                    _deviceId.value = id
                }
            }

            viewModelScope.launch {
                edgemlClient.modelDownloadState.collect { state ->
                    _downloadState.value = state
                }
            }
        }
    }

    /**
     * Run a sample inference.
     */
    fun runSampleInference() {
        viewModelScope.launch {
            val edgemlClient = client ?: return@launch

            // Get model info to determine input size
            val modelInfo = edgemlClient.getModelInfo()
            if (modelInfo == null) {
                Timber.w("No model info available")
                return@launch
            }

            // Create sample input data (random values for demo)
            val inputSize = modelInfo.inputShape.fold(1) { acc, dim -> acc * dim }
            val sampleInput = FloatArray(inputSize) { kotlin.random.Random.nextFloat() }

            // Run inference
            val result = edgemlClient.runInference(sampleInput)

            result.onSuccess { output ->
                val topK = output.topK(1).firstOrNull()
                _inferenceResult.value = InferenceResultUI(
                    topClass = topK?.first ?: -1,
                    confidence = topK?.second ?: 0f,
                    inferenceTimeMs = output.inferenceTimeMs,
                )
                Timber.i("Inference completed in ${output.inferenceTimeMs}ms")
            }.onFailure { error ->
                Timber.e(error, "Inference failed")
            }
        }
    }

    /**
     * Update the model.
     */
    fun updateModel() {
        viewModelScope.launch {
            val edgemlClient = client ?: return@launch

            val result = edgemlClient.updateModel()
            result.onSuccess { model ->
                Timber.i("Model updated: ${model.modelId} v${model.version}")
                _modelInfo.value = edgemlClient.getModelInfo()
            }.onFailure { error ->
                Timber.e(error, "Model update failed")
            }
        }
    }

    /**
     * Trigger sync.
     */
    fun triggerSync() {
        client?.triggerSync()
    }

    override fun onCleared() {
        super.onCleared()
        // Don't close the client here as it may be used by other components
    }
}
