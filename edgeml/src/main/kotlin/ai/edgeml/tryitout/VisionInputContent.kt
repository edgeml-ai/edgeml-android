package ai.edgeml.tryitout

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
 * Vision modality UI with image selection and optional text prompt.
 *
 * Provides a gallery picker via [ActivityResultContracts.GetContent], an optional
 * text prompt field for vision-language models, and an "Analyze" button that
 * triggers inference. Results are displayed in a card below the input area.
 *
 * Since real image preprocessing (resize, normalize, etc.) depends on the model,
 * this screen converts the selected image URI to a placeholder float array.
 * Production usage would use proper bitmap-to-tensor conversion.
 *
 * @param state Current [TryItOutState] from the ViewModel.
 * @param onRunInference Callback to run inference with the encoded input.
 * @param modifier Optional [Modifier] applied to the root layout.
 */
@Composable
fun VisionInputContent(
    state: TryItOutState,
    onRunInference: (FloatArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var promptText by remember { mutableStateOf("") }

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

        // Optional text prompt
        OutlinedTextField(
            value = promptText,
            onValueChange = { promptText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Optional prompt (e.g., 'Describe this image')") },
            shape = RoundedCornerShape(12.dp),
            singleLine = false,
            maxLines = 3,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Analyze button
        Button(
            onClick = {
                // Encode image URI hash + prompt as a placeholder float array.
                // Real implementation would load + preprocess the bitmap.
                val imageHash = selectedImageUri?.hashCode()?.toFloat() ?: 0f
                val promptEncoded = promptText.map { it.code.toFloat() }.toFloatArray()
                val input = floatArrayOf(imageHash) + promptEncoded
                onRunInference(input)
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
                Text("Analyze")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Result area
        when (state) {
            is TryItOutState.Result -> {
                ResultCard(
                    title = "Analysis Result",
                    content = state.output
                        .take(20)
                        .joinToString(", ") { "%.4f".format(it) },
                    latencyMs = state.latencyMs,
                )
            }
            is TryItOutState.Error -> {
                ErrorCard(message = state.message)
            }
            else -> {}
        }
    }
}
