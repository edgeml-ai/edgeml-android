# Octomil Android SDK — Samples

Small Activity-based samples for the three most common Octomil app capabilities.

| Sample | Capability | Key SDK API |
|--------|-----------|-------------|
| `ChatSampleActivity` | Text generation | `Octomil.responses.stream()` |
| `TranscriptionSampleActivity` | Speech-to-text | `Octomil.audio.transcribe()` |
| `PredictionSampleActivity` | Next-word prediction | `Octomil.text.predict()` |

## Prerequisites

1. One model already deployed to the device per capability:
   - **Chat**: e.g. `phi-4-mini` (llama.cpp)
   - **Transcription**: e.g. `whisper-small` (whisper.cpp) — also add a `test_audio.wav` to `res/raw/`
   - **Prediction**: e.g. `smollm2-135m` (llama.cpp)
2. Replace model IDs in each Activity if your deployed models use different names.
3. These samples assume local model resolution via `Octomil.init(context)`. They do not show auth, pairing, or managed delivery.

## Building

```bash
cd octomil-android
./gradlew :samples:assembleDebug
```

Install on a connected device:

```bash
./gradlew :samples:installDebug
```

Run the samples on a physical Android device. They are meant to demonstrate on-device inference, not emulator-first workflows.

## These samples vs. the companion app

| | SDK Samples (here) | [Companion App](https://github.com/octomil/octomil-app-android) |
|---|---|---|
| Purpose | Show the shortest useful integration for one capability | Evaluate models on-device and exercise the full phone-side flow |
| Scope | One capability per activity, minimal UI, local model assumptions | All capabilities, pairing, discovery, recovery, golden tests |
| Audience | SDK adopters adding Octomil to their app | Internal testing, demos, and device lab workflows |
