# Android Native Runtime Bridge

This SDK now has a small Kotlin/JNI boundary scaffold for the OCT native
runtime under `ai.octomil.runtime.nativebridge`.

What is implemented:

- Gradle/CMake wiring for the packageable `liboctomil_runtime_jni.so` bridge
- runtime ABI version probing
- runtime open and close handles
- runtime capabilities mapping into generated `RuntimeCapability` values
- OCT status to generated SDK error-code mapping
- explicit `Skipped` result when the JNI library is unavailable
- lifecycle scaffolding for native model, session, and event types
- fail-closed JNI call sites for model open/warm/close, session open/send/poll/cancel/close
- direct runtime/cache ABI call site for `cache.introspect`
- typed event placeholders for transcript, embedding, audio, VAD, diarization, TTS, error, and completion paths
- dynamic loading of `liboctomil_runtime.so` from the packaged app process

What is intentionally not implemented:

- bundling a platform `liboctomil_runtime.so` artifact in the Android SDK
- actual inference or event delivery when that runtime artifact is absent
- cloud fallback from the native path
- any cloud fallback from the native `chat.stream` path

Current blocker: the Android module now builds and packages
`liboctomil_runtime_jni.so`, but it still does not ship a platform
`liboctomil_runtime.so`. The JNI bridge dynamically loads that runtime library
and reports `NativeRuntimeResult.Skipped` with `runtime_unavailable` when the
runtime binary is absent or lacks the ABI 10 symbols. That is an artifact gap,
not a cloud fallback or a claim that the SDK cannot name the native lifecycle.

The current truth markers are:

- `chat.stream` is advertised when the native llama.cpp chat path is available
- `audio.stt.batch` is live-native-conditional through the native STT backend
- `cache.introspect` is live-native-conditional through the native cache ABI

The new lifecycle classes are intentionally thin:

- `NativeRuntime` can open a `NativeModel`
- `NativeModel` can warm, open a `NativeSession`, and close
- `NativeSession` can send audio/text, poll events, cancel, and close
- `NativeSessionEvent` parses the common streaming event families the native
  ABI is expected to emit

These types exist so the SDK can adopt the native ABI as soon as the bridge is
wired, while keeping the current build honest about the missing binary.

Generated Kotlin conformance tests remain the source of truth. The generated
native lifecycle tests stay ignored until a real JNI bridge and native runtime
artifact can run them without cloud fallback, but their ignore text now names
the missing shared libraries instead of implying the JNI symbols do not exist.
