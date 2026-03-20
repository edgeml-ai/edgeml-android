package ai.octomil.samples

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Octomil Samples", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = { startActivity(Intent(this@MainActivity, ChatSampleActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Chat") }

                        Button(
                            onClick = { startActivity(Intent(this@MainActivity, TranscriptionSampleActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Transcription") }

                        Button(
                            onClick = { startActivity(Intent(this@MainActivity, PredictionSampleActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Prediction") }
                    }
                }
            }
        }
    }
}
