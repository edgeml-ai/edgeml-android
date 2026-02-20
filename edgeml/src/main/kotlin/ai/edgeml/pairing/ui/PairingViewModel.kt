package ai.edgeml.pairing.ui

import ai.edgeml.api.EdgeMLApi
import ai.edgeml.pairing.BenchmarkRunner
import ai.edgeml.pairing.DeploymentInfo
import ai.edgeml.pairing.PairingException
import ai.edgeml.pairing.PairingManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * State machine for the pairing screen UI.
 *
 * Each subclass maps directly to a visual state in [PairingScreen].
 */
sealed class PairingState {

    /** Initial state: connecting to the EdgeML server. */
    data class Connecting(
        val host: String,
    ) : PairingState()

    /** Model download in progress with live progress tracking. */
    data class Downloading(
        val progress: Float,
        val modelName: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : PairingState()

    /** Pairing and download completed successfully. */
    data class Success(
        val modelName: String,
        val modelVersion: String,
        val sizeBytes: Long,
        val runtime: String,
    ) : PairingState()

    /** An error occurred during pairing or download. */
    data class Error(
        val message: String,
        val isRetryable: Boolean = true,
    ) : PairingState()
}

/**
 * ViewModel driving the pairing screen flow.
 *
 * Orchestrates the full pairing sequence:
 * 1. Connect to server using the pairing code from the deep link.
 * 2. Wait for the server to assign a model deployment.
 * 3. Download the model binary.
 * 4. Run on-device benchmarks and report results.
 *
 * Emits [PairingState] via [state] for the UI to observe.
 *
 * @param pairingManager The pairing manager handling server communication.
 * @param token Pairing code extracted from the deep link URI.
 * @param host Server host extracted from the deep link URI.
 */
class PairingViewModel(
    private val pairingManager: PairingManager,
    private val token: String,
    private val host: String,
) : ViewModel() {

    private val _state = MutableStateFlow<PairingState>(PairingState.Connecting(host))
    val state: StateFlow<PairingState> = _state.asStateFlow()

    init {
        startPairing()
    }

    /**
     * Kick off the pairing flow. Called automatically on init and on retry.
     */
    fun startPairing() {
        _state.value = PairingState.Connecting(host)

        viewModelScope.launch {
            try {
                // Step 1: Connect
                Timber.i("Pairing UI: connecting with code=%s host=%s", token, host)
                pairingManager.connect(token)

                // Step 2: Wait for deployment
                Timber.d("Pairing UI: waiting for deployment")
                val deployment = pairingManager.waitForDeployment(token)

                // Step 3: Transition to downloading state
                _state.value = PairingState.Downloading(
                    progress = 0f,
                    modelName = deployment.modelName,
                    bytesDownloaded = 0L,
                    totalBytes = deployment.sizeBytes ?: 0L,
                )

                // Step 4: Download + benchmark
                Timber.d("Pairing UI: executing deployment for %s", deployment.modelName)
                val report = pairingManager.executeDeployment(deployment)

                // Step 5: Submit benchmark
                pairingManager.submitBenchmark(token, report)

                // Step 6: Success
                _state.value = PairingState.Success(
                    modelName = deployment.modelName,
                    modelVersion = deployment.modelVersion,
                    sizeBytes = deployment.sizeBytes ?: 0L,
                    runtime = formatRuntime(deployment.format),
                )

                Timber.i(
                    "Pairing UI: complete model=%s version=%s",
                    deployment.modelName, deployment.modelVersion,
                )
            } catch (e: PairingException) {
                Timber.e(e, "Pairing UI: PairingException code=%s", e.errorCode)
                _state.value = PairingState.Error(
                    message = mapErrorMessage(e),
                    isRetryable = isRetryable(e),
                )
            } catch (e: Exception) {
                Timber.e(e, "Pairing UI: unexpected error")
                _state.value = PairingState.Error(
                    message = e.message ?: "An unexpected error occurred",
                    isRetryable = true,
                )
            }
        }
    }

    /**
     * Retry the pairing flow after an error.
     */
    fun retry() {
        startPairing()
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    internal companion object {
        /**
         * Map a [PairingException] to a human-readable error message.
         */
        fun mapErrorMessage(e: PairingException): String = when (e.errorCode) {
            PairingException.ErrorCode.EXPIRED ->
                "Pairing session has expired. Please scan a new QR code."
            PairingException.ErrorCode.CANCELLED ->
                "Pairing session was cancelled from the dashboard."
            PairingException.ErrorCode.NOT_FOUND ->
                "Pairing code not found. It may have expired."
            PairingException.ErrorCode.UNAUTHORIZED ->
                "Authorization failed. Check your permissions."
            PairingException.ErrorCode.TIMEOUT ->
                "Connection timed out. Please check your network and try again."
            PairingException.ErrorCode.DOWNLOAD_FAILED ->
                "Model download failed. Please check your connection."
            PairingException.ErrorCode.NETWORK_ERROR ->
                "Network error. Please check your internet connection."
            PairingException.ErrorCode.SERVER_ERROR ->
                "Server error. Please try again later."
            PairingException.ErrorCode.BENCHMARK_FAILED ->
                "Benchmark failed. The model may not be compatible with this device."
            PairingException.ErrorCode.UNKNOWN ->
                e.message ?: "An unknown error occurred."
        }

        /**
         * Whether the error is retryable.
         */
        fun isRetryable(e: PairingException): Boolean = when (e.errorCode) {
            PairingException.ErrorCode.EXPIRED,
            PairingException.ErrorCode.CANCELLED,
            PairingException.ErrorCode.NOT_FOUND,
            PairingException.ErrorCode.UNAUTHORIZED,
            -> false
            else -> true
        }

        /**
         * Format the model runtime string for display.
         */
        fun formatRuntime(format: String): String = when {
            format.contains("tflite", ignoreCase = true) ||
                format.contains("tensorflow_lite", ignoreCase = true) -> "TFLite"
            format.contains("onnx", ignoreCase = true) -> "ONNX"
            format.contains("coreml", ignoreCase = true) -> "CoreML"
            else -> format
        }
    }

    /**
     * Factory for creating [PairingViewModel] with constructor injection.
     */
    class Factory(
        private val api: EdgeMLApi,
        private val context: Context,
        private val token: String,
        private val host: String,
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PairingViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            val pairingManager = PairingManager(api, context)
            return PairingViewModel(pairingManager, token, host) as T
        }
    }

    /**
     * Factory that accepts a pre-built [PairingManager] for testing or
     * custom configurations.
     */
    class ManagerFactory(
        private val pairingManager: PairingManager,
        private val token: String,
        private val host: String,
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PairingViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return PairingViewModel(pairingManager, token, host) as T
        }
    }
}
