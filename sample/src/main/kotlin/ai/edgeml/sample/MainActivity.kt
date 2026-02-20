package ai.edgeml.sample

import ai.edgeml.pairing.ui.EdgeMLPairingTheme
import ai.edgeml.tryitout.ModelInfo
import ai.edgeml.tryitout.TryItOutViewModel
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Main launcher activity for the EdgeML pilot reference app.
 *
 * Uses Jetpack Compose with bottom navigation exposing two tabs:
 * - **SDK Demo** -- the original register / inference / training buttons.
 * - **Paired Model** -- shows the TryItOut screen after a successful `edgeml deploy --phone`
 *   pairing, or a waiting placeholder before pairing has occurred.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EdgeMLPairingTheme {
                MainScreen()
            }
        }
    }
}

/**
 * Root composable with bottom navigation.
 */
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }

    // Paired model state -- will be set when a model is deployed via the pairing flow.
    // In a production app this would be populated by observing PairingActivity results
    // or a shared repository. For the pilot reference this starts null and can be
    // populated manually or via deep link.
    var pairedModelInfo by remember { mutableStateOf<ModelInfo?>(null) }
    var tryItOutViewModel by remember { mutableStateOf<TryItOutViewModel?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Build, contentDescription = "SDK Demo") },
                    label = { Text("SDK Demo") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CellTower, contentDescription = "Paired Model") },
                    label = { Text("Paired") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> SdkDemoContent()
                1 -> PairedModelContent(
                    pairedModelInfo = pairedModelInfo,
                    tryItOutViewModel = tryItOutViewModel,
                )
            }
        }
    }
}
