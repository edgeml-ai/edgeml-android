package ai.octomil.pairing.ui

import ai.octomil.api.OctomilApi
import ai.octomil.api.OctomilApiFactory
import ai.octomil.config.AuthConfig
import ai.octomil.config.OctomilConfig
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import timber.log.Timber

/**
 * Activity that hosts the [PairingScreen] composable.
 *
 * Handles the `octomil://pair?token=X&host=Y` deep link scheme and launches the
 * pairing flow UI. Apps can either:
 *
 * 1. **Register this activity in their manifest** for automatic deep link handling.
 * 2. **Launch it manually** via [createIntent] when a deep link is intercepted.
 * 3. **Use it as a reference** and embed [PairingScreen] directly in their own UI.
 *
 * ## Manifest registration (option 1)
 *
 * ```xml
 * <activity
 *     android:name="ai.octomil.pairing.ui.PairingActivity"
 *     android:exported="true"
 *     android:theme="@style/Theme.Material3.DayNight">
 *     <intent-filter>
 *         <action android:name="android.intent.action.VIEW" />
 *         <category android:name="android.intent.category.DEFAULT" />
 *         <category android:name="android.intent.category.BROWSABLE" />
 *         <data android:scheme="octomil" android:host="pair" />
 *     </intent-filter>
 * </activity>
 * ```
 *
 * ## Manual launch (option 2)
 *
 * ```kotlin
 * val intent = PairingActivity.createIntent(
 *     context = this,
 *     token = "ABC123",
 *     host = "https://api.octomil.com",
 * )
 * startActivity(intent)
 * ```
 */
class PairingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val (token, host) = extractPairingParams(intent)
            ?: run {
                Timber.e("PairingActivity: missing token or host in intent data")
                finish()
                return
            }

        Timber.i("PairingActivity: starting pairing flow token=%s host=%s", token, host)

        val api = resolveApi(host)
        val factory = PairingViewModel.Factory(
            api = api,
            context = applicationContext,
            token = token,
            host = host,
        )

        val viewModel = ViewModelProvider(this, factory)[PairingViewModel::class.java]

        setContent {
            OctomilPairingTheme {
                PairingScreen(
                    viewModel = viewModel,
                    onTryItOut = {
                        Timber.d("PairingActivity: user tapped 'Try it out'")
                        val successState = viewModel.state.value as? PairingState.Success
                        if (successState != null) {
                            val handler = onTryItOutHandler
                            if (handler != null) {
                                handler.invoke(
                                    this@PairingActivity,
                                    successState.modelName,
                                    successState.modelVersion,
                                )
                            } else {
                                Timber.w("No onTryItOutHandler registered. Set PairingActivity.onTryItOutHandler in Application.onCreate().")
                                finish()
                            }
                        }
                    },
                    onOpenDashboard = {
                        Timber.d("PairingActivity: user tapped 'Open Dashboard'")
                        openDashboard(host)
                    },
                )
            }
        }
    }

    /**
     * Open the Octomil dashboard in the default browser.
     */
    private fun openDashboard(host: String) {
        try {
            val dashboardUrl = if (host.startsWith("http")) {
                host.trimEnd('/')
            } else {
                "https://$host"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl))
            startActivity(intent)
        } catch (e: Exception) {
            Timber.w(e, "Failed to open dashboard")
        }
    }

    /**
     * Create an [OctomilApi] for the given host.
     *
     * Uses a minimal config since pairing endpoints are unauthenticated.
     */
    private fun resolveApi(host: String): OctomilApi {
        val serverUrl = if (host.startsWith("http")) {
            host.trimEnd('/')
        } else {
            "https://$host"
        }

        val config = OctomilConfig.Builder()
            .auth(AuthConfig.DeviceToken(deviceId = "pairing", bootstrapToken = "pairing", serverUrl = serverUrl))
            .modelId("pairing") // Placeholder — not used during pairing
            .build()

        return OctomilApiFactory.create(config)
    }

    companion object {

        /**
         * Handler invoked when the user taps "Try it out" after pairing.
         *
         * Set this in `Application.onCreate()` to navigate to your chat UI.
         * Receives (context, modelName, modelVersion).
         *
         * If null, the activity logs a warning and finishes.
         */
        @Volatile
        var onTryItOutHandler: ((Context, String, String) -> Unit)? = null

        private const val EXTRA_TOKEN = "ai.octomil.pairing.EXTRA_TOKEN"
        private const val EXTRA_HOST = "ai.octomil.pairing.EXTRA_HOST"

        /**
         * Create an intent to launch [PairingActivity] with explicit token and host.
         *
         * @param context Android context.
         * @param token Pairing code from the QR scan or deep link.
         * @param host Octomil server host (e.g., "https://api.octomil.com").
         */
        fun createIntent(
            context: Context,
            token: String,
            host: String,
        ): Intent = Intent(context, PairingActivity::class.java).apply {
            putExtra(EXTRA_TOKEN, token)
            putExtra(EXTRA_HOST, host)
        }

        /**
         * Extract token and host from an intent.
         *
         * Supports two modes:
         * 1. **Deep link**: `octomil://pair?token=X&host=Y`
         * 2. **Explicit extras**: `EXTRA_TOKEN` and `EXTRA_HOST`
         *
         * @return Pair of (token, host) or null if either is missing.
         */
        internal fun extractPairingParams(intent: Intent): Pair<String, String>? {
            // Try deep link URI first
            intent.data?.let { uri ->
                val token = uri.getQueryParameter("token")
                val host = uri.getQueryParameter("host")
                if (token != null && host != null) {
                    return token to host
                }
            }

            // Fall back to explicit extras
            val token = intent.getStringExtra(EXTRA_TOKEN)
            val host = intent.getStringExtra(EXTRA_HOST)
            if (token != null && host != null) {
                return token to host
            }

            return null
        }
    }
}
