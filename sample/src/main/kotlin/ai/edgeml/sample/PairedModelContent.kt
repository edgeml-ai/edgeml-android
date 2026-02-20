package ai.edgeml.sample

import ai.edgeml.tryitout.ModelInfo
import ai.edgeml.tryitout.TryItOutScreen
import ai.edgeml.tryitout.TryItOutViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Content shown in the "Paired" tab.
 *
 * When a model has been deployed via `edgeml deploy --phone` and paired through
 * [PairingActivity], this shows the [TryItOutScreen] for interactive inference.
 * Otherwise it displays a waiting state prompting the user to run the CLI command.
 *
 * @param pairedModelInfo Model metadata from a successful pairing, or null if not yet paired.
 * @param tryItOutViewModel ViewModel driving the TryItOut screen, or null when no model is paired.
 */
@Composable
fun PairedModelContent(
    pairedModelInfo: ModelInfo?,
    tryItOutViewModel: TryItOutViewModel?,
) {
    if (pairedModelInfo != null && tryItOutViewModel != null) {
        TryItOutScreen(viewModel = tryItOutViewModel)
    } else {
        WaitingForPairing()
    }
}

/**
 * Placeholder shown when no model has been paired yet.
 */
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
            text = "Run `edgeml deploy <model> --phone` from your terminal to deploy a model to this device.",
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
