package ai.octomil.sample

import ai.octomil.ModelResolver
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Content shown in the "Paired" tab.
 *
 * Scans for paired models on disk via [ModelResolver]. If a model is found,
 * shows a "Try Model" button that navigates to [ChatScreen]. Otherwise shows
 * a waiting state prompting the user to run `octomil deploy --phone`.
 *
 * @param onTryModel Callback to navigate to the chat screen with the model name.
 */
@Composable
fun PairedModelContent(
    onTryModel: (modelName: String) -> Unit,
) {
    val context = LocalContext.current
    var pairedModel by remember { mutableStateOf<PairedModelInfo?>(null) }
    var scanned by remember { mutableStateOf(false) }

    // Scan for paired models on disk
    LaunchedEffect(Unit) {
        val modelsDir = File(context.filesDir, "octomil_models")
        if (modelsDir.exists()) {
            val modelDirs = modelsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            for (modelDir in modelDirs) {
                val versionDir = modelDir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.maxByOrNull { it.name }
                val modelFile = versionDir?.listFiles()?.firstOrNull { it.isFile }
                if (modelFile != null) {
                    pairedModel = PairedModelInfo(
                        name = modelDir.name,
                        version = versionDir.name,
                        file = modelFile,
                    )
                    break
                }
            }
        }
        scanned = true
    }

    if (!scanned) return

    val model = pairedModel
    if (model != null) {
        PairedModelReady(model = model, onTryModel = { onTryModel(model.name) })
    } else {
        WaitingForPairing()
    }
}

@Composable
private fun PairedModelReady(
    model: PairedModelInfo,
    onTryModel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Chat,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = model.name,
            style = MaterialTheme.typography.titleLarge,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "v${model.version} \u00B7 ${formatFileSize(model.file.length())}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onTryModel,
            modifier = Modifier.fillMaxWidth(0.6f),
        ) {
            Icon(Icons.Default.Chat, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Try Model")
        }
    }
}

@Composable
private fun WaitingForPairing() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.CellTower,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No Model Paired",
            style = MaterialTheme.typography.titleLarge,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Run `octomil deploy <model> --phone` from your terminal to deploy a model to this device.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(0xFF4CAF50), CircleShape),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Discoverable on local network",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val gb = bytes / 1_073_741_824.0
    if (gb >= 1.0) return "%.1f GB".format(gb)
    val mb = bytes / 1_048_576.0
    return "%.0f MB".format(mb)
}

private data class PairedModelInfo(
    val name: String,
    val version: String,
    val file: File,
)
