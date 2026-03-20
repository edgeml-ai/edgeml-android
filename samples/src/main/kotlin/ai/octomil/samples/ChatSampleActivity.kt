package ai.octomil.samples

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.octomil.Octomil
import ai.octomil.responses.ResponseRequest
import ai.octomil.responses.ResponseStreamEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Minimal chat sample — streaming text generation.
//
// Prerequisites:
//   1. Call Octomil.init(context) in your Application class.
//   2. Deploy a chat model (e.g. phi-4-mini) via `octomil deploy --phone`.

class ChatSampleActivity : ComponentActivity() {

    // -- Replace with your model name --
    private val modelName = "phi-4-mini"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Octomil.init(this)

        setContent {
            MaterialTheme {
                ChatSampleScreen(modelName)
            }
        }
    }
}

data class ChatMessage(val role: String, val text: String)

@Composable
fun ChatSampleScreen(modelName: String) {
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var input by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var job by remember { mutableStateOf<Job?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Chat Sample") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(messages) { _, msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.role == "user") Arrangement.End else Arrangement.Start,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (msg.role == "user")
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = msg.text,
                                modifier = Modifier.padding(10.dp),
                                color = if (msg.role == "user")
                                    MaterialTheme.colorScheme.onPrimary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    enabled = !isGenerating,
                    singleLine = true,
                )

                if (isGenerating) {
                    TextButton(onClick = { job?.cancel() }) {
                        Text("Stop", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Button(
                        onClick = {
                            val text = input.trim()
                            if (text.isEmpty()) return@Button

                            messages = messages + ChatMessage("user", text)
                            input = ""
                            isGenerating = true
                            val idx = messages.size
                            messages = messages + ChatMessage("assistant", "")

                            job = scope.launch {
                                var accumulated = ""
                                try {
                                    val request = ResponseRequest.text(model = modelName, text = text)
                                    Octomil.responses.stream(request).collect { event ->
                                        when (event) {
                                            is ResponseStreamEvent.TextDelta -> {
                                                accumulated += event.text
                                                messages = messages.toMutableList().also {
                                                    it[idx] = ChatMessage("assistant", accumulated)
                                                }
                                            }
                                            else -> {}
                                        }
                                    }
                                } catch (e: Exception) {
                                    messages = messages.toMutableList().also {
                                        it[idx] = ChatMessage("assistant", accumulated.ifEmpty { "Error: ${e.message}" })
                                    }
                                }
                                isGenerating = false

                                // Scroll to bottom
                                listState.animateScrollToItem(messages.size - 1)
                            }
                        },
                        enabled = input.trim().isNotEmpty(),
                    ) { Text("Send") }
                }
            }
        }
    }
}
