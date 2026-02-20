package ai.edgeml.tryitout

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * Represents a single classification label with its confidence score.
 */
data class ClassificationLabel(
    /** Label index (used as fallback name if no label map is available). */
    val index: Int,
    /** Human-readable label name. */
    val name: String,
    /** Confidence score between 0 and 1. */
    val confidence: Float,
)

/**
 * Classification modality UI with image selection and top-K label results.
 *
 * Similar to [VisionInputContent] for image input, but the output area
 * displays top-K classification labels with horizontal confidence bars
 * using [LinearProgressIndicator].
 *
 * @param state Current [TryItOutState] from the ViewModel.
 * @param onRunInference Callback to run inference with the encoded input.
 * @param topK Number of top labels to display. Defaults to 5.
 * @param modifier Optional [Modifier] applied to the root layout.
 */
@Composable
fun ClassificationContent(
    state: TryItOutState,
    onRunInference: (FloatArray) -> Unit,
    topK: Int = 5,
    modifier: Modifier = Modifier,
) {
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        selectedImageUri = uri
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Image selection area
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (selectedImageUri != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Image selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = selectedImageUri.toString().takeLast(40),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Text(
                        text = "No image selected",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Image selection buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { imagePickerLauncher.launch("image/*") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Gallery")
            }

            OutlinedButton(
                onClick = { imagePickerLauncher.launch("image/*") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Camera")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Classify button
        Button(
            onClick = {
                val imageHash = selectedImageUri?.hashCode()?.toFloat() ?: 0f
                onRunInference(floatArrayOf(imageHash))
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            enabled = selectedImageUri != null && state !is TryItOutState.Loading,
        ) {
            if (state is TryItOutState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Classify")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Results area
        when (state) {
            is TryItOutState.Result -> {
                val labels = extractTopKLabels(state.output, topK)

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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Classification Results",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            LatencyChip(latencyMs = state.latencyMs)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        labels.forEach { label ->
                            ClassificationLabelRow(label = label)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
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

/**
 * A single classification label row with a horizontal confidence bar.
 */
@Composable
internal fun ClassificationLabelRow(
    label: ClassificationLabel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${(label.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        LinearProgressIndicator(
            progress = { label.confidence.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

/**
 * Extract top-K classification labels from a raw output float array.
 *
 * Sorts by confidence (descending) and returns the top [k] results.
 * Uses "Class N" as label names since no label map is available at this level.
 */
internal fun extractTopKLabels(output: FloatArray, k: Int): List<ClassificationLabel> {
    if (output.isEmpty()) return emptyList()

    return output
        .mapIndexed { index, confidence -> ClassificationLabel(index, "Class $index", confidence) }
        .sortedByDescending { it.confidence }
        .take(k)
}
