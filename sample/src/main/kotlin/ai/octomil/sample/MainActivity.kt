package ai.octomil.sample

import ai.octomil.pairing.ui.OctomilPairingTheme
import ai.octomil.sample.chat.ChatScreen
import ai.octomil.sample.chat.ChatViewModel
import android.app.Application
import android.content.Intent
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Main launcher activity for the Octomil pilot reference app.
 *
 * Uses Jetpack Compose with NavHost for navigation:
 * - **Home** — bottom nav with SDK Demo and Paired Model tabs.
 * - **Chat** — streaming LLM chat screen for a paired model.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Extract chat deep link from PairingActivity handler
        val chatModelName = intent?.getStringExtra("navigate_to_chat")

        setContent {
            OctomilPairingTheme {
                AppNavigation(initialChatModel = chatModelName)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle re-launch with SINGLE_TOP — store for recomposition
        setIntent(intent)
        val chatModelName = intent.getStringExtra("navigate_to_chat")
        if (chatModelName != null) {
            // Force recreate to pick up the new intent
            recreate()
        }
    }
}

/** Navigation route constants. */
object Routes {
    const val HOME = "home"
    const val CHAT = "chat/{modelName}"
}

@Composable
fun AppNavigation(initialChatModel: String? = null) {
    val navController = rememberNavController()

    // If launched with a chat deep link, navigate immediately
    if (initialChatModel != null) {
        val encoded = URLEncoder.encode(initialChatModel, "UTF-8")
        androidx.compose.runtime.LaunchedEffect(initialChatModel) {
            navController.navigate("chat/$encoded")
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToChat = { modelName ->
                    val encoded = URLEncoder.encode(modelName, "UTF-8")
                    navController.navigate("chat/$encoded")
                },
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("modelName") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val modelName = URLDecoder.decode(
                backStackEntry.arguments?.getString("modelName") ?: "",
                "UTF-8",
            )

            val viewModel: ChatViewModel = viewModel(
                factory = ChatViewModelFactory(
                    application = backStackEntry.arguments
                        ?.let { navController.context.applicationContext as Application }
                        ?: throw IllegalStateException("No application context"),
                    modelName = modelName,
                ),
            )

            ChatScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Home screen with bottom navigation.
 */
@Composable
fun HomeScreen(
    onNavigateToChat: (modelName: String) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

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
                    onTryModel = onNavigateToChat,
                )
            }
        }
    }
}

/**
 * Factory for [ChatViewModel] that takes model name as constructor arg.
 */
class ChatViewModelFactory(
    private val application: Application,
    private val modelName: String,
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(application, modelName) as T
    }
}
