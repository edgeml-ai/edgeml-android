#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {

using oct_status_t = uint32_t;
using oct_event_type_t = uint32_t;
using oct_priority_t = uint32_t;
using oct_accelerator_pref_t = uint32_t;
using oct_runtime_t = void;
using oct_model_t = void;
using oct_session_t = void;

constexpr uint32_t kRuntimeConfigVersion = 1;
constexpr uint32_t kSessionConfigVersion = 3;
constexpr uint32_t kModelConfigVersion = 1;
constexpr uint32_t kEventVersion = 1;

constexpr oct_status_t OCT_STATUS_OK = 0;
constexpr oct_status_t OCT_STATUS_UNSUPPORTED = 2;

constexpr oct_event_type_t OCT_EVENT_SESSION_STARTED = 1;
constexpr oct_event_type_t OCT_EVENT_AUDIO_CHUNK = 2;
constexpr oct_event_type_t OCT_EVENT_TRANSCRIPT_CHUNK = 3;
constexpr oct_event_type_t OCT_EVENT_ERROR = 7;
constexpr oct_event_type_t OCT_EVENT_SESSION_COMPLETED = 8;
constexpr oct_event_type_t OCT_EVENT_METRIC = 19;
constexpr oct_event_type_t OCT_EVENT_EMBEDDING_VECTOR = 20;
constexpr oct_event_type_t OCT_EVENT_TRANSCRIPT_SEGMENT = 21;
constexpr oct_event_type_t OCT_EVENT_TRANSCRIPT_FINAL = 22;
constexpr oct_event_type_t OCT_EVENT_TTS_AUDIO_CHUNK = 23;
constexpr oct_event_type_t OCT_EVENT_VAD_TRANSITION = 24;
constexpr oct_event_type_t OCT_EVENT_DIARIZATION_SEGMENT = 25;

struct oct_runtime_config_t {
    uint32_t version;
    const char* artifact_root;
    void* telemetry_sink;
    void* telemetry_user_data;
    uint32_t max_sessions;
};

struct oct_capabilities_t {
    uint32_t version;
    size_t size;
    const char** supported_engines;
    const char** supported_capabilities;
    const char** supported_archs;
    uint64_t ram_total_bytes;
    uint64_t ram_available_bytes;
    uint8_t has_apple_silicon;
    uint8_t has_cuda;
    uint8_t has_metal;
    uint8_t reserved0;
};

struct oct_model_config_t {
    uint32_t version;
    const char* model_uri;
    const char* artifact_digest;
    const char* engine_hint;
    const char* policy_preset;
    uint32_t accelerator_pref;
    uint64_t ram_budget_bytes;
    void* user_data;
};

struct oct_session_config_t {
    uint32_t version;
    const char* model_uri;
    const char* capability;
    const char* locality;
    const char* policy_preset;
    const char* speaker_id;
    uint32_t sample_rate_in;
    uint32_t sample_rate_out;
    oct_priority_t priority;
    void* user_data;
    const char* request_id;
    const char* route_id;
    const char* trace_id;
    const char* kv_prefix_key;
    oct_model_t* model;
};

struct oct_audio_view_t {
    const float* samples;
    uint32_t n_frames;
    uint32_t sample_rate;
    uint16_t channels;
    uint16_t reserved0;
};

/* v0.1.12 (ABI minor 11) — image input view. Mirrors oct_image_view_t in
 * octomil/runtime.h. The struct itself is local because the JNI shim never
 * links against the runtime headers — it only dlopens the shared object. */
struct oct_image_view_t {
    const uint8_t* bytes;
    size_t         n_bytes;
    uint32_t       mime;
    uint32_t       _reserved0;
};

constexpr oct_status_t OCT_STATUS_INVALID_INPUT = 1;

struct oct_event_t {
    uint32_t version;
    size_t size;
    oct_event_type_t type;
    uint64_t monotonic_ns;
    void* user_data;
    union {
        struct {
            const uint8_t* pcm;
            uint32_t n_bytes;
            uint32_t sample_rate;
            uint32_t sample_format;
            uint16_t channels;
            uint8_t is_final;
            uint8_t reserved0;
        } audio_chunk;
        struct {
            const char* utf8;
            uint32_t n_bytes;
        } transcript_chunk;
        struct {
            const char* code;
            const char* message;
            uint32_t error_code;
            uint32_t reserved0;
        } error;
        struct {
            const char* engine;
            const char* model_digest;
            const char* locality;
            const char* streaming_mode;
            const char* runtime_build_tag;
        } session_started;
        struct {
            float setup_ms;
            float engine_first_chunk_ms;
            float e2e_first_chunk_ms;
            float total_latency_ms;
            float queued_ms;
            uint32_t observed_chunks;
            uint8_t capability_verified;
            uint8_t reserved0;
            uint16_t reserved1;
            oct_status_t terminal_status;
        } session_completed;
        struct {
            const char* name;
            double value;
        } metric;
        struct {
            const float* values;
            uint32_t n_dim;
            uint32_t n_input_tokens;
            uint32_t index;
            uint32_t pooling_type;
            uint8_t is_normalized;
            uint8_t reserved0;
            uint16_t reserved1;
        } embedding_vector;
        struct {
            uint32_t transition_kind;
            uint32_t timestamp_ms;
            float confidence;
            uint32_t reserved0;
        } vad_transition;
        struct {
            const char* utf8;
            uint32_t n_bytes;
            uint32_t start_ms;
            uint32_t end_ms;
            uint32_t segment_index;
            uint8_t is_final;
            uint8_t reserved0;
            uint16_t reserved1;
        } transcript_segment;
        struct {
            const char* utf8;
            uint32_t n_bytes;
            uint32_t n_segments;
            uint32_t duration_ms;
            uint32_t reserved0;
            uint32_t reserved1;
        } transcript_final;
        struct {
            uint32_t start_ms;
            uint32_t end_ms;
            uint16_t speaker_id;
            uint16_t reserved0;
            uint32_t reserved1;
            const char* speaker_label;
        } diarization_segment;
        struct {
            const uint8_t* pcm;
            uint32_t n_bytes;
            uint32_t sample_rate;
            uint32_t sample_format;
            uint16_t channels;
            uint8_t is_final;
            uint8_t reserved0;
        } tts_audio_chunk;
    } data;
    const char* request_id;
    const char* route_id;
    const char* trace_id;
    const char* engine_version;
    const char* adapter_version;
    const char* accelerator;
    const char* artifact_digest;
    uint8_t cache_was_hit;
    uint8_t reserved0;
    uint16_t reserved1;
    uint32_t reserved2;
};

