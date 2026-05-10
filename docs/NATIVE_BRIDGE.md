# Android Native Runtime Bridge

This SDK now has a small Kotlin/JNI boundary scaffold for the OCT native
runtime under `ai.octomil.runtime.nativebridge`.

What is implemented:

- runtime ABI version probing
- runtime open and close handles
- runtime capabilities mapping into generated `RuntimeCapability` values
- OCT status to generated SDK error-code mapping
- explicit `Skipped` result when the JNI library is unavailable

What is intentionally not implemented:

- model open, model warm, session open, send, poll, event parsing, or inference
- cloud fallback from the native path
- any claim that `chat.stream` is a standalone advertised capability

Current blocker: the Android module has no native build wiring
(`externalNativeBuild`/CMake/NDK) and does not package a
`liboctomil_runtime_jni.so` bridge or a platform `liboctomil_runtime.so`.
Until those artifacts exist, `NativeRuntimeBridge.open()` fails closed with a
`NativeRuntimeResult.Skipped` carrying `runtime_unavailable`.

Generated Kotlin conformance tests remain the source of truth. The generated
native lifecycle tests stay ignored until a real JNI bridge and native runtime
artifact can run them without cloud fallback.
