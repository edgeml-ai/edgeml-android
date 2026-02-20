package ai.edgeml.sample

import ai.edgeml.client.EdgeMLClient
import ai.edgeml.config.EdgeMLConfig
import ai.edgeml.config.PrivacyConfiguration
import ai.edgeml.discovery.DiscoveryManager
import android.app.Application
import android.provider.Settings
import timber.log.Timber

/**
 * Sample application demonstrating EdgeML SDK initialization and network discovery.
 *
 * On launch this class:
 * 1. Initialises the EdgeML SDK client with config from BuildConfig / fallback constants.
 * 2. Starts mDNS/NSD advertising so the device is discoverable by `edgeml deploy --phone`.
 */
class SampleApplication : Application() {

    /** High-level manager for local-network device discovery. */
    val discoveryManager: DiscoveryManager by lazy { DiscoveryManager(this) }

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        Timber.plant(Timber.DebugTree())

        // Initialize EdgeML SDK
        initializeEdgeML()

        // Start local network discovery so `edgeml deploy --phone` can find this device
        startDiscovery()
    }

    override fun onTerminate() {
        discoveryManager.stopDiscoverable()
        super.onTerminate()
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

    private fun startDiscovery() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"
        val deviceName = android.os.Build.MODEL
        discoveryManager.startDiscoverable(
            deviceId = deviceId,
            deviceName = deviceName,
        )
        Timber.i("Discovery started: deviceId=%s deviceName=%s", deviceId, deviceName)
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