struct RuntimeApi {
    using RuntimeOpen = oct_status_t (*)(const oct_runtime_config_t*, oct_runtime_t**);
    using RuntimeClose = void (*)(oct_runtime_t*);
    using RuntimeCapabilities = oct_status_t (*)(oct_runtime_t*, oct_capabilities_t*);
    using RuntimeCapabilitiesFree = void (*)(oct_capabilities_t*);
    using VersionPart = uint32_t (*)();
    using LastError = int (*)(oct_runtime_t*, char*, size_t);
    using LastThreadError = int (*)(char*, size_t);
    using CacheIntrospect = oct_status_t (*)(oct_runtime_t*, char*, size_t);
    using ModelOpen = oct_status_t (*)(oct_runtime_t*, const oct_model_config_t*, oct_model_t**);
    using ModelWarm = oct_status_t (*)(oct_model_t*);
    using ModelClose = oct_status_t (*)(oct_model_t*);
    using SessionOpen = oct_status_t (*)(oct_runtime_t*, const oct_session_config_t*, oct_session_t**);
    using SessionClose = void (*)(oct_session_t*);
    using SessionSendAudio = oct_status_t (*)(oct_session_t*, const oct_audio_view_t*);
    using SessionSendText = oct_status_t (*)(oct_session_t*, const char*);
    using SessionSendImage = oct_status_t (*)(oct_session_t*, const oct_image_view_t*);
    using SessionPollEvent = oct_status_t (*)(oct_session_t*, oct_event_t*, uint32_t);
    using SessionCancel = oct_status_t (*)(oct_session_t*);

    void* handle = nullptr;
    std::string load_error;
    RuntimeOpen runtime_open = nullptr;
    RuntimeClose runtime_close = nullptr;
    RuntimeCapabilities runtime_capabilities = nullptr;
    RuntimeCapabilitiesFree runtime_capabilities_free = nullptr;
    VersionPart abi_major = nullptr;
    VersionPart abi_minor = nullptr;
    VersionPart abi_patch = nullptr;
    LastError runtime_last_error = nullptr;
    LastThreadError last_thread_error = nullptr;
    CacheIntrospect cache_introspect = nullptr;
    ModelOpen model_open = nullptr;
    ModelWarm model_warm = nullptr;
    ModelClose model_close = nullptr;
    SessionOpen session_open = nullptr;
    SessionClose session_close = nullptr;
    SessionSendAudio session_send_audio = nullptr;
    SessionSendText session_send_text = nullptr;
    /* v0.1.12 ABI minor 11 — lazy-resolved on first use via dlsym so a
     * v0.1.10 (minor 10) runtime that lacks the symbol still loads cleanly.
     * Use resolve_session_send_image() rather than reading the field
     * directly; the first call binds the pointer (or leaves it nullptr
     * if dlsym fails) and sets session_send_image_resolved = true. */
    SessionSendImage session_send_image = nullptr;
    bool session_send_image_resolved = false;
    SessionPollEvent session_poll_event = nullptr;
    SessionCancel session_cancel = nullptr;
};

std::mutex g_api_mutex;
RuntimeApi g_api;
bool g_loaded = false;

template <typename T>
bool bind_symbol(RuntimeApi& api, const char* name, T& out) {
    dlerror();
    auto* symbol = dlsym(api.handle, name);
    const char* error = dlerror();
    if (error != nullptr || symbol == nullptr) {
        api.load_error = std::string("liboctomil_runtime.so is missing symbol ") + name;
        out = nullptr;
        return false;
    }
    out = reinterpret_cast<T>(symbol);
    return true;
}

