package ai.octomil.samples

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.octomil.Octomil
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Minimal transcription sample — batch audio-to-text.
//
// Prerequisites:
//   1. Call Octomil.init(context) in your Application class.
//   2. Deploy a transcription model (e.g. whisper-small) via `octomil deploy --phone`.
//   3. Place a test WAV file at res/raw/test_audio.wav (16 kHz, mono, 16-bit PCM).

class TranscriptionSampleActivity : ComponentActivity() {

    // -- Replace with your model name --
    private val modelName = "whisper-small"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Octomil.init(this)

        setContent {
            MaterialTheme {
                TranscriptionSampleScreen(modelName) { loadTestAudio() }
            }
        }
    }

    // Load bundled WAV and convert 16-bit PCM to FloatArray
    private fun loadTestAudio(): FloatArray? {
        val resId = resources.getIdentifier("test_audio", "raw", packageName)
        if (resId == 0) return null

        val bytes = resources.openRawResource(resId).use { it.readBytes() }
        // Skip 44-byte WAV header
        if (bytes.size <= 44) return null
        val pcm = bytes.copyOfRange(44, bytes.size)
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
    }
}

@Composable
fun TranscriptionSampleScreen(modelName: String, loadAudio: () -> FloatArray?) {
    var transcription by remember { mutableStateOf("") }
    var isTranscribing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Transcription Sample") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.weight(1f))

            if (transcription.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = transcription,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                Text(
                    "Tap the button to transcribe the bundled audio file.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    val samples = loadAudio()
                    if (samples == null) {
                        error = "Add res/raw/test_audio.wav (16 kHz, mono, 16-bit PCM) to test."
                        return@Button
                    }
                    isTranscribing = true
                    error = null
                    scope.launch {
                        try {
                            transcription = Octomil.audio.transcribe(modelName, samples)
                        } catch (e: Exception) {
                            error = e.message
                        }
                        isTranscribing = false
                    }
                },
                enabled = !isTranscribing,
            ) {
                Text(if (isTranscribing) "Transcribing..." else "Transcribe Audio")
            }

            Spacer(Modifier.weight(1f))
        }
    }
}
