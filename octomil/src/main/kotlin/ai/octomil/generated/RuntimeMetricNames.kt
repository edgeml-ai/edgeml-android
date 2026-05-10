package ai.octomil.generated

// Auto-generated from octomil-contracts runtime_metric.json. Do not edit.
//
// Closed set of metric names emitted by the native runtime via OCT_EVENT_METRIC.

object RuntimeMetricName {
    const val CACHE_AUDIO_PHONEME_HIT_RATE = "cache.audio.phoneme.hit_rate"
    const val CACHE_AUDIO_PHRASE_HIT_RATE = "cache.audio.phrase.hit_rate"
    const val CACHE_AUDIO_VOICE_HIT_RATE = "cache.audio.voice.hit_rate"
    const val CACHE_ROUTE_HIT_RATE = "cache.route.hit_rate"
    const val GPU_ACTIVE_PCT = "gpu.active_pct"
    const val GPU_POWER_W = "gpu.power_w"
    const val KV_PREFIX_BYTES = "kv_prefix.bytes"
    const val KV_PREFIX_CACHE_HIT = "kv_prefix.cache_hit"
    const val KV_PREFIX_CACHE_HIT_RATE = "kv_prefix.cache_hit_rate"
    const val KV_PREFIX_CACHE_MISS = "kv_prefix.cache_miss"
    const val KV_PREFIX_SAVED_TOKENS = "kv_prefix.saved_tokens"
    const val KV_PREFIX_SAVED_TOKENS_TOTAL = "kv_prefix.saved_tokens_total"
    const val MIMI_FRAMES_DROPPED_TOTAL = "mimi.frames_dropped_total"
    const val MIMI_FRAMES_ENCODED_TOTAL = "mimi.frames_encoded_total"
    const val MODEL_EVICT_COUNT_TOTAL = "model.evict_count_total"
    const val MODEL_LOAD_MS = "model.load_ms"
    const val MODEL_WARM_MS = "model.warm_ms"
    const val SCHEDULER_PREEMPT_COUNT_TOTAL = "scheduler.preempt_count_total"
    const val SCHEDULER_QUEUE_DEPTH = "scheduler.queue_depth"
    const val SPEAKER_AUDIO_DURATION_MS = "speaker.audio_duration_ms"
    const val SPEAKER_INFERENCE_MS = "speaker.inference_ms"
    const val SPEAKER_SETUP_MS = "speaker.setup_ms"
    const val TTS_AUDIO_DURATION_MS = "tts.audio_duration_ms"
    const val TTS_CHUNK_COUNT = "tts.chunk_count"
    const val TTS_FIRST_AUDIO_MS = "tts.first_audio_ms"
    const val TTS_FIRST_CHUNK_AFTER_SYNTH_MS = "tts.first_chunk_after_synth_ms"
    const val TTS_REAL_TIME_FACTOR = "tts.real_time_factor"
    const val TTS_SESSION_OPEN_MS = "tts.session_open_ms"
    const val TTS_SYNTHESIZE_MS = "tts.synthesize_ms"
    const val VAD_AUDIO_DURATION_MS = "vad.audio_duration_ms"
    const val VAD_INFERENCE_MS = "vad.inference_ms"
    const val VAD_REAL_TIME_FACTOR = "vad.real_time_factor"
    const val VAD_SETUP_MS = "vad.setup_ms"
    const val WHISPER_AUDIO_DURATION_MS = "whisper.audio_duration_ms"
    const val WHISPER_DECODE_MS = "whisper.decode_ms"
    const val WHISPER_DIGEST_ADMISSION_OK = "whisper.digest_admission_ok"
    const val WHISPER_LOAD_MS = "whisper.load_ms"
    const val WHISPER_QUEUE_MS = "whisper.queue_ms"
    const val WHISPER_REAL_TIME_FACTOR = "whisper.real_time_factor"
    const val WHISPER_SESSION_OPEN_MS = "whisper.session_open_ms"

    val ALL_RUNTIME_METRICS = listOf(
        CACHE_AUDIO_PHONEME_HIT_RATE,
        CACHE_AUDIO_PHRASE_HIT_RATE,
        CACHE_AUDIO_VOICE_HIT_RATE,
        CACHE_ROUTE_HIT_RATE,
        GPU_ACTIVE_PCT,
        GPU_POWER_W,
        KV_PREFIX_BYTES,
        KV_PREFIX_CACHE_HIT,
        KV_PREFIX_CACHE_HIT_RATE,
        KV_PREFIX_CACHE_MISS,
        KV_PREFIX_SAVED_TOKENS,
        KV_PREFIX_SAVED_TOKENS_TOTAL,
        MIMI_FRAMES_DROPPED_TOTAL,
        MIMI_FRAMES_ENCODED_TOTAL,
        MODEL_EVICT_COUNT_TOTAL,
        MODEL_LOAD_MS,
        MODEL_WARM_MS,
        SCHEDULER_PREEMPT_COUNT_TOTAL,
        SCHEDULER_QUEUE_DEPTH,
        SPEAKER_AUDIO_DURATION_MS,
        SPEAKER_INFERENCE_MS,
        SPEAKER_SETUP_MS,
        TTS_AUDIO_DURATION_MS,
        TTS_CHUNK_COUNT,
        TTS_FIRST_AUDIO_MS,
        TTS_FIRST_CHUNK_AFTER_SYNTH_MS,
        TTS_REAL_TIME_FACTOR,
        TTS_SESSION_OPEN_MS,
        TTS_SYNTHESIZE_MS,
        VAD_AUDIO_DURATION_MS,
        VAD_INFERENCE_MS,
        VAD_REAL_TIME_FACTOR,
        VAD_SETUP_MS,
        WHISPER_AUDIO_DURATION_MS,
        WHISPER_DECODE_MS,
        WHISPER_DIGEST_ADMISSION_OK,
        WHISPER_LOAD_MS,
        WHISPER_QUEUE_MS,
        WHISPER_REAL_TIME_FACTOR,
        WHISPER_SESSION_OPEN_MS,
    )
}