RuntimeApi& api() {
    std::lock_guard<std::mutex> lock(g_api_mutex);
    if (g_loaded) {
        return g_api;
    }
    g_loaded = true;
    g_api.handle = dlopen("liboctomil_runtime.so", RTLD_NOW | RTLD_LOCAL);
    if (g_api.handle == nullptr) {
        const char* error = dlerror();
        g_api.load_error = error != nullptr ? error : "liboctomil_runtime.so could not be loaded";
        return g_api;
    }
    bind_symbol(g_api, "oct_runtime_open", g_api.runtime_open) &&
        bind_symbol(g_api, "oct_runtime_close", g_api.runtime_close) &&
        bind_symbol(g_api, "oct_runtime_capabilities", g_api.runtime_capabilities) &&
        bind_symbol(g_api, "oct_runtime_capabilities_free", g_api.runtime_capabilities_free) &&
        bind_symbol(g_api, "oct_runtime_abi_version_major", g_api.abi_major) &&
        bind_symbol(g_api, "oct_runtime_abi_version_minor", g_api.abi_minor) &&
        bind_symbol(g_api, "oct_runtime_abi_version_patch", g_api.abi_patch) &&
        bind_symbol(g_api, "oct_runtime_last_error", g_api.runtime_last_error) &&
        bind_symbol(g_api, "oct_last_thread_error", g_api.last_thread_error) &&
        bind_symbol(g_api, "oct_runtime_cache_introspect", g_api.cache_introspect) &&
        bind_symbol(g_api, "oct_model_open", g_api.model_open) &&
        bind_symbol(g_api, "oct_model_warm", g_api.model_warm) &&
        bind_symbol(g_api, "oct_model_close", g_api.model_close) &&
        bind_symbol(g_api, "oct_session_open", g_api.session_open) &&
        bind_symbol(g_api, "oct_session_close", g_api.session_close) &&
        bind_symbol(g_api, "oct_session_send_audio", g_api.session_send_audio) &&
        bind_symbol(g_api, "oct_session_send_text", g_api.session_send_text) &&
        bind_symbol(g_api, "oct_session_poll_event", g_api.session_poll_event) &&
        bind_symbol(g_api, "oct_session_cancel", g_api.session_cancel);
    return g_api;
}

/* Lazy-bind oct_session_send_image (ABI minor 11, v0.1.12). Returns
 * nullptr if the loaded runtime is older (minor 10) and lacks the symbol.
 * The eager bind chain in api() deliberately does NOT include this symbol
 * so a v0.1.10 dylib continues to load cleanly. First-use is mutex-protected
 * to match the g_api_mutex discipline of the existing loader. */
RuntimeApi::SessionSendImage resolve_session_send_image() {
    std::lock_guard<std::mutex> lock(g_api_mutex);
    if (g_api.session_send_image_resolved) {
        return g_api.session_send_image;
    }
    g_api.session_send_image_resolved = true;
    if (g_api.handle == nullptr) {
        return nullptr;
    }
    dlerror();
    auto* symbol = dlsym(g_api.handle, "oct_session_send_image");
    const char* error = dlerror();
    if (error != nullptr || symbol == nullptr) {
        g_api.session_send_image = nullptr;
        return nullptr;
    }
    g_api.session_send_image = reinterpret_cast<RuntimeApi::SessionSendImage>(symbol);
    return g_api.session_send_image;
}

jstring make_string(JNIEnv* env, const char* value) {
    return value == nullptr ? nullptr : env->NewStringUTF(value);
}

jstring make_string(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string jstring_to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jobject box_long(JNIEnv* env, jlong value) {
    jclass cls = env->FindClass("java/lang/Long");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(J)V");
    return env->NewObject(cls, ctor, value);
}

jobject box_int(JNIEnv* env, jint value) {
    jclass cls = env->FindClass("java/lang/Integer");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(I)V");
    return env->NewObject(cls, ctor, value);
}

jobject box_bool(JNIEnv* env, bool value) {
    jclass cls = env->FindClass("java/lang/Boolean");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Z)V");
    return env->NewObject(cls, ctor, static_cast<jboolean>(value));
}

jobject box_double(JNIEnv* env, double value) {
    jclass cls = env->FindClass("java/lang/Double");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(D)V");
    return env->NewObject(cls, ctor, value);
}

jobject new_status_wire(JNIEnv* env, oct_status_t status, const char* message = nullptr) {
    jclass cls = env->FindClass("ai/octomil/runtime/nativebridge/NativeRuntimeStatusWire");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
    return env->NewObject(cls, ctor, static_cast<jint>(status), make_string(env, message));
}

jobject new_open_wire(JNIEnv* env, oct_status_t status, jlong handle, const char* message = nullptr) {
    jclass cls = env->FindClass("ai/octomil/runtime/nativebridge/NativeRuntimeOpenWire");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(IJLjava/lang/String;)V");
    return env->NewObject(cls, ctor, static_cast<jint>(status), handle, make_string(env, message));
}

jobject new_model_open_wire(JNIEnv* env, oct_status_t status, jlong handle, const char* message = nullptr) {
    jclass cls = env->FindClass("ai/octomil/runtime/nativebridge/NativeModelOpenWire");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(IJLjava/lang/String;)V");
    return env->NewObject(cls, ctor, static_cast<jint>(status), handle, make_string(env, message));
}

