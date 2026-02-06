# EdgeML Android SDK

Privacy-preserving federated learning SDK for Android devices. Part of the EdgeML platform.

## Features

- **Device Registration**: Automatic device registration with the EdgeML server
- **Model Management**: Download, cache, and manage TFLite models with integrity verification
- **TFLite Inference**: GPU-accelerated inference using TensorFlow Lite
- **Background Sync**: WorkManager-based background synchronization
- **Secure Storage**: EncryptedSharedPreferences for sensitive data
- **Offline Support**: Queue events when offline for later sync

## Requirements

- Android API 24+ (Android 7.0 Nougat)
- Kotlin 1.9+
- TensorFlow Lite 2.14+

## Installation

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'ai.edgeml:edgeml-android:1.0.0'
}
```

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("ai.edgeml:edgeml-android:1.0.0")
}
```

### Local Development

Include the module directly:

```kotlin
// settings.gradle.kts
include(":edgeml")
project(":edgeml").projectDir = file("path/to/edgeml-android/edgeml")

// app/build.gradle.kts
dependencies {
    implementation(project(":edgeml"))
}
```

## Quick Start

### 1. Configure the SDK

```kotlin
import ai.edgeml.client.EdgeMLClient
import ai.edgeml.config.EdgeMLConfig

// Create configuration
val config = EdgeMLConfig.Builder()
    .serverUrl("https://api.edgeml.ai")
    .apiKey("your-api-key")
    .orgId("your-org-id")
    .modelId("your-model-id")
    .debugMode(BuildConfig.DEBUG)
    .build()

// Initialize client (typically in Application.onCreate)
val client = EdgeMLClient.Builder(context)
    .config(config)
    .build()
```

### 2. Initialize and Register Device

```kotlin
lifecycleScope.launch {
    val result = client.initialize()

    result.onSuccess {
        Log.d("EdgeML", "SDK initialized successfully")
    }.onFailure { error ->
        Log.e("EdgeML", "Initialization failed", error)
    }
}
```

### 3. Run Inference

```kotlin
lifecycleScope.launch {
    // Get model input shape
    val modelInfo = client.getModelInfo()
    val inputSize = modelInfo?.inputShape?.fold(1) { acc, dim -> acc * dim } ?: 0

    // Prepare input data (replace with your actual data)
    val inputData = FloatArray(inputSize) { /* your data */ }

    // Run inference
    val result = client.runInference(inputData)

    result.onSuccess { output ->
        val topPredictions = output.topK(5)
        Log.d("EdgeML", "Predictions: $topPredictions")
        Log.d("EdgeML", "Inference time: ${output.inferenceTimeMs}ms")
    }.onFailure { error ->
        Log.e("EdgeML", "Inference failed", error)
    }
}
```

### 4. Classification Example

```kotlin
lifecycleScope.launch {
    val result = client.classify(inputData, topK = 5)

    result.onSuccess { predictions ->
        predictions.forEach { (classIndex, confidence) ->
            Log.d("EdgeML", "Class $classIndex: ${confidence * 100}%")
        }
    }
}
```

## Configuration Options

| Option | Default | Description |
|--------|---------|-------------|
| `serverUrl` | Required | EdgeML server URL |
| `apiKey` | Required | API authentication key |
| `orgId` | Required | Organization identifier |
| `modelId` | Required | Model identifier |
| `deviceId` | Auto-generated | Custom device ID |
| `debugMode` | `false` | Enable debug logging |
| `connectionTimeoutMs` | `30000` | Connection timeout |
| `readTimeoutMs` | `60000` | Read timeout |
| `writeTimeoutMs` | `60000` | Write timeout |
| `maxRetries` | `3` | Maximum retry attempts |
| `retryDelayMs` | `1000` | Retry delay (exponential backoff) |
| `modelCacheSizeBytes` | `100MB` | Model cache size limit |
| `enableGpuAcceleration` | `true` | Use GPU delegate |
| `numThreads` | `4` | TFLite interpreter threads |
| `enableBackgroundSync` | `true` | Enable WorkManager sync |
| `syncIntervalMinutes` | `60` | Sync interval (min 15) |
| `minBatteryLevel` | `20` | Minimum battery for sync |
| `requireCharging` | `false` | Require charging for sync |
| `requireUnmeteredNetwork` | `true` | Require WiFi for sync |
| `enableEncryptedStorage` | `true` | Use encrypted preferences |

## Model Management

### Check for Updates

```kotlin
lifecycleScope.launch {
    val result = client.updateModel()

    result.onSuccess { model ->
        Log.d("EdgeML", "Model updated: ${model.modelId} v${model.version}")
    }
}
```

### Observe Download Progress

