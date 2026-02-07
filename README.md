# EdgeML Android SDK

Kotlin SDK for privacy-safe on-device personalization and federated learning.

## Enterprise Runtime Auth (required)

Do not embed org API keys in shipped apps. Use backend-issued bootstrap tokens and short-lived device credentials.

**Server endpoints**
- `POST /api/v1/device-auth/bootstrap`
- `POST /api/v1/device-auth/refresh`
- `POST /api/v1/device-auth/revoke`

**Default lifetimes**
- Access token: 15 minutes (configurable, max 60 minutes)
- Refresh token: 30 days (rotated on refresh)

## Installation

```kotlin
dependencies {
    implementation("ai.edgeml:edgeml-android:1.0.0")
}
```

## Quick Start (Enterprise)

### 1) Bootstrap short-lived device auth

```kotlin
import ai.edgeml.sdk.DeviceAuthManager

val auth = DeviceAuthManager(
    context = applicationContext,
    baseUrl = "https://api.edgeml.io",
    orgId = "org_123",
    deviceIdentifier = "android-device-abc",
)

val tokenState = auth.bootstrap(bootstrapBearerToken = backendBootstrapToken)
val accessToken = auth.getAccessToken()
```

### 2) Initialize EdgeML client

```kotlin
import ai.edgeml.client.EdgeMLClient
import ai.edgeml.config.EdgeMLConfig

val config = EdgeMLConfig.Builder()
    .serverUrl("https://api.edgeml.io")
    .deviceAccessToken(accessToken)
    .orgId("org_123")
    .modelId("ad-relevance")
    .debugMode(BuildConfig.DEBUG)
    .build()

val client = EdgeMLClient.Builder(applicationContext)
    .config(config)
    .build()
```

### 3) Register and run

```kotlin
client.initialize().onSuccess {
    // ready
}
```

## Token lifecycle

- Call `getAccessToken()` before protected API calls.
- SDK refreshes near expiry via refresh token.
- On logout/device compromise, revoke session and clear secure state.

## Secure storage

Token state is encrypted using Android Keystore-backed storage.

## Core capabilities

- device registration and heartbeat
- model download/cache with integrity checks
- on-device inference (TFLite)
- local training + update upload
- WorkManager background sync

## Docs

- https://docs.edgeml.io/sdks/android
- https://docs.edgeml.io/reference/api-endpoints
