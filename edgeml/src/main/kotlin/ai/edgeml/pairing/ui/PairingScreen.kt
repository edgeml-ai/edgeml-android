package ai.edgeml.pairing.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Pairing screen composable that displays the device pairing flow.
 *
 * Shows four distinct states:
 * - **Connecting**: Spinner + server host while connecting to EdgeML.
 * - **Downloading**: Progress bar + model name + size during model download.
 * - **Success**: Model info card with "Try it out" and "Open Dashboard" buttons.
 * - **Error**: Error message with retry button.
 *
 * ## Usage
 *
 * ```kotlin
 * @Composable
 * fun MyScreen(viewModel: PairingViewModel) {
 *     PairingScreen(
 *         viewModel = viewModel,
 *         onTryItOut = { /* launch inference */ },
 *         onOpenDashboard = { /* open browser */ },
 *     )
 * }
 * ```
 *
 * @param viewModel The [PairingViewModel] driving the screen state.
 * @param onTryItOut Callback when the user taps "Try it out" on the success screen.
 * @param onOpenDashboard Callback when the user taps "Open Dashboard" on the success screen.
 * @param modifier Optional [Modifier] applied to the root layout.
 */
@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onTryItOut: () -> Unit = {},
    onOpenDashboard: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    PairingScreenContent(
        state = state,
        onRetry = viewModel::retry,
        onTryItOut = onTryItOut,
        onOpenDashboard = onOpenDashboard,
        modifier = modifier,
    )
}

/**
 * Stateless content composable for the pairing screen.
 *
 * Separated from [PairingScreen] to enable preview and direct testing.
 */
@Composable
fun PairingScreenContent(
    state: PairingState,
    onRetry: () -> Unit = {},
    onTryItOut: () -> Unit = {},
    onOpenDashboard: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                text = "EdgeML",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Animated state content
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "pairing_state",
                modifier = Modifier.weight(1f),
            ) { currentState ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (currentState) {
                        is PairingState.Connecting -> ConnectingContent(state = currentState)
                        is PairingState.Downloading -> DownloadingContent(state = currentState)
                        is PairingState.Success -> SuccessContent(
                            state = currentState,
                            onTryItOut = onTryItOut,
                            onOpenDashboard = onOpenDashboard,
                        )
                        is PairingState.Error -> ErrorContent(
                            state = currentState,
                            onRetry = onRetry,
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// State-specific composables
// =============================================================================

@Composable
internal fun ConnectingContent(
    state: PairingState.Connecting,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Connecting...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Server:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = state.host,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DownloadingContent(
    state: PairingState.Downloading,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Downloading...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = state.modelName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(20.dp))

            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = "${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SuccessContent(
    state: PairingState.Success,
    onTryItOut: () -> Unit,
    onOpenDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Ready!",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Model info rows
            ModelInfoRow(label = "Model", value = state.modelName)
            Spacer(modifier = Modifier.height(8.dp))
            ModelInfoRow(label = "Version", value = state.modelVersion)
            Spacer(modifier = Modifier.height(8.dp))
            ModelInfoRow(
                label = "Size",
                value = formatBytes(state.sizeBytes),
            )
            Spacer(modifier = Modifier.height(8.dp))
            ModelInfoRow(label = "Runtime", value = state.runtime)

            Spacer(modifier = Modifier.height(32.dp))

            // Action buttons
            Button(
                onClick = onTryItOut,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = "Try it out",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onOpenDashboard,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = "Open Dashboard",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
internal fun ErrorContent(
    state: PairingState.Error,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Pairing Failed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )

            if (state.isRetryable) {
                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(
                        text = "Retry",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

// =============================================================================
// Helper composables
// =============================================================================

@Composable
private fun ModelInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

// =============================================================================
// Utility functions
// =============================================================================

/**
 * Format a byte count into a human-readable string.
 *
 * Examples:
 * - 512 -> "512 B"
 * - 1024 -> "1.0 KB"
 * - 2_700_000_000 -> "2.7 GB"
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceAtMost(units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return if (digitGroups == 0) {
        "${bytes.toInt()} B"
    } else {
        "%.1f %s".format(value, units[digitGroups])
    }
}