```kotlin
lifecycleScope.launch {
    client.modelDownloadState.collect { state ->
        when (state) {
            is DownloadState.Idle -> { /* Not downloading */ }
            is DownloadState.CheckingForUpdates -> { /* Checking */ }
            is DownloadState.Downloading -> {
                val progress = state.progress.progress
                updateProgressBar(progress)
            }
            is DownloadState.Verifying -> { /* Verifying checksum */ }
            is DownloadState.Completed -> { /* Download complete */ }
            is DownloadState.UpToDate -> { /* Already up to date */ }
            is DownloadState.Failed -> {
                Log.e("EdgeML", "Download failed", state.error)
            }
        }
    }
}
```

### Get Model Information

```kotlin
val modelInfo = client.getModelInfo()
if (modelInfo != null) {
    Log.d("EdgeML", "Model: ${modelInfo.modelId} v${modelInfo.version}")
    Log.d("EdgeML", "Input shape: ${modelInfo.inputShape.contentToString()}")
    Log.d("EdgeML", "Output shape: ${modelInfo.outputShape.contentToString()}")
    Log.d("EdgeML", "Using GPU: ${modelInfo.usingGpu}")
}
```

## Background Sync

The SDK uses WorkManager for reliable background synchronization.

### Trigger Manual Sync

```kotlin
client.triggerSync()
```

### Cancel Sync

```kotlin
client.cancelSync()
```

### Sync Constraints

Background sync respects these conditions:
- Network connectivity (WiFi only by default)
- Battery level above minimum
- Not in low battery mode
- Charging (if configured)

## Event Tracking

Track custom events for analytics and debugging.

```kotlin
lifecycleScope.launch {
    client.trackEvent(
        eventType = "custom_event",
        metrics = mapOf("accuracy" to 0.95, "latency" to 12.5),
        metadata = mapOf("source" to "camera", "resolution" to "1080p")
    )
}
```

## Error Handling

The SDK uses Kotlin `Result` types for error handling.

```kotlin
val result = client.runInference(inputData)

result.fold(
    onSuccess = { output ->
        // Handle successful inference
    },
    onFailure = { error ->
        when (error) {
            is ModelDownloadException -> {
                when (error.errorCode) {
                    ErrorCode.NETWORK_ERROR -> { /* Handle network issue */ }
                    ErrorCode.CHECKSUM_MISMATCH -> { /* Model corrupted */ }
                    ErrorCode.INSUFFICIENT_STORAGE -> { /* Clear cache */ }
                    else -> { /* Handle other errors */ }
                }
            }
            is IllegalStateException -> {
                // SDK not initialized
            }
            else -> {
                // Other errors
            }
        }
    }
)
```

## Observing Client State

```kotlin
lifecycleScope.launch {
    client.state.collect { state ->
        when (state) {
            ClientState.UNINITIALIZED -> { /* Not initialized */ }
            ClientState.INITIALIZING -> { /* Initializing */ }
            ClientState.READY -> { /* Ready for inference */ }
            ClientState.ERROR -> { /* Error occurred */ }
            ClientState.CLOSED -> { /* Client closed */ }
        }
    }
}
```

## ProGuard Configuration

If you're using R8/ProGuard, add these rules:

```proguard
# EdgeML SDK
-keep class ai.edgeml.client.EdgeMLClient { *; }
-keep class ai.edgeml.config.EdgeMLConfig { *; }
-keep class ai.edgeml.models.** { *; }
-keep class ai.edgeml.training.** { *; }

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class ai.edgeml.** {
    *** Companion;
}
```

## Permissions

The SDK requires these permissions (automatically merged):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Optional permissions for background sync:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

## Architecture

```
edgeml-android/
├── edgeml/                      # Main SDK module (AAR)
│   └── src/main/kotlin/ai/edgeml/
│       ├── api/                 # Retrofit API layer
│       │   ├── EdgeMLApi.kt     # API interface
│       │   ├── EdgeMLApiFactory.kt
│       │   └── dto/             # Data transfer objects
│       ├── client/              # Main SDK client
│       │   └── EdgeMLClient.kt  # Entry point
│       ├── config/              # Configuration
│       │   └── EdgeMLConfig.kt
│       ├── models/              # Model management
│       │   ├── ModelManager.kt  # Download, cache, verify
│       │   └── ModelTypes.kt    # Data classes
│       ├── storage/             # Secure storage
│       │   └── SecureStorage.kt # EncryptedSharedPreferences
│       ├── sync/                # Background sync
│       │   ├── WorkManagerSync.kt
│       │   └── EventQueue.kt
│       ├── training/            # Inference
│       │   └── TFLiteTrainer.kt # TFLite wrapper
│       └── utils/               # Utilities
│           └── DeviceUtils.kt
└── sample/                      # Sample application
```

## Thread Safety

- All SDK operations are thread-safe
- Use Kotlin coroutines for async operations
- The SDK manages its own internal thread pool

## Resource Management

```kotlin
// Close the client when done
lifecycleScope.launch {
    client.close()
}
```

## Sample App

See the `sample/` directory for a complete integration example.

## License

Apache License 2.0

## Support

- Documentation: https://docs.edgeml.ai
- Issues: https://github.com/edgeml/edgeml-android/issues
- Email: support@edgeml.ai