jobject new_session_open_wire(JNIEnv* env, oct_status_t status, jlong handle, const char* message = nullptr) {
    jclass cls = env->FindClass("ai/octomil/runtime/nativebridge/NativeSessionOpenWire");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(IJLjava/lang/String;)V");
    return env->NewObject(cls, ctor, static_cast<jint>(status), handle, make_string(env, message));
}

jobject new_cache_wire(JNIEnv* env, oct_status_t status, const char* message, const char* json) {
    jclass cls = env->FindClass("ai/octomil/runtime/nativebridge/NativeRuntimeCacheIntrospectWire");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;Ljava/lang/String;)V");
    return env->NewObject(cls, ctor, static_cast<jint>(status), make_string(env, message), make_string(env, json));
}

jobjectArray make_string_array(JNIEnv* env, const char** values) {
    jclass string_cls = env->FindClass("java/lang/String");
    std::vector<jstring> strings;
    if (values != nullptr) {
        for (size_t i = 0; values[i] != nullptr; ++i) {
            strings.push_back(make_string(env, values[i]));
        }
    }
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(strings.size()), string_cls, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(strings.size()); ++i) {
        env->SetObjectArrayElement(array, i, strings[static_cast<size_t>(i)]);
        env->DeleteLocalRef(strings[static_cast<size_t>(i)]);
    }
    return array;
}

jbyteArray make_byte_array(JNIEnv* env, const uint8_t* data, uint32_t n_bytes) {
    if (data == nullptr || n_bytes == 0) {
        return env->NewByteArray(0);
    }
    jbyteArray array = env->NewByteArray(static_cast<jsize>(n_bytes));
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(n_bytes), reinterpret_cast<const jbyte*>(data));
    return array;
}

jfloatArray make_float_array(JNIEnv* env, const float* data, uint32_t n_values) {
    if (data == nullptr || n_values == 0) {
        return env->NewFloatArray(0);
    }
    jfloatArray array = env->NewFloatArray(static_cast<jsize>(n_values));
    env->SetFloatArrayRegion(array, 0, static_cast<jsize>(n_values), data);
    return array;
}

jobject new_event_wire(
    JNIEnv* env,
    const char* kind,
    jstring message = nullptr,
    jstring text = nullptr,
    jbyteArray bytes = nullptr,
    jfloatArray floats = nullptr,
    jobject start_ms = nullptr,
    jobject end_ms = nullptr,
    jobject sample_rate = nullptr,
    jobject voiced = nullptr,
    jobject confidence = nullptr,
    jobject speaker_tag = nullptr,
    jobject normalized = nullptr,
    jstring metric_name = nullptr,
    jobject metric_value = nullptr,
    jstring engine = nullptr,
    jstring model_digest = nullptr,
    jstring locality = nullptr,
    jstring streaming_mode = nullptr,
    jobject total_latency_ms = nullptr,
    jobject terminal_status_code = nullptr,
    jobject segment_index = nullptr,
    jobject is_final = nullptr,
    jobject n_segments = nullptr,
    jobject duration_ms = nullptr
) {
    jclass cls = env->FindClass("ai/octomil/runtime/nativebridge/NativeSessionEventWire");
    jmethodID ctor = env->GetMethodID(
        cls,
        "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[B[FLjava/lang/Long;Ljava/lang/Long;Ljava/lang/Integer;Ljava/lang/Boolean;Ljava/lang/Double;Ljava/lang/Integer;Ljava/lang/Boolean;Ljava/lang/String;Ljava/lang/Double;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Double;Ljava/lang/Integer;Ljava/lang/Integer;Ljava/lang/Boolean;Ljava/lang/Integer;Ljava/lang/Long;)V"
    );
    return env->NewObject(
        cls,
        ctor,
        make_string(env, kind),
        message,
        text,
        bytes,
        floats,
        start_ms,
        end_ms,
        sample_rate,
        voiced,
        confidence,
        speaker_tag,
        normalized,
        metric_name,
        metric_value,
        engine,
        model_digest,
        locality,
        streaming_mode,
        total_latency_ms,
        terminal_status_code,
        segment_index,
        is_final,
        n_segments,
        duration_ms
    );
}

