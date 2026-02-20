package ai.edgeml.sample

import ai.edgeml.client.ClientState
import ai.edgeml.models.DownloadState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Compose screen that ports the original XML-based SDK demo UI.
 *
 * Shows status, model info, inference results, training progress,
 * and action buttons wired to [MainViewModel].
 */
@Composable
fun SdkDemoContent(
    viewModel: MainViewModel = viewModel(),
) {
    val clientState by viewModel.clientState.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val inferenceResult by viewModel.inferenceResult.collectAsStateWithLifecycle()
    val deviceId by viewModel.deviceId.collectAsStateWithLifecycle()
    val modelInfo by viewModel.modelInfo.collectAsStateWithLifecycle()
    val trainingState by viewModel.trainingState.collectAsStateWithLifecycle()

    SdkDemoScreen(
        clientState = clientState,
        downloadState = downloadState,
        inferenceResult = inferenceResult,
        deviceId = deviceId,
        modelInfo = modelInfo,
        trainingState = trainingState,
        onInitialize = viewModel::initializeSDK,
        onRunInference = viewModel::runSampleInference,
        onStartTraining = viewModel::runTraining,
        onUpdateModel = viewModel::updateModel,
        onSync = viewModel::triggerSync,
    )
}

/**
 * Stateless SDK demo screen for preview and testing.
 */
@Composable
fun SdkDemoScreen(
    clientState: ClientState,
    downloadState: DownloadState?,
    inferenceResult: InferenceResultUI?,
    deviceId: String?,
    modelInfo: ai.edgeml.client.ModelInfo?,
    trainingState: TrainingStateUI?,
    onInitialize: () -> Unit,
    onRunInference: () -> Unit,
    onStartTraining: () -> Unit,
    onUpdateModel: () -> Unit,
    onSync: () -> Unit,
) {
    val isReady = clientState == ClientState.READY
    val canInitialize = clientState == ClientState.UNINITIALIZED || clientState == ClientState.ERROR

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- Status card ---
        SectionCard(title = "Status") {
            Text(
                text = "State: ${clientState.displayName}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Device ID: ${deviceId ?: "Not registered"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Download: ${downloadState.displayText}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            DownloadProgressBar(downloadState)
        }

        // --- Model info card ---
        SectionCard(title = "Model Information") {
            if (modelInfo != null) {
                Text(
                    text = buildString {
                        appendLine("Model: ${modelInfo.modelId}")
                        appendLine("Version: ${modelInfo.version}")
                        appendLine("Format: ${modelInfo.format}")
                        appendLine("Size: ${modelInfo.sizeBytes / 1024} KB")
                        append("GPU: ${if (modelInfo.usingGpu) "Yes" else "No"}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Text(
                    text = "No model loaded",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // --- Inference result card ---
        SectionCard(title = "Inference") {
            if (inferenceResult != null) {
                Text(
                    text = buildString {
                        appendLine("Inference Result:")
                        appendLine("Top prediction: Class ${inferenceResult.topClass} (${String.format("%.2f", inferenceResult.confidence * 100)}%)")
                        append("Time: ${inferenceResult.inferenceTimeMs} ms")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Text(
                    text = "No inference result yet",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // --- Training card ---
        SectionCard(title = "Training") {
            if (trainingState != null) {
                Text(
                    text = buildString {
                        append("Status: ${trainingState.status}")
                        if (trainingState.totalEpochs > 0) {
                            append("\nEpochs: ${trainingState.currentEpoch}/${trainingState.totalEpochs}")
                        }
                        if (trainingState.loss != null) {
                            append("\nLoss: ${String.format("%.4f", trainingState.loss)}")
                        }
                        if (trainingState.accuracy != null) {
                            append("\nAccuracy: ${String.format("%.2f", trainingState.accuracy * 100)}%")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Text(
                    text = "No training started",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onStartTraining,
                enabled = isReady && trainingState?.isTraining != true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start Training")
            }
        }

        // --- Action buttons ---
        Button(
            onClick = onInitialize,
            enabled = canInitialize,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Initialize SDK")
        }

        Button(
            onClick = onRunInference,
            enabled = isReady,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Run Inference")
        }

        OutlinedButton(
            onClick = onUpdateModel,
            enabled = isReady,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Update Model")
        }

        OutlinedButton(
            onClick = onSync,
            enabled = isReady,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Trigger Sync")
        }

        // Bottom spacer so last button isn't clipped by nav bar
        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DownloadProgressBar(state: DownloadState?) {
    when (state) {
        is DownloadState.Downloading -> {
            LinearProgressIndicator(
                progress = { state.progress.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is DownloadState.CheckingForUpdates,
        is DownloadState.Verifying,
        -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        else -> {
            LinearProgressIndicator(
                progress = { 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Human-readable name for [ClientState]. */
private val ClientState.displayName: String
    get() = when (this) {
        ClientState.UNINITIALIZED -> "Uninitialized"
        ClientState.INITIALIZING -> "Initializing..."
        ClientState.READY -> "Ready"
        ClientState.ERROR -> "Error"
        ClientState.CLOSED -> "Closed"
    }

/** Human-readable summary for [DownloadState]. */
private val DownloadState?.displayText: String
    get() = when (this) {
        null, DownloadState.Idle -> "Idle"
        DownloadState.CheckingForUpdates -> "Checking for updates..."
        is DownloadState.Downloading -> "Downloading: ${progress.progress}%"
        DownloadState.Verifying -> "Verifying..."
        is DownloadState.Completed -> "Download completed"
        is DownloadState.Failed -> "Failed: ${error.message}"
        is DownloadState.UpToDate -> "Model up to date"
    }
