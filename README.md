<p align="center">
  <strong>Octomil Android</strong><br>
  On-device ML for Android.
</p>

<p align="center">
  <a href="https://github.com/octomil/octomil-android/actions/workflows/ci.yml"><img src="https://github.com/octomil/octomil-android/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/octomil/octomil-android"><img src="https://img.shields.io/badge/Android-5.0%2B-green.svg" alt="Android 5.0+"></a>
  <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="MIT"></a>
</p>

---

## Install

```kotlin
dependencies {
    implementation("ai.octomil:octomil-android:1.0.0")
}
```

## Quick Start

Deploy a TFLite model and run inference locally. No server needed:

```kotlin
import ai.octomil.Octomil

val model = Octomil.deploy(context, "MobileNet.tflite")

val result = model.predict(floatArrayOf(1f, 2f, 3f)).getOrThrow()

println(model.activeDelegate)  // "gpu"
println(model.warmupResult)    // cold: 62ms, warm: 4ms
```

## Server Integration

Connect to the Octomil platform for model management and federated learning:

```kotlin
import ai.octomil.client.OctomilClient
import ai.octomil.config.OctomilConfig

val config = OctomilConfig.Builder()
    .serverUrl("https://api.octomil.com")
    .deviceAccessToken("<device-token>")
    .orgId("org_123")
    .modelId("fraud_detection")
    .build()

val client = OctomilClient.Builder(context).config(config).build()
client.initialize().getOrThrow()

val result = client.runInference(inputData)
```

## Training

On-device training with automatic round management:

```kotlin
val trainingConfig = TrainingConfig(batchSize = 32, epochs = 5, learningRate = 0.001f)

client.train(trainingConfig).collect { result ->
    when (result) {
        is TrainingResult.Progress -> Log.i("Octomil", "${result.progress}%")
        is TrainingResult.Success  -> client.uploadModelUpdate(result.modelDelta)
        is TrainingResult.Error    -> Log.e("Octomil", "Failed", result.error)
    }
}
```

## Highlights

- TFLite + NNAPI with automatic delegate benchmarking (NPU/GPU/CPU)
- Streaming inference across text, image, audio, and video
- Federated learning with secure aggregation
- On-device personalization (Ditto, FedPer)
- Battery and network aware training via WorkManager
- Android Keystore-backed token storage
- NSD/mDNS discovery for `octomil deploy --phone`
- 100% Kotlin

## Documentation

[docs.octomil.com/sdks/android](https://docs.octomil.com/sdks/android)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
