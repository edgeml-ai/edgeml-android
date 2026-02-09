package ai.edgeml.sample

import ai.edgeml.client.EdgeMLClient
import ai.edgeml.config.EdgeMLConfig
import android.app.Application
import timber.log.Timber

/**
 * Sample application demonstrating EdgeML SDK initialization.
 */
class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        Timber.plant(Timber.DebugTree())

        // Initialize EdgeML SDK
        initializeEdgeML()
    }

    private fun initializeEdgeML() {
        // Build configuration
        // In production, these would come from secure storage or a config file
        val config = EdgeMLConfig.Builder()
            .serverUrl(SampleConfig.EDGEML_SERVER_URL)
            .deviceAccessToken(SampleConfig.EDGEML_DEVICE_ACCESS_TOKEN)
            .orgId(SampleConfig.EDGEML_ORG_ID)
            .modelId(SampleConfig.EDGEML_MODEL_ID)
            .debugMode(SampleConfig.DEBUG)
            .enableGpuAcceleration(true)
            .enableBackgroundSync(true)
            .syncIntervalMinutes(60)
            .build()

        // Create EdgeML client
        EdgeMLClient.Builder(this)
            .config(config)
            .build()

        Timber.i("EdgeML SDK configured")
    }
}

/**
 * Sample configuration constants.
 *
 * In a real app, these would be in BuildConfig generated from gradle.
 * For the sample, we use placeholder values.
 */
object SampleConfig {
    const val DEBUG = true
    const val EDGEML_SERVER_URL = "https://api.edgeml.ai"
    const val EDGEML_DEVICE_ACCESS_TOKEN = "your-device-token-here"
    const val EDGEML_ORG_ID = "your-org-id-here"
    const val EDGEML_MODEL_ID = "your-model-id-here"
}