jobject event_to_wire(JNIEnv* env, const oct_event_t& event) {
    switch (event.type) {
        case OCT_EVENT_SESSION_STARTED:
            return new_event_wire(
                env,
                "session_started",
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                make_string(env, event.data.session_started.engine),
                make_string(env, event.data.session_started.model_digest),
                make_string(env, event.data.session_started.locality),
                make_string(env, event.data.session_started.streaming_mode)
            );
        case OCT_EVENT_AUDIO_CHUNK:
            return new_event_wire(
                env,
                "audio_chunk",
                nullptr,
                nullptr,
                make_byte_array(env, event.data.audio_chunk.pcm, event.data.audio_chunk.n_bytes),
                nullptr,
                nullptr,
                nullptr,
                box_int(env, static_cast<jint>(event.data.audio_chunk.sample_rate))
            );
        case OCT_EVENT_TRANSCRIPT_CHUNK:
            return new_event_wire(env, "transcript_chunk", nullptr, make_string(env, event.data.transcript_chunk.utf8));
        case OCT_EVENT_ERROR:
            return new_event_wire(env, "error", make_string(env, event.data.error.code), make_string(env, event.data.error.message));
        case OCT_EVENT_SESSION_COMPLETED:
            return new_event_wire(
                env,
                "session_completed",
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                box_double(env, event.data.session_completed.total_latency_ms),
                box_int(env, static_cast<jint>(event.data.session_completed.terminal_status))
            );
        case OCT_EVENT_METRIC:
            return new_event_wire(
                env,
                "metric",
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                make_string(env, event.data.metric.name),
                box_double(env, event.data.metric.value)
            );
        case OCT_EVENT_EMBEDDING_VECTOR:
            return new_event_wire(
                env,
                "embedding_vector",
                nullptr,
                nullptr,
                nullptr,
                make_float_array(env, event.data.embedding_vector.values, event.data.embedding_vector.n_dim),
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                box_bool(env, event.data.embedding_vector.is_normalized != 0)
            );
        case OCT_EVENT_TRANSCRIPT_SEGMENT:
            return new_event_wire(
                env,
                "transcript_segment",
                nullptr,
                make_string(env, event.data.transcript_segment.utf8),
                nullptr,
                nullptr,
                box_long(env, event.data.transcript_segment.start_ms),
                box_long(env, event.data.transcript_segment.end_ms),
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                box_int(env, static_cast<jint>(event.data.transcript_segment.segment_index)),
                box_bool(env, event.data.transcript_segment.is_final != 0)
            );
        case OCT_EVENT_TRANSCRIPT_FINAL:
            return new_event_wire(
                env,
                "transcript_final",
                nullptr,
                make_string(env, event.data.transcript_final.utf8),
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                box_int(env, static_cast<jint>(event.data.transcript_final.n_segments)),
                box_long(env, event.data.transcript_final.duration_ms)
            );
        case OCT_EVENT_TTS_AUDIO_CHUNK:
            return new_event_wire(
                env,
                "tts_chunk",
                nullptr,
                nullptr,
                make_byte_array(env, event.data.tts_audio_chunk.pcm, event.data.tts_audio_chunk.n_bytes),
                nullptr,
                nullptr,
                nullptr,
                box_int(env, static_cast<jint>(event.data.tts_audio_chunk.sample_rate))
            );
        case OCT_EVENT_VAD_TRANSITION:
            return new_event_wire(
                env,
                "vad_segment",
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                box_long(env, event.data.vad_transition.timestamp_ms),
                box_long(env, event.data.vad_transition.timestamp_ms),
                nullptr,
                box_bool(env, event.data.vad_transition.transition_kind == 1),
                box_double(env, event.data.vad_transition.confidence)
            );
        case OCT_EVENT_DIARIZATION_SEGMENT:
            return new_event_wire(
                env,
                "diarization_segment",
                nullptr,
                nullptr,
                nullptr,
                nullptr,
                box_long(env, event.data.diarization_segment.start_ms),
                box_long(env, event.data.diarization_segment.end_ms),
                nullptr,
                nullptr,
                nullptr,
                box_int(env, static_cast<jint>(event.data.diarization_segment.speaker_id))
            );
        default:
            return new_event_wire(env, "unknown", make_string(env, "unknown native event type"));
    }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeRuntimeLoadError(JNIEnv* env, jobject) {
    RuntimeApi& runtime = api();
    if (runtime.load_error.empty()) {
        return nullptr;
    }
    return make_string(env, runtime.load_error);
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeAbiVersionMajor(JNIEnv*, jobject) {
    RuntimeApi& runtime = api();
    return runtime.abi_major == nullptr ? 0 : static_cast<jint>(runtime.abi_major());
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeAbiVersionMinor(JNIEnv*, jobject) {
    RuntimeApi& runtime = api();
    return runtime.abi_minor == nullptr ? 0 : static_cast<jint>(runtime.abi_minor());
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeAbiVersionPatch(JNIEnv*, jobject) {
    RuntimeApi& runtime = api();
    return runtime.abi_patch == nullptr ? 0 : static_cast<jint>(runtime.abi_patch());
}

extern "C" JNIEXPORT jobject JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeOpen(JNIEnv* env, jobject, jstring artifact_root, jint max_sessions) {
    RuntimeApi& runtime = api();
    if (runtime.runtime_open == nullptr) {
        return new_open_wire(env, OCT_STATUS_UNSUPPORTED, 0, runtime.load_error.c_str());
    }
    std::string artifact_root_value = jstring_to_string(env, artifact_root);
    oct_runtime_config_t config{};
    config.version = kRuntimeConfigVersion;
    config.artifact_root = artifact_root == nullptr ? nullptr : artifact_root_value.c_str();
    config.max_sessions = static_cast<uint32_t>(std::max(0, max_sessions));
    oct_runtime_t* handle = nullptr;
    oct_status_t status = runtime.runtime_open(&config, &handle);
    return new_open_wire(env, status, reinterpret_cast<jlong>(handle));
}

extern "C" JNIEXPORT jobject JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeCapabilities(JNIEnv* env, jobject, jlong runtime_handle) {
    RuntimeApi& runtime = api();
    if (runtime.runtime_capabilities == nullptr) {
        return nullptr;
    }
    oct_capabilities_t capabilities{};
    capabilities.version = 1;
    capabilities.size = sizeof(oct_capabilities_t);
    oct_status_t status = runtime.runtime_capabilities(reinterpret_cast<oct_runtime_t*>(runtime_handle), &capabilities);
    jclass cls = env->FindClass("ai/octomil/runtime/nativebridge/NativeRuntimeCapabilitiesWire");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;JJZZZ)V");
    jobject result = env->NewObject(
        cls,
        ctor,
        static_cast<jint>(status),
        nullptr,
        make_string_array(env, capabilities.supported_engines),
        make_string_array(env, capabilities.supported_capabilities),
        make_string_array(env, capabilities.supported_archs),
        static_cast<jlong>(capabilities.ram_total_bytes),
        static_cast<jlong>(capabilities.ram_available_bytes),
        static_cast<jboolean>(capabilities.has_apple_silicon != 0),
        static_cast<jboolean>(capabilities.has_cuda != 0),
        static_cast<jboolean>(capabilities.has_metal != 0)
    );
    if (runtime.runtime_capabilities_free != nullptr) {
        runtime.runtime_capabilities_free(&capabilities);
    }
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeCacheIntrospect(JNIEnv* env, jobject, jlong runtime_handle, jint buffer_bytes) {
    RuntimeApi& runtime = api();
    if (runtime.cache_introspect == nullptr) {
        return new_cache_wire(env, OCT_STATUS_UNSUPPORTED, runtime.load_error.c_str(), nullptr);
    }
    const size_t size = static_cast<size_t>(std::max(1, buffer_bytes));
    std::vector<char> buffer(size, '\0');
    oct_status_t status = runtime.cache_introspect(reinterpret_cast<oct_runtime_t*>(runtime_handle), buffer.data(), buffer.size());
    return new_cache_wire(env, status, nullptr, buffer.data());
}

extern "C" JNIEXPORT jobject JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeModelOpen(
    JNIEnv* env,
    jobject,
    jlong runtime_handle,
    jstring model_uri,
    jstring artifact_digest,
    jstring engine_hint,
    jstring policy_preset,
    jint accelerator_pref,
    jlong ram_budget_bytes,
    jlong user_data
) {
    RuntimeApi& runtime = api();
    if (runtime.model_open == nullptr) {
        return new_model_open_wire(env, OCT_STATUS_UNSUPPORTED, 0, runtime.load_error.c_str());
    }
    std::string model_uri_value = jstring_to_string(env, model_uri);
    std::string artifact_digest_value = jstring_to_string(env, artifact_digest);
    std::string engine_hint_value = jstring_to_string(env, engine_hint);
    std::string policy_preset_value = jstring_to_string(env, policy_preset);
    oct_model_config_t config{};
    config.version = kModelConfigVersion;
    config.model_uri = model_uri == nullptr ? nullptr : model_uri_value.c_str();
    config.artifact_digest = artifact_digest == nullptr ? nullptr : artifact_digest_value.c_str();
    config.engine_hint = engine_hint == nullptr ? nullptr : engine_hint_value.c_str();
    config.policy_preset = policy_preset == nullptr ? nullptr : policy_preset_value.c_str();
    config.accelerator_pref = static_cast<oct_accelerator_pref_t>(std::max(0, accelerator_pref));
    config.ram_budget_bytes = static_cast<uint64_t>(std::max<jlong>(0, ram_budget_bytes));
    config.user_data = reinterpret_cast<void*>(user_data);
    oct_model_t* model = nullptr;
    oct_status_t status = runtime.model_open(reinterpret_cast<oct_runtime_t*>(runtime_handle), &config, &model);
    return new_model_open_wire(env, status, reinterpret_cast<jlong>(model));
}

extern "C" JNIEXPORT jobject JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeModelWarm(JNIEnv* env, jobject, jlong model_handle) {
    RuntimeApi& runtime = api();
    oct_status_t status = runtime.model_warm == nullptr
        ? OCT_STATUS_UNSUPPORTED
        : runtime.model_warm(reinterpret_cast<oct_model_t*>(model_handle));
    return new_status_wire(env, status);
}

extern "C" JNIEXPORT void JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeModelClose(JNIEnv*, jobject, jlong model_handle) {
    RuntimeApi& runtime = api();
    if (runtime.model_close != nullptr) {
        runtime.model_close(reinterpret_cast<oct_model_t*>(model_handle));
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeSessionOpen(
    JNIEnv* env,
    jobject,
    jlong runtime_handle,
    jlong model_handle,
    jstring capability,
    jstring model_uri,
    jstring locality,
    jstring policy_preset,
    jstring speaker_id,
    jint sample_rate_in,
    jint sample_rate_out,
    jint priority,
    jlong user_data
) {
    RuntimeApi& runtime = api();
    if (runtime.session_open == nullptr) {
        return new_session_open_wire(env, OCT_STATUS_UNSUPPORTED, 0, runtime.load_error.c_str());
    }
    std::string capability_value = jstring_to_string(env, capability);
    std::string model_uri_value = jstring_to_string(env, model_uri);
    std::string locality_value = jstring_to_string(env, locality);
    std::string policy_preset_value = jstring_to_string(env, policy_preset);
    std::string speaker_id_value = jstring_to_string(env, speaker_id);
    oct_session_config_t config{};
    config.version = kSessionConfigVersion;
    config.model_uri = model_uri == nullptr ? nullptr : model_uri_value.c_str();
    config.capability = capability == nullptr ? nullptr : capability_value.c_str();
    config.locality = locality == nullptr ? "on_device" : locality_value.c_str();
    config.policy_preset = policy_preset == nullptr ? nullptr : policy_preset_value.c_str();
    config.speaker_id = speaker_id == nullptr ? nullptr : speaker_id_value.c_str();
    config.sample_rate_in = static_cast<uint32_t>(std::max(0, sample_rate_in));
    config.sample_rate_out = static_cast<uint32_t>(std::max(0, sample_rate_out));
    config.priority = static_cast<oct_priority_t>(std::max(0, priority));
    config.user_data = reinterpret_cast<void*>(user_data);
    config.model = reinterpret_cast<oct_model_t*>(model_handle);
    oct_session_t* session = nullptr;
    oct_status_t status = runtime.session_open(reinterpret_cast<oct_runtime_t*>(runtime_handle), &config, &session);
    return new_session_open_wire(env, status, reinterpret_cast<jlong>(session));
}

extern "C" JNIEXPORT jobject JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeSessionOpenModelFree(
    JNIEnv* env,
    jobject,
    jlong runtime_handle,
    jstring capability,
    jstring model_uri,
    jstring locality,
    jstring policy_preset,
    jstring speaker_id,
    jint sample_rate_in,
    jint sample_rate_out,
    jint priority,
    jlong user_data
) {
    RuntimeApi& runtime = api();
    if (runtime.session_open == nullptr) {
        return new_session_open_wire(env, OCT_STATUS_UNSUPPORTED, 0, runtime.load_error.c_str());
    }
    std::string capability_value = jstring_to_string(env, capability);
    std::string model_uri_value = jstring_to_string(env, model_uri);
    std::string locality_value = jstring_to_string(env, locality);
    std::string policy_preset_value = jstring_to_string(env, policy_preset);
    std::string speaker_id_value = jstring_to_string(env, speaker_id);
    oct_session_config_t config{};
    config.version = kSessionConfigVersion;
    config.model_uri = model_uri == nullptr ? nullptr : model_uri_value.c_str();
    config.capability = capability == nullptr ? nullptr : capability_value.c_str();
    config.locality = locality == nullptr ? "on_device" : locality_value.c_str();
    config.policy_preset = policy_preset == nullptr ? nullptr : policy_preset_value.c_str();
    config.speaker_id = speaker_id == nullptr ? nullptr : speaker_id_value.c_str();
    config.sample_rate_in = static_cast<uint32_t>(std::max(0, sample_rate_in));
    config.sample_rate_out = static_cast<uint32_t>(std::max(0, sample_rate_out));
    config.priority = static_cast<oct_priority_t>(std::max(0, priority));
    config.user_data = reinterpret_cast<void*>(user_data);
    config.model = nullptr;  // Model-free: runtime resolves via capability/model_uri.
    oct_session_t* session = nullptr;
    oct_status_t status = runtime.session_open(reinterpret_cast<oct_runtime_t*>(runtime_handle), &config, &session);
    return new_session_open_wire(env, status, reinterpret_cast<jlong>(session));
}

extern "C" JNIEXPORT jobject JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeSessionSendAudio(
    JNIEnv* env,
    jobject,
    jlong session_handle,
    jfloatArray samples,
    jint sample_rate,
    jint channels
) {
    RuntimeApi& runtime = api();
    if (runtime.session_send_audio == nullptr) {
        return new_status_wire(env, OCT_STATUS_UNSUPPORTED, runtime.load_error.c_str());
    }
    jsize sample_count = samples == nullptr ? 0 : env->GetArrayLength(samples);
    jfloat* sample_data = samples == nullptr ? nullptr : env->GetFloatArrayElements(samples, nullptr);
    oct_audio_view_t view{};
    view.samples = sample_data;
    view.channels = static_cast<uint16_t>(std::max(0, channels));
    view.sample_rate = static_cast<uint32_t>(std::max(0, sample_rate));
    view.n_frames = view.channels == 0 ? 0 : static_cast<uint32_t>(sample_count / view.channels);
    oct_status_t status = runtime.session_send_audio(reinterpret_cast<oct_session_t*>(session_handle), &view);
    if (samples != nullptr && sample_data != nullptr) {
        env->ReleaseFloatArrayElements(samples, sample_data, JNI_ABORT);
    }
    return new_status_wire(env, status);
}

extern "C" JNIEXPORT jobject JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeSessionSendText(JNIEnv* env, jobject, jlong session_handle, jstring text) {
    RuntimeApi& runtime = api();
    if (runtime.session_send_text == nullptr) {
        return new_status_wire(env, OCT_STATUS_UNSUPPORTED, runtime.load_error.c_str());
    }
    std::string value = jstring_to_string(env, text);
    oct_status_t status = runtime.session_send_text(reinterpret_cast<oct_session_t*>(session_handle), value.c_str());
    return new_status_wire(env, status);
}

/*
 * v0.1.12 ABI minor 11 — oct_session_send_image bridge. Resolved via
 * lazy dlsym; on older (minor 10) runtimes that lack the symbol we
 * return OCT_STATUS_UNSUPPORTED with a diagnostic message rather than
 * crashing on a missing entry point. The caller-side gate
 * (NativeRuntimeBridge.sendImage) MUST have already checked ABI >= 11
 * and capability presence; this shim is the symbol-presence safety net.
 */
extern "C" JNIEXPORT jobject JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeSessionSendImage(
    JNIEnv* env,
    jobject,
    jlong session_handle,
    jbyteArray bytes,
    jint byte_length,
    jint mime
) {
    RuntimeApi::SessionSendImage send_image = resolve_session_send_image();
    if (send_image == nullptr) {
        RuntimeApi& runtime = api();
        const char* message = runtime.handle == nullptr
            ? runtime.load_error.c_str()
            : "oct_session_send_image is not exported by the loaded runtime (requires ABI minor 11)";
        return new_status_wire(env, OCT_STATUS_UNSUPPORTED, message);
    }
    if (bytes == nullptr || byte_length <= 0) {
        return new_status_wire(env, OCT_STATUS_INVALID_INPUT, "image bytes must be non-empty");
    }
    jsize array_length = env->GetArrayLength(bytes);
    if (byte_length > array_length) {
        return new_status_wire(env, OCT_STATUS_INVALID_INPUT, "byte_length exceeds buffer size");
    }
    jbyte* byte_data = env->GetByteArrayElements(bytes, nullptr);
    if (byte_data == nullptr) {
        return new_status_wire(env, OCT_STATUS_INVALID_INPUT, "could not access image byte buffer");
    }
    oct_image_view_t view{};
    view.bytes = reinterpret_cast<const uint8_t*>(byte_data);
    view.n_bytes = static_cast<size_t>(byte_length);
    view.mime = static_cast<uint32_t>(std::max(0, mime));
    view._reserved0 = 0;
    oct_status_t status = send_image(reinterpret_cast<oct_session_t*>(session_handle), &view);
    env->ReleaseByteArrayElements(bytes, byte_data, JNI_ABORT);
    return new_status_wire(env, status);
}

extern "C" JNIEXPORT jobject JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeSessionPollEvent(JNIEnv* env, jobject, jlong session_handle, jint timeout_ms) {
    RuntimeApi& runtime = api();
    if (runtime.session_poll_event == nullptr) {
        jclass cls = env->FindClass("ai/octomil/runtime/nativebridge/NativeSessionPollWire");
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;Lai/octomil/runtime/nativebridge/NativeSessionEventWire;)V");
        return env->NewObject(cls, ctor, static_cast<jint>(OCT_STATUS_UNSUPPORTED), make_string(env, runtime.load_error), nullptr);
    }
    oct_event_t event{};
    event.version = kEventVersion;
    event.size = sizeof(oct_event_t);
    oct_status_t status = runtime.session_poll_event(
        reinterpret_cast<oct_session_t*>(session_handle),
        &event,
        static_cast<uint32_t>(std::max(0, timeout_ms))
    );
    jobject event_wire = status == OCT_STATUS_OK ? event_to_wire(env, event) : nullptr;
    jclass cls = env->FindClass("ai/octomil/runtime/nativebridge/NativeSessionPollWire");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;Lai/octomil/runtime/nativebridge/NativeSessionEventWire;)V");
    return env->NewObject(cls, ctor, static_cast<jint>(status), nullptr, event_wire);
}

extern "C" JNIEXPORT jobject JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeSessionCancel(JNIEnv* env, jobject, jlong session_handle) {
    RuntimeApi& runtime = api();
    oct_status_t status = runtime.session_cancel == nullptr
        ? OCT_STATUS_UNSUPPORTED
        : runtime.session_cancel(reinterpret_cast<oct_session_t*>(session_handle));
    return new_status_wire(env, status);
}

extern "C" JNIEXPORT void JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeSessionClose(JNIEnv*, jobject, jlong session_handle) {
    RuntimeApi& runtime = api();
    if (runtime.session_close != nullptr) {
        runtime.session_close(reinterpret_cast<oct_session_t*>(session_handle));
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeLastError(JNIEnv* env, jobject, jlong runtime_handle) {
    RuntimeApi& runtime = api();
    if (runtime.runtime_last_error == nullptr) {
        return nullptr;
    }
    char buffer[4096] = {};
    int n = runtime.runtime_last_error(reinterpret_cast<oct_runtime_t*>(runtime_handle), buffer, sizeof(buffer));
    return n <= 0 ? nullptr : make_string(env, buffer);
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeLastThreadError(JNIEnv* env, jobject) {
    RuntimeApi& runtime = api();
    if (runtime.last_thread_error == nullptr) {
        return nullptr;
    }
    char buffer[4096] = {};
    int n = runtime.last_thread_error(buffer, sizeof(buffer));
    return n <= 0 ? nullptr : make_string(env, buffer);
}

extern "C" JNIEXPORT void JNICALL
Java_ai_octomil_runtime_nativebridge_SystemNativeRuntimeJni_nativeClose(JNIEnv*, jobject, jlong runtime_handle) {
    RuntimeApi& runtime = api();
    if (runtime.runtime_close != nullptr) {
        runtime.runtime_close(reinterpret_cast<oct_runtime_t*>(runtime_handle));
    }
}
