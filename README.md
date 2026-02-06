# EdgeML Android SDK

Official Android SDK for the EdgeML federated learning platform.

## Features

- ✅ **Automatic Device Registration** - Collects and sends complete hardware metadata
- ✅ **Real-Time Monitoring** - Tracks battery level, network type, and system constraints
- ✅ **Stable Device IDs** - Uses Android ID as stable identifier
- ✅ **NNAPI & TFLite Optimization** - Leverages hardware acceleration
- ✅ **Privacy-First** - All training happens on-device

## Installation

### Gradle

```gradle
dependencies {
    implementation 'ai.edgeml:edgeml-android:1.0.0'
}
```

### Maven

```xml
<dependency>
    <groupId>ai.edgeml</groupId>
    <artifactId>edgeml-android</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

```kotlin
import ai.edgeml.sdk.DeviceInfo

// Collect device information
val deviceInfo = DeviceInfo(context)

// Get registration payload
val registrationData = deviceInfo.toRegistrationMap()
Log.d("EdgeML", "Device ID: ${deviceInfo.deviceId}")

// Get current metadata (battery, network)
val metadata = deviceInfo.updateMetadata()
Log.d("EdgeML", "Battery: ${metadata["battery_level"]}%")
Log.d("EdgeML", "Network: ${metadata["network_type"]}")
```

## Device Information Collected

### Hardware
- Manufacturer (e.g., "Samsung", "Google", "Xiaomi")
- Model (e.g., "Pixel 7 Pro", "Galaxy S23")
- CPU Architecture (e.g., "arm64-v8a")
- Total Memory (MB)
- Available Storage (MB)
- GPU/NNAPI Available (boolean)

### Runtime Constraints
- Battery Level (0-100%)
- Network Type (wifi, cellular, ethernet, offline)

### System Info
- Platform: "android"
- Android Version
- Locale and Region
- Timezone

## Integration Example

```kotlin
import ai.edgeml.sdk.DeviceInfo
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class EdgeMLClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.edgeml.io"
) {
    private val client = OkHttpClient()
    private val deviceInfo = DeviceInfo(context)
    private var deviceServerId: String? = null

    suspend fun register(orgId: String): String = withContext(Dispatchers.IO) {
        val data = JSONObject(deviceInfo.toRegistrationMap())
        data.put("org_id", orgId)
        data.put("sdk_version", "1.0.0")

        val requestBody = data.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/api/v1/devices/register")
            .post(requestBody)
            .header("Authorization", "Bearer $apiKey")
            .build()

        val response = client.newCall(request).execute()
        val responseData = JSONObject(response.body?.string() ?: "{}")

        deviceServerId = responseData.getString("id")
        return@withContext deviceServerId ?: ""
    }

    suspend fun sendHeartbeat() = withContext(Dispatchers.IO) {
        val deviceId = deviceServerId
            ?: throw IllegalStateException("Device not registered")

        val metadata = JSONObject(deviceInfo.updateMetadata())
        val requestBody = JSONObject()
            .put("metadata", metadata)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/api/v1/devices/$deviceId/heartbeat")
            .put(requestBody)
            .header("Authorization", "Bearer $apiKey")
            .build()

        client.newCall(request).execute()
    }
}

// Usage
val client = EdgeMLClient(apiKey = "edg_your_key_here")
lifecycleScope.launch {
    val deviceId = client.register(orgId = "your_org_id")
    Log.d("EdgeML", "Registered with ID: $deviceId")

    // Send periodic heartbeats
    client.sendHeartbeat()
}
```

## Background Heartbeats with WorkManager

```kotlin
import androidx.work.*
import java.util.concurrent.TimeUnit

class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val client = EdgeMLClient(apiKey = "your_api_key")
            client.sendHeartbeat()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

// Schedule periodic heartbeats
fun scheduleHeartbeat() {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val heartbeatRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(
        15, TimeUnit.MINUTES
    )
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
            "edgeml_heartbeat",
            ExistingPeriodicWorkPolicy.KEEP,
            heartbeatRequest
        )
}
```

## Permissions

Add these permissions to your `AndroidManifest.xml`:

```xml
<!-- For network detection -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- For battery monitoring -->
<uses-permission android:name="android.permission.BATTERY_STATS" />

<!-- Optional: For cellular network info -->
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

## Requirements

- Android API 21+ (Android 5.0 Lollipop)
- Kotlin 1.5+
- AndroidX

## Privacy

The SDK collects hardware metadata and runtime constraints for:
- Training eligibility (battery, network)
- Device fleet monitoring
- Model compatibility

All training happens **on-device**. No personal data or training data is sent to servers.

## License

MIT
