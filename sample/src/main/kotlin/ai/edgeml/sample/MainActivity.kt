package ai.edgeml.sample

import ai.edgeml.client.ClientState
import ai.edgeml.models.DownloadState
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ai.edgeml.sample.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main activity demonstrating EdgeML SDK usage.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeState()
    }

    private fun setupUI() {
        binding.apply {
            // Initialize button
            btnInitialize.setOnClickListener {
                viewModel.initializeSDK()
            }

            // Run inference button
            btnRunInference.setOnClickListener {
                viewModel.runSampleInference()
            }

            // Update model button
            btnUpdateModel.setOnClickListener {
                viewModel.updateModel()
            }

            // Sync button
            btnSync.setOnClickListener {
                viewModel.triggerSync()
                Toast.makeText(this@MainActivity, "Sync triggered", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe client state
                launch {
                    viewModel.clientState.collectLatest { state ->
                        updateClientStateUI(state)
                    }
                }

                // Observe download state
                launch {
                    viewModel.downloadState.collectLatest { state ->
                        updateDownloadStateUI(state)
                    }
                }

                // Observe inference result
                launch {
                    viewModel.inferenceResult.collectLatest { result ->
                        updateInferenceResultUI(result)
                    }
                }

                // Observe device ID
                launch {
                    viewModel.deviceId.collectLatest { deviceId ->
                        binding.tvDeviceId.text = "Device ID: ${deviceId ?: "Not registered"}"
                    }
                }

                // Observe model info
                launch {
                    viewModel.modelInfo.collectLatest { info ->
                        if (info != null) {
                            binding.tvModelInfo.text = """
                                Model: ${info.modelId}
                                Version: ${info.version}
                                Format: ${info.format}
                                Size: ${info.sizeBytes / 1024} KB
                                GPU: ${if (info.usingGpu) "Yes" else "No"}
                            """.trimIndent()
                        } else {
                            binding.tvModelInfo.text = "No model loaded"
                        }
                    }
                }
            }
        }
    }

    private fun updateClientStateUI(state: ClientState) {
        val stateText = when (state) {
            ClientState.UNINITIALIZED -> "Uninitialized"
            ClientState.INITIALIZING -> "Initializing..."
            ClientState.READY -> "Ready"
            ClientState.ERROR -> "Error"
            ClientState.CLOSED -> "Closed"
        }

        binding.tvClientState.text = "State: $stateText"
        binding.btnInitialize.isEnabled = state == ClientState.UNINITIALIZED || state == ClientState.ERROR
        binding.btnRunInference.isEnabled = state == ClientState.READY
        binding.btnUpdateModel.isEnabled = state == ClientState.READY
        binding.btnSync.isEnabled = state == ClientState.READY
    }

    private fun updateDownloadStateUI(state: DownloadState?) {
        val statusText = when (state) {
            null, DownloadState.Idle -> "Idle"
            DownloadState.CheckingForUpdates -> "Checking for updates..."
            is DownloadState.Downloading -> "Downloading: ${state.progress.progress}%"
            DownloadState.Verifying -> "Verifying..."
            is DownloadState.Completed -> "Download completed"
            is DownloadState.Failed -> "Failed: ${state.error.message}"
            is DownloadState.UpToDate -> "Model up to date"
        }

        binding.tvDownloadStatus.text = "Download: $statusText"

        // Update progress bar
        when (state) {
            is DownloadState.Downloading -> {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = state.progress.progress
            }
            is DownloadState.CheckingForUpdates, DownloadState.Verifying -> {
                binding.progressBar.isIndeterminate = true
            }
            else -> {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = 0
            }
        }
    }

    private fun updateInferenceResultUI(result: InferenceResultUI?) {
        if (result != null) {
            binding.tvInferenceResult.text = """
                Inference Result:
                Top prediction: Class ${result.topClass} (${String.format("%.2f", result.confidence * 100)}%)
                Time: ${result.inferenceTimeMs} ms
            """.trimIndent()
        } else {
            binding.tvInferenceResult.text = "No inference result yet"
        }
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
