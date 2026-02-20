package ai.edgeml.tryitout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Audio modality UI with record/stop button and transcription output.
 *
 * Displays a large microphone-style record button. When recording, the button
 * changes to a stop button. After stopping, a placeholder audio buffer is
 * sent to the inference callback. The result is displayed as a transcription.
 *
 * Note: Actual [MediaRecorder] integration requires runtime permissions
 * (RECORD_AUDIO) and is left to the host application. This composable
 * provides the UI shell with state management — the [onRunInference] callback
 * is invoked with a placeholder float array when "stop" is tapped.
 *
 * @param state Current [TryItOutState] from the ViewModel.
 * @param onRunInference Callback to run inference with the recorded audio buffer.
 * @param modifier Optional [Modifier] applied to the root layout.
 */
@Composable
fun AudioInputContent(
    state: TryItOutState,
    onRunInference: (FloatArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isRecording by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Status text
        Text(
            text = when {
                state is TryItOutState.Loading -> "Processing..."
                isRecording -> "Recording..."
                else -> "Tap to record"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Record / Stop button
        LargeFloatingActionButton(
            onClick = {
                if (isRecording) {
                    isRecording = false
                    // Send a placeholder audio buffer for inference.
                    // Real implementation would send recorded PCM samples.
                    val placeholderAudio = FloatArray(16000) { 0f }
                    onRunInference(placeholderAudio)
                } else {
                    isRecording = true
                }
            },
            shape = CircleShape,
            containerColor = if (isRecording) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            contentColor = if (isRecording) {
                MaterialTheme.colorScheme.onError
            } else {
                MaterialTheme.colorScheme.onPrimary
            },
            modifier = Modifier.size(96.dp),
        ) {
            if (state is TryItOutState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    text = if (isRecording) "\u25A0" else "\uD83C\uDFA4", // Stop square or mic
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Transcription result area
        when (state) {
            is TryItOutState.Result -> {
                val transcription = state.output
                    .map { it.toInt().toChar() }
                    .joinToString("")
                    .trimEnd('\u0000')
                    .ifEmpty { "[ No transcription available ]" }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "Transcription",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = transcription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        LatencyChip(latencyMs = state.latencyMs)
                    }
                }
            }
            is TryItOutState.Error -> {
                ErrorCard(message = state.message)
            }
            else -> {}
        }
    }
}
