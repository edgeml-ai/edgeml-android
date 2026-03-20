# Octomil Android SDK — Samples

Minimal, copyable examples for the main Octomil capabilities.

| Sample | Capability | Key SDK API |
|--------|-----------|-------------|
| `ChatSampleActivity` | Text generation | `Octomil.responses.stream()` |
| `TranscriptionSampleActivity` | Speech-to-text | `Octomil.audio.transcribe()` |
| `PredictionSampleActivity` | Next-word prediction | `Octomil.text.predict()` |

## Prerequisites

1. An Octomil account with API credentials ([app.octomil.com](https://app.octomil.com))
2. At least one model deployed per capability:
   - **Chat**: e.g. `phi-4-mini` (llama.cpp)
   - **Transcription**: e.g. `whisper-small` (whisper.cpp) — also add a `test_audio.wav` to `res/raw/`
   - **Prediction**: e.g. `smollm2-135m` (llama.cpp)
3. Replace model names in each Activity if using different models.

## Building

```bash
cd octomil-android
./gradlew :samples:assembleDebug
```

Install on a connected device:

```bash
./gradlew :samples:installDebug
```

## These samples vs. the companion app

| | SDK Samples (here) | [Companion App](https://github.com/octomil/octomil-app-android) |
|---|---|---|
| Purpose | Show the shortest integration path for each capability | Full evaluation and dogfood app |
| Scope | One capability per activity, ~100 lines each | All capabilities, pairing, discovery, golden tests |
| Audience | SDK adopters adding Octomil to their app | Internal testing and device lab |
