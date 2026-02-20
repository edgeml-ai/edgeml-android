package ai.edgeml.tryitout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Main "Try It Out" screen that reads the model's modality and renders the
 * appropriate modality-specific sub-composable.
 *
 * Modality routing:
 * - `"text"` or `null`/unknown -> [TextChatContent]
 * - `"vision"` -> [VisionInputContent]
 * - `"audio"` -> [AudioInputContent]
 * - `"classification"` -> [ClassificationContent]
 *
 * @param viewModel The [TryItOutViewModel] driving inference state.
 * @param modifier Optional [Modifier] applied to the root layout.
 */
@Composable
fun TryItOutScreen(
    viewModel: TryItOutViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    TryItOutScreenContent(
        modelInfo = viewModel.modelInfo,
        modality = viewModel.effectiveModality,
        state = state,
        onRunInference = viewModel::runInference,
        onReset = viewModel::reset,
        modifier = modifier,
    )
}

/**
 * Stateless content composable for the Try It Out screen.
 *
 * Separated from [TryItOutScreen] to enable preview and direct testing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TryItOutScreenContent(
    modelInfo: ModelInfo,
    modality: String,
    state: TryItOutState,
    onRunInference: (FloatArray) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = modelInfo.modelName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "v${modelInfo.modelVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (modality) {
                "vision" -> VisionInputContent(
                    state = state,
                    onRunInference = onRunInference,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
                "audio" -> AudioInputContent(
                    state = state,
                    onRunInference = onRunInference,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
                "classification" -> ClassificationContent(
                    state = state,
                    onRunInference = onRunInference,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
                else -> TextChatContent(
                    state = state,
                    onRunInference = onRunInference,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
            }
        }
    }
}
