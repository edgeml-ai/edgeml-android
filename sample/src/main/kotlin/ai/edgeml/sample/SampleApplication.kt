package ai.edgeml.sample

import ai.edgeml.client.EdgeMLClient
import ai.edgeml.config.EdgeMLConfig
import ai.edgeml.config.PrivacyConfiguration
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
        // Read config from BuildConfig (generated from local.properties),
        // falling back to SampleConfig placeholders.
        val serverUrl = BuildConfig.EDGEML_SERVER_URL.ifBlank { SampleConfig.EDGEML_SERVER_URL }
        val deviceToken = BuildConfig.EDGEML_DEVICE_TOKEN.ifBlank { SampleConfig.EDGEML_DEVICE_ACCESS_TOKEN }
        val orgId = BuildConfig.EDGEML_ORG_ID.ifBlank { SampleConfig.EDGEML_ORG_ID }
        val modelId = BuildConfig.EDGEML_MODEL_ID.ifBlank { SampleConfig.EDGEML_MODEL_ID }

        // Build configuration
        val config =
            EdgeMLConfig
                .Builder()
                .serverUrl(serverUrl)
                .deviceAccessToken(deviceToken)
                .orgId(orgId)
                .modelId(modelId)
                .debugMode(SampleConfig.DEBUG)
                .enableGpuAcceleration(true)
                .enableBackgroundSync(true)
                .syncIntervalMinutes(60)
                .allowDegradedTraining(true)
                .privacyConfiguration(PrivacyConfiguration.MODERATE)
                .build()

        // Create EdgeML client
        EdgeMLClient
            .Builder(this)
            .config(config)
            .build()

        Timber.i("EdgeML SDK configured")
    }
}

/**
 * Sample configuration constants.
 *
 * These are fallback values used when BuildConfig fields (from local.properties)
 * are empty. For real usage, set values in your project's local.properties:
 *
 * ```
 * EDGEML_SERVER_URL=https://your-server.example.com
 * EDGEML_DEVICE_TOKEN=your-token
 * EDGEML_ORG_ID=your-org
 * EDGEML_MODEL_ID=your-model
 * ```
 */
object SampleConfig {
    const val DEBUG = true
    const val EDGEML_SERVER_URL = "https://api.edgeml.ai"
    const val EDGEML_DEVICE_ACCESS_TOKEN = "your-device-token-here"
    const val EDGEML_ORG_ID = "your-org-id-here"
    const val EDGEML_MODEL_ID = "your-model-id-here"
}
