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
import kotlin.math.min

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

    // Load bundled WAV and convert 16-bit mono PCM to FloatArray.
    private fun loadTestAudio(): FloatArray? {
        val resId = resources.getIdentifier("test_audio", "raw", packageName)
        if (resId == 0) return null

        val bytes = resources.openRawResource(resId).use { it.readBytes() }
        return parsePcmWav(bytes)
    }

    private fun parsePcmWav(bytes: ByteArray): FloatArray? {
        if (bytes.size < 12) return null
        if (readAscii(bytes, 0, 4) != "RIFF" || readAscii(bytes, 8, 4) != "WAVE") return null

        var offset = 12
        var audioFormat: Int? = null
        var channelCount: Int? = null
        var sampleRate: Int? = null
        var bitsPerSample: Int? = null
        var dataOffset: Int? = null
        var dataSize: Int? = null

        while (offset + 8 <= bytes.size) {
            val chunkId = readAscii(bytes, offset, 4)
            val chunkSize = readIntLE(bytes, offset + 4)
            if (chunkSize < 0) return null

            val chunkDataOffset = offset + 8
            val chunkEnd = chunkDataOffset + chunkSize
            if (chunkEnd > bytes.size) return null

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16) return null
                    audioFormat = readShortLE(bytes, chunkDataOffset)
                    channelCount = readShortLE(bytes, chunkDataOffset + 2)
                    sampleRate = readIntLE(bytes, chunkDataOffset + 4)
                    bitsPerSample = readShortLE(bytes, chunkDataOffset + 14)
                }
                "data" -> {
                    dataOffset = chunkDataOffset
                    dataSize = chunkSize
                }
            }

            offset = chunkEnd + (chunkSize and 1)
        }

        if (audioFormat != 1 || channelCount != 1 || sampleRate != 16_000 || bitsPerSample != 16) {
            return null
        }

        val start = dataOffset ?: return null
        val size = dataSize ?: return null
        if (size < 2) return null

        val shortBuffer = ByteBuffer.wrap(bytes, start, size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val sampleCount = min(shortBuffer.remaining(), size / 2)
        return FloatArray(sampleCount) { shortBuffer.get(it) / 32768f }
    }

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String {
        return bytes.copyOfRange(offset, offset + length).decodeToString()
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
