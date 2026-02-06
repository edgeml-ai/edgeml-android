# EdgeML Android SDK

Official Android SDK for the EdgeML federated learning platform.

## Features

- Automatic Device Registration - Collects and sends complete hardware metadata
- Real-Time Monitoring - Tracks battery level, network type, and system constraints
- Stable Device IDs - Uses Android ID as stable identifier
- NNAPI & TFLite Optimization - Leverages hardware acceleration
- Privacy-First - All training happens on-device

## Installation

### Gradle

```gradle
dependencies {
    implementation 'ai.edgeml:edgeml-android:1.1.0'
}
```

### Maven

```xml
<dependency>
    <groupId>ai.edgeml</groupId>
    <artifactId>edgeml-android</artifactId>
    <version>1.1.0</version>
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
import android.content.Context
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class EdgeMLClient(
    context: Context,
    private val deviceToken: String,
    private val baseUrl: String = "https://api.edgeml.io"
) {
    private val client = OkHttpClient()
    private val deviceInfo = DeviceInfo(context)
    private var deviceServerId: String? = null

    suspend fun register(orgId: String): String = withContext(Dispatchers.IO) {
        val data = JSONObject(deviceInfo.toRegistrationMap())
        data.put("org_id", orgId)
        data.put("sdk_version", "1.1.0")

        val requestBody = data.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/api/v1/devices/register")
            .post(requestBody)
            .header("Authorization", "Bearer $deviceToken")
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
            .header("Authorization", "Bearer $deviceToken")
            .build()

        client.newCall(request).execute()
    }
}

// Usage
val client = EdgeMLClient(
    context = this,
    deviceToken = "short_lived_token_from_backend"
)
lifecycleScope.launch {
    val deviceId = client.register(orgId = "your_org_id")
    Log.d("EdgeML", "Registered with ID: $deviceId")

    // Send periodic heartbeats
    client.sendHeartbeat()
}
```

## Security Guidance

- Do not ship long-lived org API keys in Android apps.
- Mint short-lived device tokens from your backend after app/user authentication.
- Bind each token to one organization and minimum required scopes.

## Runtime Auth Manager

```kotlin
val auth = DeviceAuthManager(
    context = this,
    baseUrl = "https://api.edgeml.io",
    orgId = "your_org_id",
    deviceIdentifier = "device-123"
)

// Bootstrap with backend-issued bootstrap token
val tokenState = auth.bootstrap(
    bootstrapBearerToken = "token_from_your_backend"
)

// Get valid short-lived access token (auto-refreshes when expiring)
val accessToken = auth.getAccessToken()
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
            val client = EdgeMLClient(
                context = applicationContext,
                deviceToken = "short_lived_token_from_backend"
            )
            client.sendHeartbeat()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

// Schedule periodic heartbeats
fun scheduleHeartbeat(context: Context) {
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

Add this permission to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Battery level can be read without special privileged permissions.

## Requirements

- Android API 21+ (Android 5.0 Lollipop)
- Kotlin 1.5+
- AndroidX

## Privacy

The SDK collects hardware metadata and runtime constraints for:
- Training eligibility (battery, network)
- Device fleet monitoring
- Model compatibility

All training happens on-device. No personal data or training data is sent to servers.

## License

MIT
