package ai.octomil.tryitout

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var promptText by remember { mutableStateOf("") }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        selectedImageUri = uri
        selectedBitmap = uri?.let { loadBitmap(context, it) }
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
                val bmp = selectedBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Selected image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
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

        // Image selection button
        OutlinedButton(
            onClick = {
                imagePickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(if (selectedBitmap != null) "Change Image" else "Select Image")
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
                val bmp = selectedBitmap ?: return@Button
                val input = bitmapToFloatArray(bmp)
                onRunInference(input)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            enabled = selectedBitmap != null && state !is TryItOutState.Loading,
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

/** Default input size for image models (224x224 RGB). */
private const val DEFAULT_IMAGE_SIZE = 224

/**
 * Load a [Bitmap] from a content URI, scaled to [DEFAULT_IMAGE_SIZE].
 */
private fun loadBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    return try {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        val original = BitmapFactory.decodeStream(stream)
        stream.close()
        Bitmap.createScaledBitmap(original, DEFAULT_IMAGE_SIZE, DEFAULT_IMAGE_SIZE, true)
    } catch (e: Exception) {
        null
    }
}

/**
 * Convert a [Bitmap] to a normalized RGB [FloatArray] (values 0..1).
 */
internal fun bitmapToFloatArray(bitmap: Bitmap): FloatArray {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    val floats = FloatArray(w * h * 3)
    for (i in pixels.indices) {
        floats[i * 3] = ((pixels[i] shr 16) and 0xFF) / 255f
        floats[i * 3 + 1] = ((pixels[i] shr 8) and 0xFF) / 255f
        floats[i * 3 + 2] = (pixels[i] and 0xFF) / 255f
    }
    return floats
}
