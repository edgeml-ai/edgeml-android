# Architecture — octomil-android

## Repo Responsibility

Native Android SDK (minSdk 33, compileSdk 36) for the Octomil platform. Owns:

- **Runtime / session management** — Engine lifecycle, model loading, inference sessions
- **Planner** — Runtime planning and model selection per device capability
- **Device profile** — Hardware detection (NNAPI, GPU delegate, XNNPACK, memory, thermal state)
- **Hosted API client** — Chat, completions, embeddings, responses, model catalog
- **Unified facade** — Single entry point routing to local or cloud backends
- **Streaming** — SSE streaming for inference
- **Telemetry** — OpenTelemetry-compatible span instrumentation
- **Device auth** — Token bootstrap, refresh, publishable key auth
- **Training** — Federated learning participant (on-device training rounds)

## Module Layout

```
octomil/src/main/kotlin/ai/octomil/
├── generated/           # Enum types from octomil-contracts — DO NOT HAND-EDIT
├── runtime/
│   ├── core/            # Engine registry, session management, kernel
│   ├── engines/         # Engine adapters (LiteRT, MNN, ONNX RT, etc.)
│   ├── planner/         # Runtime planning and model selection
│   ├── routing/         # Smart routing (local vs cloud)
│   └── adaptation/      # Runtime adaptation and fallback
├── sdk/                 # SDK entry point, facade, auth, device context
├── client/              # Hosted API client (capabilities, embeddings, routing, telemetry)
├── api/                 # API models and request/response types
├── chat/                # Chat completions
├── audio/               # Audio transcription
├── text/                # Text generation helpers
├── responses/           # Responses API
├── streaming/           # SSE streaming helpers
├── models/              # Model types, references, resolution
├── manifest/            # Engine manifest types and resolution
├── discovery/           # Model discovery and catalog
├── analytics/           # Analytics and metrics
├── config/              # Configuration management
├── control/             # Control plane client
├── training/            # Federated learning participant
├── experiments/         # A/B experiment variants
├── pairing/             # Device pairing
├── personalization/     # On-device personalization
├── privacy/             # Privacy controls
├── secagg/              # Secure aggregation
├── speech/              # Speech processing
├── storage/             # Local model storage
├── sync/                # State sync with control plane
├── tryitout/            # Interactive demo helpers
├── workflows/           # Multi-step workflow orchestration
├── wrapper/             # Compatibility wrappers
├── errors/              # Error types
├── utils/               # Shared utilities
├── android/             # Android-specific utilities
├── Octomil.kt           # Main SDK entry point
├── Prelude.kt           # SDK prelude / common imports
├── DeployedModel.kt     # Deployed model types
├── LocalModel.kt        # Local model types
├── LocalModelOptions.kt # Local model options
├── ModelResolver.kt     # Model resolution
└── ModelNotFoundException.kt

octomil/src/test/kotlin/ai/octomil/
├── conformance/         # Contract conformance tests
├── runtime/             # Runtime-specific tests
├── sdk/                 # SDK tests
├── client/              # Client tests
└── <module>/            # Unit tests mirroring source structure
```

## Build System

- **Gradle** with Kotlin DSL (`build.gradle.kts`)
- Android Library plugin (`com.android.library`)
- Kotlin Serialization for JSON
- Jetpack Compose for UI components
- JaCoCo for coverage

## Boundary Rules

- **`generated/` is read-only**: Machine-generated from `octomil-contracts`. Never hand-edit.
- **Runtime engine deps are optional**: Engine adapters (LiteRT, MNN, ONNX RT) use runtime-scoped or optional dependencies. Missing native libs produce clear errors, not crashes.
- **No Activity/Fragment in SDK core**: UI helpers belong in `tryitout/` or the companion app (`octomil-app-android`), not the SDK.
- **`sdk/` is the public facade**: External consumers interact via `Octomil.kt` and `sdk/` package.

## Public API Surfaces

- `ai.octomil.sdk.Octomil` — Main entry point for SDK consumers
- Chat, Responses, Embeddings, Audio APIs via facade
- Coroutine-based async API with Flow streaming

## Generated Code

Location: `octomil/src/main/kotlin/ai/octomil/generated/`

Generated from `octomil-contracts/enums/*.yaml` via codegen. All enum types (device platform, artifact format, runtime executor, thermal state, etc.) live here.

**Do not hand-edit.** Run codegen from `octomil-contracts` to update.

## Source-of-Truth Dependencies

| Dependency | Source |
|---|---|
| Enum definitions | `octomil-contracts/enums/*.yaml` |
| Engine manifest | `octomil-contracts/fixtures/core/engine_manifest.json` |
| API semantics | `octomil-contracts/schemas/` |
| Conformance tests | `octomil-contracts/conformance/` |

## Test Commands

```bash
# All unit tests
./gradlew :octomil:testDebugUnitTest

# With coverage
./gradlew :octomil:testDebugUnitTest jacocoTestReport

# Specific test class
./gradlew :octomil:testDebugUnitTest --tests "ai.octomil.sdk.OctomilTest"

# Instrumented tests (requires emulator/device)
./gradlew :octomil:connectedDebugAndroidTest

# Build
./gradlew :octomil:assembleDebug

# Lint
./gradlew :octomil:lint
```

Tests use **JUnit 5** + **MockK** for mocking.

## Review Checklist

- [ ] New enum value: was it added to `octomil-contracts` first, then regenerated?
- [ ] Runtime change: are optional engine deps guarded against missing libraries?
- [ ] New public API: is it accessible from `sdk/Octomil.kt`?
- [ ] Facade change: does it handle both hosted and local paths?
- [ ] Streaming: does it work with Kotlin Flow?
- [ ] minSdk: does it compile for API 33+?
- [ ] ProGuard: are new public classes excluded from obfuscation in `consumer-rules.pro`?
- [ ] Conformance: do conformance tests still pass?
