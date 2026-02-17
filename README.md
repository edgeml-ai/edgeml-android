# EdgeML Android SDK

[![CI](https://github.com/edgeml-ai/edgeml-android/actions/workflows/ci.yml/badge.svg)](https://github.com/edgeml-ai/edgeml-android/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/edgeml-ai/edgeml-android/branch/main/graph/badge.svg)](https://codecov.io/gh/edgeml-ai/edgeml-android)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=edgeml-ai_edgeml-android&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=edgeml-ai_edgeml-android)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=edgeml-ai_edgeml-android&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=edgeml-ai_edgeml-android)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/edgeml-ai/edgeml-android/badge)](https://scorecard.dev/viewer/?uri=github.com/edgeml-ai/edgeml-android)
[![CodeQL](https://github.com/edgeml-ai/edgeml-android/actions/workflows/codeql.yml/badge.svg)](https://github.com/edgeml-ai/edgeml-android/actions/workflows/codeql.yml)
[![CII Best Practices](https://www.bestpractices.dev/projects/11912/badge)](https://www.bestpractices.dev/projects/11912)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-blue.svg)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-green.svg)](https://github.com/edgeml-ai/edgeml-android)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

> Enterprise-grade Android SDK for privacy-preserving federated learning on mobile devices.

## Overview

The EdgeML Android SDK brings production-ready federated learning to Android smartphones and tablets. Designed with Google's Android security model in mind, it implements real on-device training via TFLite model signatures while maintaining complete data sovereignty.

### Key Features

- **🔒 Privacy-First**: All training happens on-device, data never leaves the phone
- **⚡ TensorFlow Lite Optimized**: Leverages NNAPI for hardware-accelerated on-device training via model signatures
- **📱 Production Ready**: Complete hardware metadata and runtime constraint monitoring
- **🔋 Battery Aware**: Training eligibility based on battery level and charging state
- **📶 Network Smart**: Respects WiFi-only preferences for model sync
- **✅ Type Safe**: 100% Kotlin with comprehensive type safety
- **🔐 Keystore Integration**: Secure token storage using Android Keystore

### Security & Privacy

- ✅ **Code Coverage**: >80% test coverage
- ✅ **Static Analysis**: SonarCloud quality gates enforced
- ✅ **Security Scanning**: Android Lint checks on every commit
- ✅ **Data Privacy**: Training data never leaves device
- ✅ **Secure Storage**: Android Keystore-backed credential encryption
- ✅ **Stable IDs**: Android ID based device identification

## Requirements

- Android 5.0+ (API level 21+)
- Kotlin 1.9+
- Gradle 8.0+

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("ai.edgeml:edgeml-android:1.0.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'ai.edgeml:edgeml-android:1.0.0'
}
```

### Optional Dependencies

```kotlin
dependencies {
    // TensorFlow Lite GPU delegate
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")

    // WorkManager for background sync
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
```

## Quick Start

### Local Inference — No Server Required

Deploy and benchmark TFLite models locally. No server, no auth, no registration needed:

```kotlin
import ai.edgeml.EdgeML

// Deploy a model — auto-benchmarks NPU/GPU/CPU delegates, picks fastest
val model = EdgeML.deploy(context, "MobileNet.tflite")

val result = model.predict(floatArrayOf(1f, 2f, 3f)).getOrThrow()

println(model.name)            // "MobileNet"
println(model.engine)          // Engine.TFLITE
println(model.activeDelegate)  // "gpu"
println(model.warmupResult)    // cold: 62ms, warm: 4ms, cpu: 12ms
```

Skip benchmarking for faster startup:

```kotlin
val model = EdgeML.deploy(context, "model.tflite", benchmark = false)
```

When you're ready to connect to the EdgeML platform, initialize with your API key and the SDK automatically starts reporting metrics.

### Enterprise Runtime Authentication

For production deployments, use secure token-based authentication:

```kotlin
import ai.edgeml.sdk.DeviceAuthManager
import ai.edgeml.client.EdgeMLClient
import ai.edgeml.config.EdgeMLConfig

class MyApplication : Application() {

    private lateinit var authManager: DeviceAuthManager
    private lateinit var edgeMLClient: EdgeMLClient

    override fun onCreate() {
        super.onCreate()

        // Initialize device auth manager
        authManager = DeviceAuthManager(
            context = applicationContext,
            baseUrl = "https://api.edgeml.io",
            orgId = "org_123",
            deviceIdentifier = "android-device-${getDeviceId()}",
        )

        // Bootstrap with backend-issued token (one-time)
        lifecycleScope.launch {
            val tokenState = authManager.bootstrap(
                bootstrapBearerToken = getBackendBootstrapToken()
            )

            // Initialize EdgeML client
            initializeEdgeML()
        }
    }

    private suspend fun initializeEdgeML() {
        val config = EdgeMLConfig.Builder()
            .serverUrl("https://api.edgeml.io")
            .deviceAccessToken(authManager.getAccessToken())
            .orgId("org_123")
            .modelId("recommendation-model")
            .debugMode(BuildConfig.DEBUG)
            .build()

        edgeMLClient = EdgeMLClient.Builder(applicationContext)
            .config(config)
            .build()

        edgeMLClient.initialize().onSuccess {
            Log.i("EdgeML", "Client initialized successfully")
        }.onFailure { error ->
            Log.e("EdgeML", "Initialization failed", error)
        }
    }

    private fun getDeviceId(): String {
        return Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }
}
```

### Full Integration Example

```kotlin
import ai.edgeml.client.EdgeMLClient
import ai.edgeml.training.TrainingConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class FederatedLearningManager(
    private val context: Context,
    private val authManager: DeviceAuthManager
) {

    private lateinit var client: EdgeMLClient

    suspend fun initialize() {
        val config = EdgeMLConfig.Builder()
            .serverUrl("https://api.edgeml.io")
            .deviceAccessToken(authManager.getAccessToken())
            .orgId("org_123")
            .modelId("sentiment-model")
            .build()

        client = EdgeMLClient.Builder(context)
            .config(config)
            .build()

        client.initialize().getOrThrow()
    }

    suspend fun startTraining() {
        // Check training eligibility
        if (!isEligibleForTraining()) {
            Log.w("EdgeML", "Device not eligible for training")
            return
        }

        val trainingConfig = TrainingConfig(
            batchSize = 32,
            epochs = 5,
            learningRate = 0.001f,
        )

        client.train(trainingConfig)
            .collect { result ->
                when (result) {
                    is TrainingResult.Progress -> {
                        Log.i("EdgeML", "Training progress: ${result.progress}%")
                    }
                    is TrainingResult.Success -> {
                        Log.i("EdgeML", "Training completed successfully")
                        uploadModelUpdate(result.modelDelta)
                    }
                    is TrainingResult.Error -> {
                        Log.e("EdgeML", "Training failed", result.error)
                    }
                }
            }
    }

    private fun isEligibleForTraining(): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging

        return (batteryLevel > 20 && isCharging) || batteryLevel > 50
    }

    private suspend fun uploadModelUpdate(modelDelta: ByteArray) {
        client.uploadModelUpdate(modelDelta).getOrThrow()
    }
}
```

## Architecture

The Android SDK provides production-ready federated learning with hardware-accelerated training:

### Token Lifecycle

- **Access Token**: 15 minutes (configurable, max 60 minutes)
- **Refresh Token**: 30 days (automatically rotated on refresh)
- **Storage**: Android Keystore-backed encrypted storage

The SDK automatically handles token refresh and secure credential storage.

### Device Information Collected

#### Hardware Metadata
- **Manufacturer**: Device manufacturer (e.g., "Samsung", "Google")
- **Model**: Device model (e.g., "SM-G998B", "Pixel 7")
- **CPU Architecture**: ARM architecture (e.g., "arm64-v8a")
- **Total Memory**: Available RAM in MB
- **Available Storage**: Free disk space in MB
- **NNAPI Support**: Hardware acceleration availability

#### Runtime Constraints
- **Battery Level**: 0-100%
- **Charging State**: Plugged in or running on battery
- **Network Type**: WiFi, cellular, or offline

#### System Information
- **Platform**: "android"
- **OS Version**: Android version
- **API Level**: Android API level
- **Locale**: User's language and region
- **Timezone**: Device timezone

## On-Device Training

The SDK implements real on-device training using TFLite model signatures. The `TFLiteTrainer` uses `runSignature` to invoke dedicated train, infer, save, and restore operations defined in the TFLite model. For models without training signatures, the trainer falls back to inference-only mode with real loss computation.

### Weight Extraction

The `WeightExtractor` directly parses `.tflite` FlatBuffer binary format to extract model weights for federated aggregation. This approach requires no additional dependencies beyond the core TFLite runtime.

## Configuration

### Environment Variables

```bash
# API Configuration
export EDGEML_API_BASE="https://api.edgeml.io/api/v1"
export EDGEML_ORG_ID="your-org-id"

# Device Configuration
export EDGEML_DEVICE_ID="unique-device-identifier"
export EDGEML_PLATFORM="android"

# Logging
export EDGEML_LOG_LEVEL="INFO"  # DEBUG, INFO, WARNING, ERROR
```

### Advanced Configuration

```kotlin
val config = EdgeMLConfig.Builder()
    .serverUrl("https://api.edgeml.io")
    .deviceAccessToken(accessToken)
    .orgId("org_123")
    .modelId("recommendation-model")
    .timeout(30000L)  // API timeout in milliseconds
    .retryAttempts(3)  // Number of retry attempts
    .wifiOnly(true)    // Only sync over WiFi
    .debugMode(false)  // Enable debug logging
    .build()
```

## Background Training

### WorkManager Integration

```kotlin
import androidx.work.*
import java.util.concurrent.TimeUnit

class TrainingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val authManager = DeviceAuthManager.getInstance(applicationContext)
        val learningManager = FederatedLearningManager(applicationContext, authManager)

        return try {
            learningManager.initialize()
            learningManager.startTraining()
            Result.success()
        } catch (e: Exception) {
            Log.e("TrainingWorker", "Training failed", e)
            Result.retry()
        }
    }
}

// Schedule periodic training
fun schedulePeriodicTraining(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)  // WiFi only
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(true)
        .build()

    val trainingRequest = PeriodicWorkRequestBuilder<TrainingWorker>(
        repeatInterval = 24,
        repeatIntervalTimeUnit = TimeUnit.HOURS
    )
        .setConstraints(constraints)
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS
        )
        .build()

    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
            "federated_learning_training",
            ExistingPeriodicWorkPolicy.KEEP,
            trainingRequest
        )
}
```

### AndroidManifest.xml

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Required permissions -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- Optional: For background work -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application>
        <!-- WorkManager initialization -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup" />
        </provider>
    </application>
</manifest>
```

## Best Practices

### Security

1. **Use HTTPS Only**: Never send credentials over HTTP
2. **Secure Storage**: Use Android Keystore for sensitive data
3. **Certificate Pinning**: Enable SSL pinning for production
4. **Background Tasks**: Use WorkManager for periodic updates
5. **ProGuard/R8**: Enable code obfuscation for release builds

### Performance

1. **Battery Awareness**: Only train when battery > 20% and charging
2. **WiFi Preference**: Default to WiFi-only for model downloads
3. **NNAPI Acceleration**: Leverage Neural Network API when available
4. **Memory Management**: Monitor memory usage during training
5. **Model Caching**: Cache downloaded models locally

### Privacy

1. **Differential Privacy**: Enable noise injection for gradient updates
2. **Federated Analytics**: Use aggregated metrics only
3. **User Consent**: Request explicit consent for federated learning
4. **Data Minimization**: Send only model gradients, never raw data
5. **Secure Deletion**: Properly clear sensitive data on logout

### Reliability

1. **Handle errors gracefully**: Implement retry logic with exponential backoff
2. **Monitor metrics**: Track success rates and latencies
3. **Health checks**: Implement periodic connectivity tests
4. **Fallback strategies**: Design degradation paths for API failures

## Testing

```bash
# Run all tests
./gradlew test

# Run with coverage
./gradlew testDebugUnitTest jacocoTestReport

# Run instrumented tests
./gradlew connectedAndroidTest

# Generate coverage report
./gradlew jacocoTestReport

# View coverage report
open edgeml/build/reports/jacoco/jacocoTestReport/html/index.html
```

## Documentation

For full SDK documentation, see [https://docs.edgeml.io/sdks/android](https://docs.edgeml.io/sdks/android)

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

### Development Setup

```bash
# Clone repository
git clone https://github.com/edgeml-ai/edgeml-android.git
cd edgeml-android

# Open in Android Studio
open -a "Android Studio" .

# Or build from command line
./gradlew build
./gradlew test
```

## Privacy Statement

### Data Collection Disclosure

The SDK automatically collects the following device information:

**Hardware Metadata** (collected once at registration):
- Device manufacturer, model, and CPU architecture
- Total RAM and available storage
- NNAPI (Neural Network API) availability
- Android OS version and API level
- Locale and timezone

**Runtime Constraints** (collected periodically via heartbeats):
- **Battery Level** (0-100%): Read using `BatteryManager.BATTERY_PROPERTY_CAPACITY`
- **Charging State**: Whether device is plugged in (`BatteryManager.isCharging`)
- **Network Type**: WiFi, cellular, or offline status

### No User Permissions Required

The SDK uses Android system APIs that **do not require runtime permissions**:
- `BatteryManager` - reads battery percentage and charging state
- `ConnectivityManager` - detects network type
- `android.os.Build` - reads device specifications

Users are **not** prompted for any permissions to enable this data collection.

### Why This Data is Collected

- **Training Eligibility**: Ensures training only happens when battery/network conditions are suitable
- **Fleet Monitoring**: Helps understand device distribution and health across your user base
- **Model Compatibility**: Ensures models fit within device hardware capabilities

### Google Play Privacy Disclosure

**You must disclose this data collection in your Google Play Data Safety form:**
- Under "Device or other IDs" → Include Android ID
- Under "App info and performance" → Include crash logs, diagnostics
- Under "Device or other IDs" → Include device specs (battery, network)
- Mark usage as "Analytics" or "App functionality"

### Required AndroidManifest.xml Permissions

The SDK only requires these normal (non-dangerous) permissions:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

These do **not** trigger runtime permission prompts on Android 6.0+.

### What Data is NOT Collected

**Important**: All training happens **on-device**. The SDK never collects or transmits:
- ❌ Personal information or user data
- ❌ Training datasets or raw input data
- ❌ Location data
- ❌ Contacts, photos, or files
- ❌ User behavior or app usage patterns
- ❌ Precise device identifiers (IMEI, MAC address)

Only aggregated model gradients (mathematical weight updates) are uploaded to the server.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Support

For issues and feature requests, please use the [GitHub issue tracker](https://github.com/edgeml-ai/edgeml-android/issues).

For questions: support@edgeml.io

---

<p align="center">
  <strong>Built with ❤️ by the EdgeML Team</strong>
</p>
