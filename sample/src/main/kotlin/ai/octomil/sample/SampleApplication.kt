package ai.octomil.sample

import ai.octomil.chat.LLMRuntimeRegistry
import ai.octomil.client.OctomilClient
import ai.octomil.config.OctomilConfig
import ai.octomil.config.PrivacyConfiguration
import ai.octomil.discovery.DiscoveryManager
import ai.octomil.sample.chat.LlamaCppRuntime
import android.app.Application
import android.provider.Settings
import timber.log.Timber

/**
 * Sample application demonstrating Octomil SDK initialization and network discovery.
 *
 * On launch this class:
 * 1. Initialises the Octomil SDK client with config from BuildConfig / fallback constants.
 * 2. Starts mDNS/NSD advertising so the device is discoverable by `octomil deploy --phone`.
 */
class SampleApplication : Application() {

    /** High-level manager for local-network device discovery. */
    val discoveryManager: DiscoveryManager by lazy { DiscoveryManager(this) }

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        Timber.plant(Timber.DebugTree())

        // Register llama.cpp as the LLM runtime for GGUF models
        LLMRuntimeRegistry.factory = { modelFile ->
            LlamaCppRuntime(modelFile, this)
        }

        // Initialize Octomil SDK
        initializeOctomil()

        // Start local network discovery so `octomil deploy --phone` can find this device
        startDiscovery()
    }

    override fun onTerminate() {
        discoveryManager.stopDiscoverable()
        super.onTerminate()
    }

    private fun initializeOctomil() {
        // Read config from BuildConfig (generated from local.properties),
        // falling back to SampleConfig placeholders.
        val serverUrl = BuildConfig.OCTOMIL_SERVER_URL.ifBlank { SampleConfig.OCTOMIL_SERVER_URL }
        val deviceToken = BuildConfig.OCTOMIL_DEVICE_TOKEN.ifBlank { SampleConfig.OCTOMIL_DEVICE_ACCESS_TOKEN }
        val orgId = BuildConfig.OCTOMIL_ORG_ID.ifBlank { SampleConfig.OCTOMIL_ORG_ID }
        val modelId = BuildConfig.OCTOMIL_MODEL_ID.ifBlank { SampleConfig.OCTOMIL_MODEL_ID }

        // Build configuration
        val config =
            OctomilConfig
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

        // Create Octomil client
        OctomilClient
            .Builder(this)
            .config(config)
            .build()

        Timber.i("Octomil SDK configured")
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
 * OCTOMIL_SERVER_URL=https://your-server.example.com
 * OCTOMIL_DEVICE_TOKEN=your-token
 * OCTOMIL_ORG_ID=your-org
 * OCTOMIL_MODEL_ID=your-model
 * ```
 */
object SampleConfig {
    const val DEBUG = true
    const val OCTOMIL_SERVER_URL = "https://api.octomil.com"
    const val OCTOMIL_DEVICE_ACCESS_TOKEN = "your-device-token-here"
    const val OCTOMIL_ORG_ID = "your-org-id-here"
    const val OCTOMIL_MODEL_ID = "your-model-id-here"
}
