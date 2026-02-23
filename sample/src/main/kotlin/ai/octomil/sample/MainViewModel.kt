package ai.octomil.sample

import ai.octomil.client.ClientState
import ai.octomil.client.OctomilClient
import ai.octomil.client.ModelInfo
import ai.octomil.models.DownloadState
import ai.octomil.training.InMemoryTrainingDataProvider
import ai.octomil.training.TrainingConfig
import ai.octomil.training.UploadPolicy
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

    private val _trainingState = MutableStateFlow<TrainingStateUI?>(null)
    val trainingState: StateFlow<TrainingStateUI?> = _trainingState.asStateFlow()

    private var client: OctomilClient? = null

    init {
        // Get client if already created
        try {
            client = OctomilClient.getInstance()
            observeClient()
        } catch (e: Exception) {
            Timber.d("Client not yet initialized")
        }
    }

    /**
     * Initialize the Octomil SDK.
     */
    fun initializeSDK() {
        viewModelScope.launch {
            try {
                client = OctomilClient.getInstance()
                observeClient()

                val result = client?.initialize()
                result
                    ?.onSuccess {
                        Timber.i("SDK initialized successfully")
                        _modelInfo.value = client?.getModelInfo()
                    }?.onFailure { error ->
                        Timber.e(error, "SDK initialization failed")
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get Octomil client")
            }
        }
    }

    /**
     * Observe client state flows.
     */
    private fun observeClient() {
        client?.let { octomilClient ->
            viewModelScope.launch {
                octomilClient.state.collect { state ->
                    _clientState.value = state
                }
            }

            viewModelScope.launch {
                octomilClient.serverDeviceId.collect { id ->
                    _deviceId.value = id
                }
            }

            viewModelScope.launch {
                octomilClient.modelDownloadState.collect { state ->
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
            val octomilClient = client ?: return@launch

            // Get model info to determine input size
            val modelInfo = octomilClient.getModelInfo()
            if (modelInfo == null) {
                Timber.w("No model info available")
                return@launch
            }

            // Create sample input data (random values for demo)
            val inputSize = modelInfo.inputShape.fold(1) { acc, dim -> acc * dim }
            val sampleInput = FloatArray(inputSize) { kotlin.random.Random.nextFloat() }

            // Run inference
            val result = octomilClient.runInference(sampleInput)

            result
                .onSuccess { output ->
                    val topK = output.topK(1).firstOrNull()
                    _inferenceResult.value =
                        InferenceResultUI(
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
     * Run local training with synthetic data.
     */
    fun runTraining() {
        viewModelScope.launch {
            val octomilClient = client ?: return@launch

            val modelInfo = octomilClient.getModelInfo()
            if (modelInfo == null) {
                _trainingState.value = TrainingStateUI(status = "No model loaded")
                return@launch
            }

            _trainingState.value = TrainingStateUI(
                isTraining = true,
                status = "Preparing synthetic data...",
            )

            try {
                val inputSize = modelInfo.inputShape.fold(1) { acc, dim -> acc * dim }
                val numClasses = 10
                val numSamples = 10

                // Generate synthetic training data
                val syntheticData = (0 until numSamples).map {
                    val input = FloatArray(inputSize) { kotlin.random.Random.nextFloat() }
                    val label = FloatArray(numClasses).also { arr ->
                        arr[kotlin.random.Random.nextInt(numClasses)] = 1.0f
                    }
                    input to label
                }

                val dataProvider = InMemoryTrainingDataProvider(syntheticData)
                val trainingConfig = TrainingConfig(
                    epochs = 3,
                    batchSize = 4,
                    learningRate = 0.001f,
                )

                _trainingState.value = TrainingStateUI(
                    isTraining = true,
                    totalEpochs = trainingConfig.epochs,
                    status = "Training...",
                )

                val result = octomilClient.train(
                    dataProvider = dataProvider,
                    trainingConfig = trainingConfig,
                    uploadPolicy = UploadPolicy.DISABLED,
                )

                result
                    .onSuccess { outcome ->
                        val tr = outcome.trainingResult
                        _trainingState.value = TrainingStateUI(
                            isTraining = false,
                            totalEpochs = trainingConfig.epochs,
                            currentEpoch = trainingConfig.epochs,
                            loss = tr.loss,
                            accuracy = tr.accuracy,
                            status = if (outcome.degraded) "Completed (degraded)" else "Completed",
                            completed = true,
                        )
                        Timber.i(
                            "Training completed: loss=%.4f, accuracy=%.4f, degraded=%s",
                            tr.loss,
                            tr.accuracy,
                            outcome.degraded,
                        )
                    }
                    .onFailure { error ->
                        _trainingState.value = TrainingStateUI(
                            isTraining = false,
                            status = "Failed: ${error.message}",
                        )
                        Timber.e(error, "Training failed")
                    }
            } catch (e: Exception) {
                _trainingState.value = TrainingStateUI(
                    isTraining = false,
                    status = "Error: ${e.message}",
                )
                Timber.e(e, "Training error")
            }
        }
    }

    /**
     * Update the model.
     */
    fun updateModel() {
        viewModelScope.launch {
            val octomilClient = client ?: return@launch

            val result = octomilClient.updateModel()
            result
                .onSuccess { model ->
                    Timber.i("Model updated: ${model.modelId} v${model.version}")
                    _modelInfo.value = octomilClient.getModelInfo()
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

/**
 * UI representation of inference result.
 */
data class InferenceResultUI(
    val topClass: Int,
    val confidence: Float,
    val inferenceTimeMs: Long,
)

/**
 * UI state for training progress.
 */
data class TrainingStateUI(
    val isTraining: Boolean = false,
    val currentEpoch: Int = 0,
    val totalEpochs: Int = 0,
    val loss: Double? = null,
    val accuracy: Double? = null,
    val status: String = "",
    val completed: Boolean = false,
)
