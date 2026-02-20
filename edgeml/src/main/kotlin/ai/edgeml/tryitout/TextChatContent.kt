package ai.edgeml.tryitout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Represents a single message in the text chat UI.
 */
data class ChatMessage(
    /** The text content of the message. */
    val text: String,
    /** True if the message was sent by the user, false for model responses. */
    val isUser: Boolean,
    /** Inference latency in milliseconds; only set for model responses. */
    val latencyMs: Long? = null,
)

/**
 * Chat-style UI for text modality models.
 *
 * Shows a scrollable list of chat bubbles (user messages right-aligned, model
 * responses left-aligned), an [OutlinedTextField] for input, and a send button.
 * Each model response includes a latency chip.
 *
 * The input text is converted to a FloatArray by encoding each character's code
 * as a float. This is a simplified demonstration encoding — production usage
 * would use proper tokenization.
 *
 * @param state Current [TryItOutState] from the ViewModel.
 * @param onRunInference Callback to run inference with the encoded input.
 * @param modifier Optional [Modifier] applied to the root layout.
 */
@Composable
fun TextChatContent(
    state: TryItOutState,
    onRunInference: (FloatArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    var inputText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()

    // React to state changes from the ViewModel
    LaunchedEffect(state) {
        when (state) {
            is TryItOutState.Result -> {
                val responseText = state.output
                    .map { it.toInt().toChar() }
                    .joinToString("")
                    .trimEnd('\u0000')
                    .ifEmpty { state.output.take(10).joinToString(", ") { "%.4f".format(it) } }
                messages.add(
                    ChatMessage(
                        text = responseText,
                        isUser = false,
                        latencyMs = state.latencyMs,
                    )
                )
            }
            is TryItOutState.Error -> {
                messages.add(
                    ChatMessage(
                        text = "Error: ${state.message}",
                        isUser = false,
                    )
                )
            }
            else -> {}
        }
    }

    // Auto-scroll to bottom when messages change
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Chat message list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { message ->
                ChatBubble(message = message)
            }

            // Show loading indicator while inference is running
            if (state is TryItOutState.Loading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Input area
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                shape = RoundedCornerShape(24.dp),
                singleLine = false,
                maxLines = 4,
                enabled = state !is TryItOutState.Loading,
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        messages.add(ChatMessage(text = inputText, isUser = true))
                        val encoded = inputText.map { it.code.toFloat() }.toFloatArray()
                        onRunInference(encoded)
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank() && state !is TryItOutState.Loading,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ),
            ) {
                Text(
                    text = "\u2191", // Up arrow as send icon
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * A single chat bubble in the text chat UI.
 *
 * User messages are right-aligned with the primary color background.
 * Model responses are left-aligned with the surface variant background and
 * include an optional latency chip.
 */
@Composable
internal fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start,
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (message.isUser) 16.dp else 4.dp,
                    bottomEnd = if (message.isUser) 4.dp else 16.dp,
                ),
                color = if (message.isUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (message.isUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Latency chip for model responses
            message.latencyMs?.let { ms ->
                LatencyChip(latencyMs = ms)
            }
        }
    }
}
