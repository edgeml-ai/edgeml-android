# Octomil Android SDK

Run ML models on-device with one function call. TFLite + NNAPI + GPU, automatic hardware benchmarking, telemetry, and OTA updates.

[![CI](https://github.com/octomil/octomil-android/actions/workflows/ci.yml/badge.svg)](https://github.com/octomil/octomil-android/actions/workflows/ci.yml)
[![CodeQL](https://github.com/octomil/octomil-android/actions/workflows/codeql.yml/badge.svg)](https://github.com/octomil/octomil-android/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/github/license/octomil/octomil-android)](LICENSE)

## What is this?

A Kotlin SDK that deploys TFLite models to Android devices and handles the hard parts: hardware acceleration selection, delegate benchmarking, inference telemetry, and model updates. Drop a `.tflite` file in your assets, call `Octomil.deploy()`, and get GPU/NPU-accelerated inference with zero boilerplate. Optionally connect to the Octomil platform for federated learning, A/B experiments, and fleet-wide model management.

## Installation

```kotlin
// build.gradle.kts
repositories {
    maven("https://maven.pkg.github.com/octomil/octomil-android")
}

dependencies {
    implementation("ai.octomil:octomil:1.2.0")
}
```

## Quick Start

```kotlin
import ai.octomil.Octomil

// Load from assets/ and auto-benchmark hardware delegates
val model = Octomil.deploy(context, "mobilenet.tflite")

val result = model.predict(inputData).getOrThrow()
println(result.predictions)        // [0.02, 0.91, 0.07, ...]
println(model.activeDelegate)      // "gpu"
println(model.warmupResult)        // cold: 62ms, warm: 4ms
```

That's it. The SDK copies the asset to cache, loads the TFLite interpreter, benchmarks GPU vs CPU vs vendor NPU, picks the fastest delegate, and warms up the model.

## Features

### Inference with automatic hardware selection

```kotlin
val options = LocalModelOptions(
    enableGpu = true,           // TFLite GPU delegate
    enableVendorNpu = true,     // Qualcomm QNN / Samsung Eden / MediaTek NeuroPilot
    enableFloat16 = true,       // ~2x throughput on supported GPUs
    preferBigCores = true,      // Pin to performance cores on ARM big.LITTLE
)
val model = Octomil.deploy(context, "classifier.tflite", options = options)
```

### Classification

```kotlin
val topK = model.classify(inputData, topK = 5).getOrThrow()
topK.forEach { (classIndex, confidence) ->
    println("Class $classIndex: ${confidence * 100}%")
}
```

### Batch inference

```kotlin
val results = model.predictBatch(listOf(input1, input2, input3)).getOrThrow()
```

### Drop-in TFLite wrapper (one-line migration)

Already using `Interpreter` directly? Wrap it to get telemetry and OTA updates with no call-site changes:

```kotlin
import ai.octomil.wrapper.Octomil

// Before
val interpreter = Interpreter(modelFile)
interpreter.run(input, output)

// After — same predict API, plus telemetry + OTA
val interpreter = Octomil.wrap(Interpreter(modelFile), modelId = "classifier")
interpreter.predict(input, output)
```

### Streaming inference (text, image, audio, video)

```kotlin
client.stream(prompt, modality = Modality.TEXT).collect { chunk ->
    print(String(chunk.data))  // token-by-token output
}
```

### On-device training (federated learning)

```kotlin
val outcome = client.train(
    dataProvider = InMemoryTrainingDataProvider(trainingData),
    config = TrainingConfig(batchSize = 32, epochs = 5, learningRate = 0.001f),
)
println("Loss: ${outcome.trainingResult.loss}")
// Weight updates are uploaded to the server automatically (or manually via UploadPolicy.MANUAL)
```

### Telemetry and model management

Connect to the Octomil platform for fleet-wide observability:

```kotlin
val config = OctomilConfig.Builder()
    .serverUrl("https://api.octomil.com")
    .deviceAccessToken("<device-token>")
    .orgId("org_123")
    .modelId("fraud_detection")
    .build()

val client = OctomilClient.Builder(context).config(config).build()
client.initialize().getOrThrow()

// Inference, training, experiments, telemetry — all through client
val result = client.runInference(inputData)
```

## Why not raw TFLite?

| | Octomil SDK | Raw TFLite |
|---|---|---|
| Hardware delegate selection | Automatic benchmarking (GPU/NPU/CPU) | Manual setup per device |
| Model loading | `Octomil.deploy(ctx, "model.tflite")` | Copy asset, create Interpreter, configure delegates, handle errors |
| Telemetry | Built-in latency/error tracking | Build your own |
| OTA model updates | Config flag | Build your own |
| Batch inference | `.predictBatch()` | Manual loop |
| Vendor NPU support | Auto-detected via reflection | Integrate each vendor SDK |
| Federated learning | `.train()` with secure aggregation | Not available |

## Supported Models

- **Format:** TensorFlow Lite (`.tflite`)
- **Delegates:** CPU, GPU (OpenGL/OpenCL), NNAPI, Qualcomm QNN, Samsung Eden, MediaTek NeuroPilot
- **Modalities:** Classification, regression, text generation, image, audio, video
- **Training:** On-device gradient updates (requires TFLite training signatures)

## Requirements

- Android API 24+ (Android 7.0)
- Kotlin 1.9+
- TFLite models (`.tflite` format)

## Architecture

```
ai.octomil
├── Octomil              # Local-first entry point: deploy, loadModel
├── wrapper/Octomil      # Drop-in TFLite Interpreter wrapper
├── client/              # OctomilClient — server-connected client
├── inference/           # Streaming engines (text, image, audio, video)
├── training/            # On-device training, weight extraction
├── models/              # Model manager, caching, versioning
├── runtime/             # Adaptive interpreter, device state monitoring
├── secagg/              # Secure aggregation (ECDH, Shamir, SecAgg+)
├── experiments/         # A/B testing client
├── analytics/           # Federated analytics
├── privacy/             # Differential privacy
├── discovery/           # NSD/mDNS for `octomil deploy --phone`
├── pairing/             # Device pairing UI (Compose)
└── storage/             # Android Keystore-backed secure storage
```

## Octomil Manifest

The [Octomil Manifest](https://github.com/octomil/octomil-python#manifest) (`octomil.yaml`) is a declarative config file that describes which models your app needs, their delivery mode (`bundled`, `managed`, or `cloud`), and which capability each model serves. Generate it with the CLI:

```bash
octomil manifest init
```

The Android SDK reads `octomil.yaml` via `AppManifest` and `ModelCatalogService` to handle model downloads and runtime resolution automatically.

## Samples

Minimal, copyable examples for the main SDK capabilities:

| Sample | Capability | Key API |
|--------|-----------|---------|
| `ChatSampleActivity` | Text generation | `Octomil.responses.stream()` |
| `TranscriptionSampleActivity` | Speech-to-text | `Octomil.audio.transcribe()` |
| `PredictionSampleActivity` | Next-word prediction | `Octomil.text.predict()` |

Each sample is a single Activity (~100 lines) in the [`samples/`](samples/) module. Build and install:

```bash
./gradlew :samples:installDebug
```

**Prerequisites:** At least one deployed model per capability. See [samples/README.md](samples/README.md) for details.

> **Looking for the companion app?** The [Octomil Android App](https://github.com/octomil/octomil-app-android) is a broader evaluation and dogfood app covering all capabilities, device pairing, discovery, and golden tests. These SDK samples are intentionally minimal — use them as a starting point for your own integration.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
