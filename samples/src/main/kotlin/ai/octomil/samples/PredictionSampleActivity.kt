package ai.octomil.samples

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.octomil.Octomil
import kotlinx.coroutines.launch

// Minimal prediction sample — next-word suggestions.
//
// Prerequisites:
//   1. Call Octomil.init(context) in your Application class.
//   2. Deploy a prediction model (e.g. smollm2-135m) via `octomil deploy --phone`.

class PredictionSampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Octomil.init(this)

        setContent {
            MaterialTheme {
                PredictionSampleScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionSampleScreen() {
    var input by remember { mutableStateOf("The weather today is") }
    var suggestions by remember { mutableStateOf(listOf<String>()) }
    var isPredicting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun predict() {
        val text = input.trim()
        if (text.isEmpty() || isPredicting) return
        isPredicting = true
        error = null
        scope.launch {
            try {
                suggestions = Octomil.text.predict(text)
            } catch (e: Exception) {
                error = e.message
            }
            isPredicting = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Prediction Sample") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
                label = { Text("Input text") },
            )

            // Suggestion chips
            if (suggestions.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggestions.forEach { suggestion ->
                        SuggestionChip(
                            onClick = {
                                input = if (input.endsWith(" ")) input + suggestion
                                        else "$input $suggestion"
                                suggestions = emptyList()
                                predict()
                            },
                            label = { Text(suggestion) },
                        )
                    }
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { predict() },
                    enabled = !isPredicting && input.trim().isNotEmpty(),
                ) { Text(if (isPredicting) "Predicting..." else "Predict Next") }

                OutlinedButton(onClick = {
                    input = ""
                    suggestions = emptyList()
                }) { Text("Clear") }
            }
        }
    }
}
